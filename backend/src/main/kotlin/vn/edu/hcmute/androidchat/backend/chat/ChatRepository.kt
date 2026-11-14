package vn.edu.hcmute.androidchat.backend.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.common.CursorPage
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

data class SaveMessageResult(
    val ack: MessageAckPayload,
    val created: MessageDto?,
    val isMember: Boolean = true
)

sealed interface GroupOpResult<out T> {
    data class Ok<T>(val value: T) : GroupOpResult<T>
    data class Err(val statusCode: Int, val code: String, val message: String) : GroupOpResult<Nothing>
}

/**
 * Persistence contract for Conversation and Message storage (module CHAT).
 */
interface ChatRepository {
    suspend fun listConversations(userId: Long, limit: Int): List<ConversationSummaryDto>
    suspend fun getOrCreateDirectConversation(userId: Long, targetUserId: Long, nowIso: String): ConversationSummaryDto
    suspend fun createGroupConversation(
        creatorId: Long,
        name: String,
        memberIds: List<Long>,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto>
    suspend fun listMembers(requesterId: Long, conversationId: Long): GroupOpResult<List<ConversationMemberDto>>
    suspend fun addMembers(
        requesterId: Long,
        conversationId: Long,
        userIds: List<Long>,
        nowIso: String
    ): GroupOpResult<List<ConversationMemberDto>>
    suspend fun updateMemberRole(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        newRole: String
    ): GroupOpResult<ConversationMemberDto>
    suspend fun removeMember(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        nowIso: String
    ): GroupOpResult<Unit>
    suspend fun saveReceipt(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        status: String,
        nowIso: String
    ): GroupOpResult<ReceiptPayload>
    suspend fun editMessage(userId: Long, messageId: Long, newContent: String, nowIso: String): GroupOpResult<MessageDto>
    suspend fun recallMessage(userId: Long, messageId: Long, nowIso: String): GroupOpResult<MessageDto>
    suspend fun forwardMessage(
        userId: Long,
        sourceMessageId: Long,
        targetConversationId: Long,
        clientMessageId: String,
        nowIso: String
    ): GroupOpResult<MessageDto>
    suspend fun toggleReaction(
        userId: Long,
        messageId: Long,
        emoji: String,
        nowIso: String
    ): GroupOpResult<ReactionUpdatedPayload>
    suspend fun searchMessages(
        userId: Long,
        conversationId: Long?,
        query: String,
        cursor: Long? = null,
        limit: Int = 20
    ): GroupOpResult<CursorPage<MessageDto>>
    suspend fun setConversationPinned(
        userId: Long,
        conversationId: Long,
        pinned: Boolean,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto>
    suspend fun listPinnedMessages(userId: Long, conversationId: Long): GroupOpResult<List<PinnedMessageDto>>
    suspend fun pinMessage(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        nowIso: String
    ): GroupOpResult<PinnedMessageDto>
    suspend fun unpinMessage(userId: Long, conversationId: Long, messageId: Long): GroupOpResult<Unit>
    suspend fun listMessages(userId: Long, conversationId: Long, cursor: Long? = null, limit: Int = 30): CursorPage<MessageDto>?
    suspend fun saveMessage(senderId: Long, payload: MessageSendPayload, nowIso: String): SaveMessageResult
    suspend fun activeMemberIds(conversationId: Long): Set<Long>
    suspend fun peerUserIdsFor(userId: Long): Set<Long>
    suspend fun updateLastSeen(userId: Long, lastSeenAt: String)
    suspend fun getLastSeenMap(userIds: Collection<Long>): Map<Long, String>
}

/**
 * PostgreSQL-backed repository aligned with `V001__chat_schema_draft.sql`.
 */
class PostgresChatRepository(private val dataSource: DataSource) : ChatRepository {
    @Volatile
    private var lastSeenSchemaEnsured = false

    override suspend fun listConversations(userId: Long, limit: Int): List<ConversationSummaryDto> =
        withContext(Dispatchers.IO) {
            val sql = """
                SELECT c.id, c.type, c.name, c.avatar_url, cm.is_pinned, c.updated_at,
                       CASE WHEN c.type = 'DIRECT' THEN (
                           SELECT cm2.user_id
                           FROM conversation_members cm2
                           WHERE cm2.conversation_id = c.id
                             AND cm2.status = 'ACTIVE'
                             AND cm2.user_id <> ?
                           LIMIT 1
                       ) END AS peer_user_id
                FROM conversations c
                JOIN conversation_members cm ON cm.conversation_id = c.id
                WHERE cm.user_id = ? AND cm.status = 'ACTIVE'
                ORDER BY cm.is_pinned DESC, c.updated_at DESC
                LIMIT ?
            """.trimIndent()

            dataSource.connection.use { conn ->
                val items = conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.setLong(2, userId)
                    stmt.setInt(3, limit.coerceIn(1, 100))
                    stmt.executeQuery().use { rs ->
                        val list = mutableListOf<ConversationSummaryDto>()
                        while (rs.next()) {
                            val peerId = rs.getLong("peer_user_id").takeUnless { rs.wasNull() }
                            list += ConversationSummaryDto(
                                id = rs.getLong("id"),
                                type = rs.getString("type"),
                                name = rs.getString("name"),
                                avatarUrl = rs.getString("avatar_url"),
                                isPinned = rs.getBoolean("is_pinned"),
                                updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
                                peerUserId = peerId
                            )
                        }
                        list
                    }
                }
                val peerIds = items.mapNotNull { it.peerUserId }.distinct()
                val lastSeenMap = if (peerIds.isNotEmpty()) fetchLastSeenMap(conn, peerIds) else emptyMap()
                items.map { item ->
                    val peerId = item.peerUserId
                    if (peerId != null) item.copy(peerLastSeenAt = lastSeenMap[peerId]) else item
                }
            }
        }

    override suspend fun getOrCreateDirectConversation(
        userId: Long,
        targetUserId: Long,
        nowIso: String
    ): ConversationSummaryDto = withContext(Dispatchers.IO) {
        val pairKey = "${minOf(userId, targetUserId)}:${maxOf(userId, targetUserId)}"
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            ensureLastSeenColumn(conn)
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val insertConvSql = """
                    INSERT INTO conversations (type, direct_pair_key, created_by, created_at, updated_at)
                    VALUES ('DIRECT', ?, ?, ?, ?)
                    ON CONFLICT (direct_pair_key) DO NOTHING
                """.trimIndent()
                conn.prepareStatement(insertConvSql).use { stmt ->
                    stmt.setString(1, pairKey)
                    stmt.setLong(2, userId)
                    stmt.setTimestamp(3, timestamp)
                    stmt.setTimestamp(4, timestamp)
                    stmt.executeUpdate()
                }

                val selectConvSql = """
                    SELECT id, updated_at
                    FROM conversations
                    WHERE direct_pair_key = ?
                    LIMIT 1
                """.trimIndent()
                var conversationId = 0L
                var updatedAt = nowIso
                conn.prepareStatement(selectConvSql).use { stmt ->
                    stmt.setString(1, pairKey)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            conversationId = rs.getLong("id")
                            updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
                        }
                    }
                }

                val insertMemberSql = """
                    INSERT INTO conversation_members (conversation_id, user_id, role, status)
                    VALUES (?, ?, 'MEMBER', 'ACTIVE')
                    ON CONFLICT (conversation_id, user_id) DO NOTHING
                """.trimIndent()
                for (memberId in setOf(userId, targetUserId)) {
                    conn.prepareStatement(insertMemberSql).use { stmt ->
                        stmt.setLong(1, conversationId)
                        stmt.setLong(2, memberId)
                        stmt.executeUpdate()
                    }
                }

                conn.commit()
                val peerLastSeen = fetchLastSeenMap(conn, listOf(targetUserId))[targetUserId]
                ConversationSummaryDto(
                    id = conversationId,
                    type = "DIRECT",
                    updatedAt = updatedAt,
                    peerUserId = targetUserId,
                    peerLastSeenAt = peerLastSeen
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun listMessages(
        userId: Long,
        conversationId: Long,
        cursor: Long?,
        limit: Int
    ): CursorPage<MessageDto>? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                if (!isActiveMember(conn, conversationId, userId)) return@withContext null

                val clampedLimit = limit.coerceIn(1, 100)
                val sql = if (cursor != null) {
                    """
                        SELECT id, client_message_id, conversation_id, sender_id, type, content,
                               media_ref, reply_to_id, metadata::text, created_at, edited_at, deleted_at
                        FROM messages
                        WHERE conversation_id = ? AND id < ?
                        ORDER BY id DESC
                        LIMIT ?
                    """.trimIndent()
                } else {
                    """
                        SELECT id, client_message_id, conversation_id, sender_id, type, content,
                               media_ref, reply_to_id, metadata::text, created_at, edited_at, deleted_at
                        FROM messages
                        WHERE conversation_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                    """.trimIndent()
                }

                val (pageDesc, hasMore) = conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, conversationId)
                    if (cursor != null) {
                        stmt.setLong(2, cursor)
                        stmt.setInt(3, clampedLimit + 1)
                    } else {
                        stmt.setInt(2, clampedLimit + 1)
                    }
                    stmt.executeQuery().use { rs ->
                        val rows = mutableListOf<MessageDto>()
                        while (rs.next()) {
                            rows += rs.toMessageDto()
                        }
                        val more = rows.size > clampedLimit
                        val page = if (more) rows.take(clampedLimit) else rows
                        page to more
                    }
                }
                val nextCursor = if (hasMore) pageDesc.lastOrNull()?.id?.toString() else null
                val enrichedAsc = resolveDeliveryStatuses(conn, pageDesc.asReversed())
                CursorPage(
                    items = enrichedAsc,
                    nextCursor = nextCursor,
                    hasMore = hasMore
                )
            }
        }

    override suspend fun saveMessage(
        senderId: Long,
        payload: MessageSendPayload,
        nowIso: String
    ): SaveMessageResult = withContext(Dispatchers.IO) {
        val clientUuid = UUID.fromString(payload.clientMessageId)
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, payload.conversationId, senderId)) {
                return@withContext SaveMessageResult(
                    ack = MessageAckPayload(payload.clientMessageId, payload.conversationId, 0L, "FORBIDDEN", nowIso),
                    created = null,
                    isMember = false
                )
            }

            val insertSql = """
                INSERT INTO messages (client_message_id, conversation_id, sender_id, type, content, media_ref, reply_to_id, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (sender_id, client_message_id) DO NOTHING
                RETURNING id, created_at
            """.trimIndent()

            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, clientUuid)
                stmt.setLong(2, payload.conversationId)
                stmt.setLong(3, senderId)
                stmt.setString(4, payload.type)
                stmt.setString(5, payload.content)
                if (payload.mediaRef != null) stmt.setLong(6, payload.mediaRef) else stmt.setNull(6, java.sql.Types.BIGINT)
                if (payload.replyToId != null) stmt.setLong(7, payload.replyToId) else stmt.setNull(7, java.sql.Types.BIGINT)
                stmt.setString(8, AppJson.encodeToString(payload.metadata))

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong("id")
                        val createdAt = rs.getTimestamp("created_at").toInstant().toString()
                        val dto = MessageDto(
                            id = id,
                            clientMessageId = payload.clientMessageId,
                            conversationId = payload.conversationId,
                            senderId = senderId,
                            type = payload.type,
                            content = payload.content,
                            mediaRef = payload.mediaRef,
                            replyToId = payload.replyToId,
                            metadata = payload.metadata,
                            createdAt = createdAt,
                            deliveryStatus = "ACCEPTED"
                        )
                        return@withContext SaveMessageResult(
                            ack = MessageAckPayload(payload.clientMessageId, payload.conversationId, id, "ACCEPTED", createdAt),
                            created = dto
                        )
                    }
                }
            }

            val existingSql = """
                SELECT id, created_at FROM messages
                WHERE sender_id = ? AND client_message_id = ?
            """.trimIndent()
            conn.prepareStatement(existingSql).use { stmt ->
                stmt.setLong(1, senderId)
                stmt.setObject(2, clientUuid)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    val id = rs.getLong("id")
                    val createdAt = rs.getTimestamp("created_at").toInstant().toString()
                    SaveMessageResult(
                        ack = MessageAckPayload(payload.clientMessageId, payload.conversationId, id, "DUPLICATE", createdAt),
                        created = null
                    )
                }
            }
        }
    }

    override suspend fun activeMemberIds(conversationId: Long): Set<Long> = withContext(Dispatchers.IO) {
        val sql = "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND status = 'ACTIVE'"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<Long>()
                    while (rs.next()) ids += rs.getLong("user_id")
                    ids
                }
            }
        }
    }

    override suspend fun peerUserIdsFor(userId: Long): Set<Long> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT DISTINCT cm2.user_id
            FROM conversation_members cm1
            JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
            WHERE cm1.user_id = ? AND cm1.status = 'ACTIVE' AND cm2.status = 'ACTIVE' AND cm2.user_id <> ?
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setLong(2, userId)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<Long>()
                    while (rs.next()) ids += rs.getLong("user_id")
                    ids
                }
            }
        }
    }

    override suspend fun updateLastSeen(userId: Long, lastSeenAt: String): Unit = withContext(Dispatchers.IO) {
        val timestamp = Timestamp.from(Instant.parse(lastSeenAt))
        dataSource.connection.use { conn ->
            ensureLastSeenColumn(conn)
            conn.prepareStatement("UPDATE users SET last_seen_at = ? WHERE id = ?").use { stmt ->
                stmt.setTimestamp(1, timestamp)
                stmt.setLong(2, userId)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun getLastSeenMap(userIds: Collection<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val distinctIds = userIds.filter { it > 0L }.distinct()
        if (distinctIds.isEmpty()) return@withContext emptyMap()
        dataSource.connection.use { conn ->
            fetchLastSeenMap(conn, distinctIds)
        }
    }

    override suspend fun createGroupConversation(
        creatorId: Long,
        name: String,
        memberIds: List<Long>,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto> = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        val uniqueMembers = (memberIds.filter { it > 0L } + creatorId).toSet()
        if (trimmedName.isEmpty() || uniqueMembers.size < 3) {
            return@withContext GroupOpResult.Err(
                400,
                "VALIDATION_ERROR",
                "Group name is required and at least 3 members (including creator) are needed"
            )
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val convSql = """
                    INSERT INTO conversations (type, name, created_by, created_at, updated_at)
                    VALUES ('GROUP', ?, ?, ?, ?)
                    RETURNING id
                """.trimIndent()
                val convId = conn.prepareStatement(convSql).use { stmt ->
                    stmt.setString(1, trimmedName)
                    stmt.setLong(2, creatorId)
                    stmt.setTimestamp(3, timestamp)
                    stmt.setTimestamp(4, timestamp)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
                }

                val memberSql = """
                    INSERT INTO conversation_members (conversation_id, user_id, role, status, joined_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?)
                """.trimIndent()
                for (memberId in uniqueMembers) {
                    conn.prepareStatement(memberSql).use { stmt ->
                        stmt.setLong(1, convId)
                        stmt.setLong(2, memberId)
                        stmt.setString(3, if (memberId == creatorId) "OWNER" else "MEMBER")
                        stmt.setTimestamp(4, timestamp)
                        stmt.executeUpdate()
                    }
                }
                conn.commit()
                GroupOpResult.Ok(
                    ConversationSummaryDto(
                        id = convId,
                        type = "GROUP",
                        name = trimmedName,
                        updatedAt = nowIso
                    )
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun listMembers(
        requesterId: Long,
        conversationId: Long
    ): GroupOpResult<List<ConversationMemberDto>> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, requesterId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "User is not an active member of this conversation")
            }
            val sql = """
                SELECT conversation_id, user_id, role, status, joined_at
                FROM conversation_members
                WHERE conversation_id = ? AND status = 'ACTIVE'
                ORDER BY joined_at ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<ConversationMemberDto>()
                    while (rs.next()) {
                        list += ConversationMemberDto(
                            conversationId = rs.getLong("conversation_id"),
                            userId = rs.getLong("user_id"),
                            role = rs.getString("role"),
                            status = rs.getString("status"),
                            joinedAt = rs.getTimestamp("joined_at").toInstant().toString()
                        )
                    }
                    GroupOpResult.Ok(list)
                }
            }
        }
    }

    override suspend fun addMembers(
        requesterId: Long,
        conversationId: Long,
        userIds: List<Long>,
        nowIso: String
    ): GroupOpResult<List<ConversationMemberDto>> = withContext(Dispatchers.IO) {
        val validIds = userIds.filter { it > 0L }.distinct()
        if (validIds.isEmpty()) {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "At least one valid userId is required")
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val requesterRole = activeMemberRole(conn, conversationId, requesterId)
                ?: return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            if (requesterRole != "OWNER" && requesterRole != "ADMIN") {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Only OWNER or ADMIN can add members")
            }
            val sql = """
                INSERT INTO conversation_members (conversation_id, user_id, role, status, joined_at, left_at)
                VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, NULL)
                ON CONFLICT (conversation_id, user_id) DO UPDATE
                SET status = 'ACTIVE', left_at = NULL
            """.trimIndent()
            for (targetId in validIds) {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, conversationId)
                    stmt.setLong(2, targetId)
                    stmt.setTimestamp(3, timestamp)
                    stmt.executeUpdate()
                }
            }
        }
        listMembers(requesterId, conversationId)
    }

    override suspend fun updateMemberRole(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        newRole: String
    ): GroupOpResult<ConversationMemberDto> = withContext(Dispatchers.IO) {
        val normalizedRole = newRole.uppercase()
        if (normalizedRole != "ADMIN" && normalizedRole != "MEMBER") {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Role must be ADMIN or MEMBER")
        }
        dataSource.connection.use { conn ->
            val requesterRole = activeMemberRole(conn, conversationId, requesterId)
                ?: return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            if (requesterRole != "OWNER") {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Only OWNER can change member roles")
            }
            if (targetUserId == requesterId) {
                return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "OWNER cannot demote self")
            }
            val sql = """
                UPDATE conversation_members
                SET role = ?
                WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
                RETURNING conversation_id, user_id, role, status, joined_at
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedRole)
                stmt.setLong(2, conversationId)
                stmt.setLong(3, targetUserId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Target member not found")
                    }
                    GroupOpResult.Ok(
                        ConversationMemberDto(
                            conversationId = rs.getLong("conversation_id"),
                            userId = rs.getLong("user_id"),
                            role = rs.getString("role"),
                            status = rs.getString("status"),
                            joinedAt = rs.getTimestamp("joined_at").toInstant().toString()
                        )
                    )
                }
            }
        }
    }

    override suspend fun removeMember(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        nowIso: String
    ): GroupOpResult<Unit> = withContext(Dispatchers.IO) {
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val requesterRole = activeMemberRole(conn, conversationId, requesterId)
                ?: return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            val targetRole = activeMemberRole(conn, conversationId, targetUserId)
                ?: return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Target member not found")

            val allowed = when {
                requesterId == targetUserId -> requesterRole != "OWNER"
                requesterRole == "OWNER" -> targetRole != "OWNER"
                requesterRole == "ADMIN" -> targetRole == "MEMBER"
                else -> false
            }
            if (!allowed) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Insufficient permission to remove member")
            }

            val nextStatus = if (requesterId == targetUserId) "LEFT" else "REMOVED"
            val sql = """
                UPDATE conversation_members
                SET status = ?, left_at = ?
                WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, nextStatus)
                stmt.setTimestamp(2, timestamp)
                stmt.setLong(3, conversationId)
                stmt.setLong(4, targetUserId)
                stmt.executeUpdate()
            }
            GroupOpResult.Ok(Unit)
        }
    }

    override suspend fun saveReceipt(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        status: String,
        nowIso: String
    ): GroupOpResult<ReceiptPayload> = withContext(Dispatchers.IO) {
        val normalized = status.uppercase()
        if (normalized != "DELIVERED" && normalized != "READ") {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Receipt status must be DELIVERED or READ")
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val msgExistsSql = "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? LIMIT 1"
            val msgExists = conn.prepareStatement(msgExistsSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.setLong(2, conversationId)
                stmt.executeQuery().use { it.next() }
            }
            if (!msgExists) {
                return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
            }

            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val upsertSql = """
                    INSERT INTO message_receipts (message_id, recipient_id, delivered_at, read_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (message_id, recipient_id) DO UPDATE
                    SET delivered_at = COALESCE(message_receipts.delivered_at, EXCLUDED.delivered_at),
                        read_at = COALESCE(message_receipts.read_at, EXCLUDED.read_at)
                    RETURNING read_at, delivered_at
                """.trimIndent()
                val (finalStatus, storedAt) = conn.prepareStatement(upsertSql).use { stmt ->
                    stmt.setLong(1, messageId)
                    stmt.setLong(2, userId)
                    stmt.setTimestamp(3, timestamp)
                    if (normalized == "READ") stmt.setTimestamp(4, timestamp) else stmt.setNull(4, java.sql.Types.TIMESTAMP)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val readAt = rs.getTimestamp("read_at")?.toInstant()?.toString()
                        val deliveredAt = rs.getTimestamp("delivered_at")?.toInstant()?.toString()
                        if (readAt != null) {
                            "READ" to readAt
                        } else {
                            "DELIVERED" to (deliveredAt ?: nowIso)
                        }
                    }
                }

                if (finalStatus == "READ") {
                    val updateLastReadSql = """
                        UPDATE conversation_members
                        SET last_read_message_id = GREATEST(COALESCE(last_read_message_id, 0), ?)
                        WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
                    """.trimIndent()
                    conn.prepareStatement(updateLastReadSql).use { stmt ->
                        stmt.setLong(1, messageId)
                        stmt.setLong(2, conversationId)
                        stmt.setLong(3, userId)
                        stmt.executeUpdate()
                    }
                }

                conn.commit()
                GroupOpResult.Ok(
                    ReceiptPayload(
                        conversationId = conversationId,
                        messageId = messageId,
                        userId = userId,
                        status = finalStatus,
                        at = storedAt
                    )
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun editMessage(
        userId: Long,
        messageId: Long,
        newContent: String,
        nowIso: String
    ): GroupOpResult<MessageDto> = withContext(Dispatchers.IO) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Edited content cannot be blank")
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val selectSql = "SELECT conversation_id, sender_id, type, deleted_at FROM messages WHERE id = ?"
            var convId = 0L
            var senderId = 0L
            var msgType = ""
            var deletedAt: Timestamp? = null
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
                    convId = rs.getLong("conversation_id")
                    senderId = rs.getLong("sender_id")
                    msgType = rs.getString("type")
                    deletedAt = rs.getTimestamp("deleted_at")
                }
            }
            if (!isActiveMember(conn, convId, userId) || senderId != userId) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Only the sender can edit this message")
            }
            if (msgType != "TEXT" || deletedAt != null) {
                return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Only active TEXT messages can be edited")
            }
            val updateSql = """
                UPDATE messages SET content = ?, edited_at = ? WHERE id = ?
                RETURNING id, client_message_id, conversation_id, sender_id, type, content,
                          media_ref, reply_to_id, metadata::text, created_at, edited_at, deleted_at
            """.trimIndent()
            val updatedDto = conn.prepareStatement(updateSql).use { stmt ->
                stmt.setString(1, trimmed)
                stmt.setTimestamp(2, timestamp)
                stmt.setLong(3, messageId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.toMessageDto()
                }
            }
            GroupOpResult.Ok(resolveDeliveryStatuses(conn, listOf(updatedDto)).first())
        }
    }

    override suspend fun recallMessage(
        userId: Long,
        messageId: Long,
        nowIso: String
    ): GroupOpResult<MessageDto> = withContext(Dispatchers.IO) {
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val selectSql = "SELECT conversation_id, sender_id FROM messages WHERE id = ?"
            var convId = 0L
            var senderId = 0L
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
                    convId = rs.getLong("conversation_id")
                    senderId = rs.getLong("sender_id")
                }
            }
            val role = activeMemberRole(conn, convId, userId)
                ?: return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            if (senderId != userId && role != "OWNER" && role != "ADMIN") {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Insufficient permission to recall message")
            }
            val updateSql = """
                UPDATE messages SET deleted_at = COALESCE(deleted_at, ?), content = '' WHERE id = ?
                RETURNING id, client_message_id, conversation_id, sender_id, type, content,
                          media_ref, reply_to_id, metadata::text, created_at, edited_at, deleted_at
            """.trimIndent()
            val recalledDto = conn.prepareStatement(updateSql).use { stmt ->
                stmt.setTimestamp(1, timestamp)
                stmt.setLong(2, messageId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.toMessageDto()
                }
            }
            GroupOpResult.Ok(resolveDeliveryStatuses(conn, listOf(recalledDto)).first())
        }
    }

    override suspend fun forwardMessage(
        userId: Long,
        sourceMessageId: Long,
        targetConversationId: Long,
        clientMessageId: String,
        nowIso: String
    ): GroupOpResult<MessageDto> = withContext(Dispatchers.IO) {
        if (clientMessageId.isBlank() || targetConversationId <= 0L) {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "clientMessageId and valid targetConversationId required")
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        val clientUuid = UUID.fromString(clientMessageId)
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, targetConversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not a member of target conversation")
            }
            val selectSql = "SELECT conversation_id, type, content, media_ref, metadata::text, deleted_at FROM messages WHERE id = ?"
            var srcConvId = 0L
            var srcType = "TEXT"
            var srcContent: String? = null
            var srcMediaRef: Long? = null
            var srcMetadata = "{}"
            var srcDeleted: Timestamp? = null
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setLong(1, sourceMessageId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Source message not found")
                    srcConvId = rs.getLong("conversation_id")
                    srcType = rs.getString("type")
                    srcContent = rs.getString("content")
                    srcMediaRef = rs.getLong("media_ref").takeUnless { rs.wasNull() }
                    srcMetadata = rs.getString("metadata") ?: "{}"
                    srcDeleted = rs.getTimestamp("deleted_at")
                }
            }
            if (!isActiveMember(conn, srcConvId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not a member of source conversation")
            }
            if (srcDeleted != null) {
                return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Cannot forward a recalled message")
            }
            val insertSql = """
                INSERT INTO messages (client_message_id, conversation_id, sender_id, type, content, media_ref, forwarded_from_id, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, created_at
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, clientUuid)
                stmt.setLong(2, targetConversationId)
                stmt.setLong(3, userId)
                stmt.setString(4, srcType)
                stmt.setString(5, srcContent)
                if (srcMediaRef != null) stmt.setLong(6, srcMediaRef) else stmt.setNull(6, java.sql.Types.BIGINT)
                stmt.setLong(7, sourceMessageId)
                stmt.setString(8, srcMetadata)
                stmt.setTimestamp(9, timestamp)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    GroupOpResult.Ok(
                        MessageDto(
                            id = rs.getLong("id"),
                            clientMessageId = clientMessageId,
                            conversationId = targetConversationId,
                            senderId = userId,
                            type = srcType,
                            content = srcContent,
                            mediaRef = srcMediaRef,
                            forwardedFromId = sourceMessageId,
                            metadata = AppJson.decodeFromString<JsonObject>(srcMetadata),
                            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                            deliveryStatus = "ACCEPTED"
                        )
                    )
                }
            }
        }
    }

    override suspend fun toggleReaction(
        userId: Long,
        messageId: Long,
        emoji: String,
        nowIso: String
    ): GroupOpResult<ReactionUpdatedPayload> = withContext(Dispatchers.IO) {
        val trimmedEmoji = emoji.trim()
        if (trimmedEmoji.isEmpty() || trimmedEmoji.length > 16) {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Invalid emoji")
        }
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            val convSql = "SELECT conversation_id FROM messages WHERE id = ?"
            val convId = conn.prepareStatement(convSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
                    rs.getLong("conversation_id")
                }
            }
            if (!isActiveMember(conn, convId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val delSql = "DELETE FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?"
            val deleted = conn.prepareStatement(delSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.setLong(2, userId)
                stmt.setString(3, trimmedEmoji)
                stmt.executeUpdate()
            }
            val added = deleted == 0
            if (added) {
                val insSql = "INSERT INTO message_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)"
                conn.prepareStatement(insSql).use { stmt ->
                    stmt.setLong(1, messageId)
                    stmt.setLong(2, userId)
                    stmt.setString(3, trimmedEmoji)
                    stmt.setTimestamp(4, timestamp)
                    stmt.executeUpdate()
                }
            }
            val countSql = "SELECT emoji, COUNT(*) AS cnt FROM message_reactions WHERE message_id = ? GROUP BY emoji"
            val counts = mutableMapOf<String, Int>()
            conn.prepareStatement(countSql).use { stmt ->
                stmt.setLong(1, messageId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        counts[rs.getString("emoji")] = rs.getInt("cnt")
                    }
                }
            }
            GroupOpResult.Ok(
                ReactionUpdatedPayload(
                    conversationId = convId,
                    messageId = messageId,
                    userId = userId,
                    emoji = trimmedEmoji,
                    added = added,
                    counts = counts
                )
            )
        }
    }

    override suspend fun searchMessages(
        userId: Long,
        conversationId: Long?,
        query: String,
        cursor: Long?,
        limit: Int
    ): GroupOpResult<CursorPage<MessageDto>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext GroupOpResult.Err(400, "VALIDATION_ERROR", "Search query cannot be blank")
        }
        val clampedLimit = limit.coerceIn(1, 100)
        dataSource.connection.use { conn ->
            if (conversationId != null && !isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member of this conversation")
            }
            val sql = """
                SELECT m.id, m.client_message_id, m.conversation_id, m.sender_id, m.type, m.content,
                       m.media_ref, m.reply_to_id, m.metadata::text, m.created_at, m.edited_at, m.deleted_at
                FROM messages m
                JOIN conversation_members cm ON cm.conversation_id = m.conversation_id
                WHERE cm.user_id = ? AND cm.status = 'ACTIVE'
                  AND m.deleted_at IS NULL
                  AND (? IS NULL OR m.conversation_id = ?)
                  AND (? IS NULL OR m.id < ?)
                  AND m.content ILIKE ?
                ORDER BY m.id DESC
                LIMIT ?
            """.trimIndent()
            val (page, hasMore) = conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                if (conversationId != null) {
                    stmt.setLong(2, conversationId)
                    stmt.setLong(3, conversationId)
                } else {
                    stmt.setNull(2, java.sql.Types.BIGINT)
                    stmt.setNull(3, java.sql.Types.BIGINT)
                }
                if (cursor != null) {
                    stmt.setLong(4, cursor)
                    stmt.setLong(5, cursor)
                } else {
                    stmt.setNull(4, java.sql.Types.BIGINT)
                    stmt.setNull(5, java.sql.Types.BIGINT)
                }
                stmt.setString(6, "%$trimmed%")
                stmt.setInt(7, clampedLimit + 1)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<MessageDto>()
                    while (rs.next()) {
                        rows += rs.toMessageDto()
                    }
                    val more = rows.size > clampedLimit
                    val sliced = if (more) rows.take(clampedLimit) else rows
                    sliced to more
                }
            }
            val nextCursor = if (hasMore) page.lastOrNull()?.id?.toString() else null
            val enrichedPage = resolveDeliveryStatuses(conn, page)
            GroupOpResult.Ok(CursorPage(items = enrichedPage, nextCursor = nextCursor, hasMore = hasMore))
        }
    }

    override suspend fun setConversationPinned(
        userId: Long,
        conversationId: Long,
        pinned: Boolean,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto> = withContext(Dispatchers.IO) {
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val updSql = """
                UPDATE conversation_members
                SET is_pinned = ?, pinned_at = ?
                WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
            """.trimIndent()
            conn.prepareStatement(updSql).use { stmt ->
                stmt.setBoolean(1, pinned)
                if (pinned) stmt.setTimestamp(2, timestamp) else stmt.setNull(2, java.sql.Types.TIMESTAMP)
                stmt.setLong(3, conversationId)
                stmt.setLong(4, userId)
                stmt.executeUpdate()
            }
            val selSql = "SELECT id, type, name, avatar_url, updated_at FROM conversations WHERE id = ?"
            conn.prepareStatement(selSql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext GroupOpResult.Err(404, "NOT_FOUND", "Conversation not found")
                    GroupOpResult.Ok(
                        ConversationSummaryDto(
                            id = rs.getLong("id"),
                            type = rs.getString("type"),
                            name = rs.getString("name"),
                            avatarUrl = rs.getString("avatar_url"),
                            isPinned = pinned,
                            updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
                        )
                    )
                }
            }
        }
    }

    override suspend fun listPinnedMessages(
        userId: Long,
        conversationId: Long
    ): GroupOpResult<List<PinnedMessageDto>> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val sql = """
                SELECT conversation_id, message_id, pinned_by, pinned_at
                FROM pinned_messages
                WHERE conversation_id = ?
                ORDER BY pinned_at DESC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<PinnedMessageDto>()
                    while (rs.next()) {
                        list += PinnedMessageDto(
                            conversationId = rs.getLong("conversation_id"),
                            messageId = rs.getLong("message_id"),
                            pinnedBy = rs.getLong("pinned_by"),
                            pinnedAt = rs.getTimestamp("pinned_at").toInstant().toString()
                        )
                    }
                    GroupOpResult.Ok(list)
                }
            }
        }
    }

    override suspend fun pinMessage(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        nowIso: String
    ): GroupOpResult<PinnedMessageDto> = withContext(Dispatchers.IO) {
        val timestamp = Timestamp.from(Instant.parse(nowIso))
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val sql = """
                INSERT INTO pinned_messages (conversation_id, message_id, pinned_by, pinned_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (conversation_id, message_id) DO UPDATE SET pinned_by = EXCLUDED.pinned_by, pinned_at = EXCLUDED.pinned_at
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.setLong(2, messageId)
                stmt.setLong(3, userId)
                stmt.setTimestamp(4, timestamp)
                stmt.executeUpdate()
            }
            GroupOpResult.Ok(PinnedMessageDto(conversationId, messageId, userId, nowIso))
        }
    }

    override suspend fun unpinMessage(
        userId: Long,
        conversationId: Long,
        messageId: Long
    ): GroupOpResult<Unit> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            if (!isActiveMember(conn, conversationId, userId)) {
                return@withContext GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
            }
            val sql = "DELETE FROM pinned_messages WHERE conversation_id = ? AND message_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, conversationId)
                stmt.setLong(2, messageId)
                stmt.executeUpdate()
            }
            GroupOpResult.Ok(Unit)
        }
    }

    private fun activeMemberRole(conn: java.sql.Connection, conversationId: Long, userId: Long): String? {
        val sql = """
            SELECT role FROM conversation_members
            WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, conversationId)
            stmt.setLong(2, userId)
            stmt.executeQuery().use { return if (it.next()) it.getString("role") else null }
        }
    }

    private fun isActiveMember(conn: java.sql.Connection, conversationId: Long, userId: Long): Boolean {
        val sql = """
            SELECT 1 FROM conversation_members
            WHERE conversation_id = ? AND user_id = ? AND status = 'ACTIVE'
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, conversationId)
            stmt.setLong(2, userId)
            stmt.executeQuery().use { return it.next() }
        }
    }

    private fun ensureLastSeenColumn(conn: java.sql.Connection) {
        if (lastSeenSchemaEnsured) return
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ")
            }
            lastSeenSchemaEnsured = true
        } catch (_: Exception) {
            // Safe fallback if DDL is restricted; migrations are expected to provide the column.
        }
    }

    private fun fetchLastSeenMap(conn: java.sql.Connection, userIds: Collection<Long>): Map<Long, String> {
        val distinctIds = userIds.filter { it > 0L }.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        ensureLastSeenColumn(conn)
        val placeholders = distinctIds.joinToString(",") { "?" }
        val sql = "SELECT id, last_seen_at FROM users WHERE id IN ($placeholders) AND last_seen_at IS NOT NULL"
        return conn.prepareStatement(sql).use { stmt ->
            distinctIds.forEachIndexed { index, uid -> stmt.setLong(index + 1, uid) }
            stmt.executeQuery().use { rs ->
                val map = mutableMapOf<Long, String>()
                while (rs.next()) {
                    rs.getTimestamp("last_seen_at")?.toInstant()?.toString()?.let { ts ->
                        map[rs.getLong("id")] = ts
                    }
                }
                map
            }
        }
    }

    private fun ResultSet.toMessageDto(): MessageDto {
        val metadataText = getString("metadata") ?: "{}"
        return MessageDto(
            id = getLong("id"),
            clientMessageId = getObject("client_message_id").toString(),
            conversationId = getLong("conversation_id"),
            senderId = getLong("sender_id"),
            type = getString("type"),
            content = getString("content"),
            mediaRef = getLong("media_ref").takeUnless { wasNull() },
            replyToId = getLong("reply_to_id").takeUnless { wasNull() },
            metadata = AppJson.decodeFromString<JsonObject>(metadataText),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            editedAt = getTimestamp("edited_at")?.toInstant()?.toString(),
            deletedAt = getTimestamp("deleted_at")?.toInstant()?.toString()
        )
    }

    private fun resolveDeliveryStatuses(conn: java.sql.Connection, messages: List<MessageDto>): List<MessageDto> {
        if (messages.isEmpty()) return messages
        val ids = messages.map { it.id }.distinct()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT mr.message_id,
                   BOOL_OR(mr.read_at IS NOT NULL) AS any_read,
                   BOOL_OR(mr.delivered_at IS NOT NULL) AS any_delivered
            FROM message_receipts mr
            JOIN messages m ON m.id = mr.message_id
            WHERE mr.message_id IN ($placeholders)
              AND mr.recipient_id <> m.sender_id
            GROUP BY mr.message_id
        """.trimIndent()
        val statusByMessageId = mutableMapOf<Long, String>()
        conn.prepareStatement(sql).use { stmt ->
            ids.forEachIndexed { index, id -> stmt.setLong(index + 1, id) }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val msgId = rs.getLong("message_id")
                    val anyRead = rs.getBoolean("any_read")
                    val anyDelivered = rs.getBoolean("any_delivered")
                    statusByMessageId[msgId] = when {
                        anyRead -> "READ"
                        anyDelivered -> "DELIVERED"
                        else -> "ACCEPTED"
                    }
                }
            }
        }
        return messages.map { it.copy(deliveryStatus = statusByMessageId[it.id] ?: "ACCEPTED") }
    }
}

/**
 * In-memory repository used only for testApplication and DB-free local skeleton mode.
 */
class InMemoryChatRepository : ChatRepository {
    private data class InMemoryReceipt(
        val deliveredAt: String,
        val readAt: String? = null
    )

    private val conversations = ConcurrentHashMap<Long, ConversationSummaryDto>()
    private val directPairIndex = ConcurrentHashMap<String, Long>()
    private val membersByConversation = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val memberRolesByConversation = ConcurrentHashMap<Long, ConcurrentHashMap<Long, ConversationMemberDto>>()
    private val receiptsByMessage = ConcurrentHashMap<Long, ConcurrentHashMap<Long, InMemoryReceipt>>()
    private val lastReadMessageIdByConvUser = ConcurrentHashMap<String, Long>()
    private val lastSeenByUser = ConcurrentHashMap<Long, String>()
    private val reactionsByMessage = ConcurrentHashMap<Long, ConcurrentHashMap<String, MutableSet<Long>>>()
    private val pinnedConversationsByUser = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val pinnedMessagesByConversation = ConcurrentHashMap<Long, ConcurrentHashMap<Long, PinnedMessageDto>>()
    private val messagesByConversation = ConcurrentHashMap<Long, CopyOnWriteArrayList<MessageDto>>()
    private val dedupIndex = ConcurrentHashMap<String, MessageDto>()
    private val nextConversationId = AtomicLong(1000L)
    private val nextMessageId = AtomicLong(1L)

    fun seedConversation(conversationId: Long, memberIds: Set<Long>, title: String = "Conversation $conversationId") {
        conversations[conversationId] = ConversationSummaryDto(
            id = conversationId,
            type = if (memberIds.size > 2) "GROUP" else "DIRECT",
            name = title,
            updatedAt = Instant.now().toString()
        )
        membersByConversation[conversationId] = ConcurrentHashMap.newKeySet<Long>().apply { addAll(memberIds) }
        if (memberIds.size == 2) {
            val sorted = memberIds.sorted()
            directPairIndex["${sorted[0]}:${sorted[1]}"] = conversationId
        }
    }

    override suspend fun listConversations(userId: Long, limit: Int): List<ConversationSummaryDto> =
        conversations.values
            .filter { conv -> membersByConversation[conv.id]?.contains(userId) == true }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 100))
            .map { conv ->
                val enrichedLastMsg = conv.lastMessage?.let { withDeliveryStatus(it) }
                if (conv.type == "DIRECT") {
                    val peerId = membersByConversation[conv.id]?.firstOrNull { it != userId }
                    conv.copy(
                        lastMessage = enrichedLastMsg,
                        peerUserId = peerId,
                        peerLastSeenAt = peerId?.let { lastSeenByUser[it] }
                    )
                } else {
                    conv.copy(lastMessage = enrichedLastMsg)
                }
            }

    override suspend fun getOrCreateDirectConversation(
        userId: Long,
        targetUserId: Long,
        nowIso: String
    ): ConversationSummaryDto {
        val pairKey = "${minOf(userId, targetUserId)}:${maxOf(userId, targetUserId)}"
        val existingId = directPairIndex[pairKey]
        if (existingId != null) {
            conversations[existingId]?.let { existing ->
                return existing.copy(
                    lastMessage = existing.lastMessage?.let { withDeliveryStatus(it) },
                    peerUserId = targetUserId,
                    peerLastSeenAt = lastSeenByUser[targetUserId]
                )
            }
        }
        val newId = nextConversationId.getAndIncrement()
        val created = ConversationSummaryDto(
            id = newId,
            type = "DIRECT",
            name = "Direct $newId",
            updatedAt = nowIso,
            peerUserId = targetUserId,
            peerLastSeenAt = lastSeenByUser[targetUserId]
        )
        val racedId = directPairIndex.putIfAbsent(pairKey, newId)
        if (racedId != null) {
            val existing = conversations[racedId] ?: created
            return existing.copy(
                lastMessage = existing.lastMessage?.let { withDeliveryStatus(it) },
                peerUserId = targetUserId,
                peerLastSeenAt = lastSeenByUser[targetUserId]
            )
        }
        conversations[newId] = created
        membersByConversation[newId] = ConcurrentHashMap.newKeySet<Long>().apply {
            add(userId)
            add(targetUserId)
        }
        return created
    }

    override suspend fun listMessages(
        userId: Long,
        conversationId: Long,
        cursor: Long?,
        limit: Int
    ): CursorPage<MessageDto>? {
        val members = membersByConversation[conversationId]
        if (members != null && userId !in members) return null
        if (members == null) return CursorPage(items = emptyList(), nextCursor = null, hasMore = false)

        val clampedLimit = limit.coerceIn(1, 100)
        val allDesc = messagesByConversation[conversationId]
            .orEmpty()
            .asReversed()
            .filter { msg -> cursor == null || msg.id < cursor }
        val hasMore = allDesc.size > clampedLimit
        val pageDesc = if (hasMore) allDesc.take(clampedLimit) else allDesc
        val nextCursor = if (hasMore) pageDesc.lastOrNull()?.id?.toString() else null
        return CursorPage(
            items = pageDesc.asReversed().map { withDeliveryStatus(it) },
            nextCursor = nextCursor,
            hasMore = hasMore
        )
    }

    override suspend fun saveMessage(
        senderId: Long,
        payload: MessageSendPayload,
        nowIso: String
    ): SaveMessageResult {
        val members = membersByConversation[payload.conversationId]
        if (members != null && senderId !in members) {
            return SaveMessageResult(
                ack = MessageAckPayload(payload.clientMessageId, payload.conversationId, 0L, "FORBIDDEN", nowIso),
                created = null,
                isMember = false
            )
        }

        val dedupKey = "$senderId:${payload.clientMessageId}"
        val existing = dedupIndex[dedupKey]
        if (existing != null) {
            return SaveMessageResult(
                ack = MessageAckPayload(
                    clientMessageId = payload.clientMessageId,
                    conversationId = payload.conversationId,
                    messageId = existing.id,
                    status = "DUPLICATE",
                    serverCreatedAt = existing.createdAt
                ),
                created = null
            )
        }

        val assignedId = nextMessageId.getAndIncrement()
        val created = withDeliveryStatus(
            MessageDto(
                id = assignedId,
                clientMessageId = payload.clientMessageId,
                conversationId = payload.conversationId,
                senderId = senderId,
                type = payload.type,
                content = payload.content,
                mediaRef = payload.mediaRef,
                replyToId = payload.replyToId,
                metadata = payload.metadata,
                createdAt = nowIso
            )
        )
        val raced = dedupIndex.putIfAbsent(dedupKey, created)
        if (raced != null) {
            return SaveMessageResult(
                ack = MessageAckPayload(
                    clientMessageId = payload.clientMessageId,
                    conversationId = payload.conversationId,
                    messageId = raced.id,
                    status = "DUPLICATE",
                    serverCreatedAt = raced.createdAt
                ),
                created = null
            )
        }

        messagesByConversation
            .computeIfAbsent(payload.conversationId) { CopyOnWriteArrayList() }
            .add(created)

        conversations.computeIfPresent(payload.conversationId) { _, existingConv ->
            existingConv.copy(lastMessage = created, updatedAt = nowIso)
        }

        return SaveMessageResult(
            ack = MessageAckPayload(
                clientMessageId = payload.clientMessageId,
                conversationId = payload.conversationId,
                messageId = assignedId,
                status = "ACCEPTED",
                serverCreatedAt = nowIso
            ),
            created = created
        )
    }

    override suspend fun activeMemberIds(conversationId: Long): Set<Long> =
        membersByConversation[conversationId]?.toSet() ?: emptySet()

    override suspend fun peerUserIdsFor(userId: Long): Set<Long> {
        val peers = mutableSetOf<Long>()
        for ((_, members) in membersByConversation) {
            if (userId in members) {
                peers.addAll(members)
            }
        }
        peers.remove(userId)
        return peers
    }

    override suspend fun updateLastSeen(userId: Long, lastSeenAt: String) {
        lastSeenByUser[userId] = lastSeenAt
    }

    override suspend fun getLastSeenMap(userIds: Collection<Long>): Map<Long, String> {
        if (userIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        for (uid in userIds) {
            lastSeenByUser[uid]?.let { result[uid] = it }
        }
        return result
    }

    override suspend fun createGroupConversation(
        creatorId: Long,
        name: String,
        memberIds: List<Long>,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto> {
        val trimmedName = name.trim()
        val uniqueMembers = (memberIds.filter { it > 0L } + creatorId).toSet()
        if (trimmedName.isEmpty() || uniqueMembers.size < 3) {
            return GroupOpResult.Err(
                400,
                "VALIDATION_ERROR",
                "Group name is required and at least 3 members (including creator) are needed"
            )
        }
        val convId = nextConversationId.getAndIncrement()
        val summary = ConversationSummaryDto(
            id = convId,
            type = "GROUP",
            name = trimmedName,
            updatedAt = nowIso
        )
        conversations[convId] = summary
        membersByConversation[convId] = ConcurrentHashMap.newKeySet<Long>().apply { addAll(uniqueMembers) }
        val roles = ConcurrentHashMap<Long, ConversationMemberDto>()
        for (memberId in uniqueMembers) {
            roles[memberId] = ConversationMemberDto(
                conversationId = convId,
                userId = memberId,
                role = if (memberId == creatorId) "OWNER" else "MEMBER",
                status = "ACTIVE",
                joinedAt = nowIso
            )
        }
        memberRolesByConversation[convId] = roles
        return GroupOpResult.Ok(summary)
    }

    override suspend fun listMembers(
        requesterId: Long,
        conversationId: Long
    ): GroupOpResult<List<ConversationMemberDto>> {
        val members = membersByConversation[conversationId]
        if (members == null || requesterId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "User is not an active member of this conversation")
        }
        val roleMap = memberRolesByConversation[conversationId]
        val items = members.map { uid ->
            roleMap?.get(uid) ?: ConversationMemberDto(
                conversationId = conversationId,
                userId = uid,
                role = "MEMBER",
                status = "ACTIVE",
                joinedAt = conversations[conversationId]?.updatedAt ?: ""
            )
        }
        return GroupOpResult.Ok(items)
    }

    override suspend fun addMembers(
        requesterId: Long,
        conversationId: Long,
        userIds: List<Long>,
        nowIso: String
    ): GroupOpResult<List<ConversationMemberDto>> {
        val validIds = userIds.filter { it > 0L }.distinct()
        if (validIds.isEmpty()) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "At least one valid userId is required")
        }
        val roles = memberRolesByConversation[conversationId]
        val requesterRole = roles?.get(requesterId)?.role
        if (requesterRole != "OWNER" && requesterRole != "ADMIN") {
            return GroupOpResult.Err(403, "FORBIDDEN", "Only OWNER or ADMIN can add members")
        }
        val members = membersByConversation.computeIfAbsent(conversationId) { ConcurrentHashMap.newKeySet() }
        for (uid in validIds) {
            if (members.add(uid)) {
                roles[uid] = ConversationMemberDto(
                    conversationId = conversationId,
                    userId = uid,
                    role = "MEMBER",
                    status = "ACTIVE",
                    joinedAt = nowIso
                )
            }
        }
        return listMembers(requesterId, conversationId)
    }

    override suspend fun updateMemberRole(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        newRole: String
    ): GroupOpResult<ConversationMemberDto> {
        val normalizedRole = newRole.uppercase()
        if (normalizedRole != "ADMIN" && normalizedRole != "MEMBER") {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Role must be ADMIN or MEMBER")
        }
        val roles = memberRolesByConversation[conversationId]
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Conversation not found")
        if (roles[requesterId]?.role != "OWNER") {
            return GroupOpResult.Err(403, "FORBIDDEN", "Only OWNER can change member roles")
        }
        if (targetUserId == requesterId) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "OWNER cannot demote self")
        }
        val existing = roles[targetUserId]
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Target member not found")
        val updated = existing.copy(role = normalizedRole)
        roles[targetUserId] = updated
        return GroupOpResult.Ok(updated)
    }

    override suspend fun removeMember(
        requesterId: Long,
        conversationId: Long,
        targetUserId: Long,
        nowIso: String
    ): GroupOpResult<Unit> {
        val roles = memberRolesByConversation[conversationId]
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Conversation not found")
        val requesterRole = roles[requesterId]?.role
            ?: return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        val targetRole = roles[targetUserId]?.role
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Target member not found")

        val allowed = when {
            requesterId == targetUserId -> requesterRole != "OWNER"
            requesterRole == "OWNER" -> targetRole != "OWNER"
            requesterRole == "ADMIN" -> targetRole == "MEMBER"
            else -> false
        }
        if (!allowed) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Insufficient permission to remove member")
        }
        membersByConversation[conversationId]?.remove(targetUserId)
        roles.remove(targetUserId)
        return GroupOpResult.Ok(Unit)
    }

    override suspend fun saveReceipt(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        status: String,
        nowIso: String
    ): GroupOpResult<ReceiptPayload> {
        val normalized = status.uppercase()
        if (normalized != "DELIVERED" && normalized != "READ") {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Receipt status must be DELIVERED or READ")
        }
        val members = membersByConversation[conversationId]
        if (members != null && userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        val messageExists = messagesByConversation[conversationId]?.any { it.id == messageId } == true
        if (!messageExists) {
            return GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
        }

        val byRecipient = receiptsByMessage.computeIfAbsent(messageId) { ConcurrentHashMap() }
        val updated = byRecipient.compute(userId) { _, existing ->
            when {
                existing == null -> InMemoryReceipt(
                    deliveredAt = nowIso,
                    readAt = if (normalized == "READ") nowIso else null
                )
                else -> InMemoryReceipt(
                    deliveredAt = existing.deliveredAt,
                    readAt = existing.readAt ?: if (normalized == "READ") nowIso else null
                )
            }
        }!!
        val finalStatus = if (updated.readAt != null) "READ" else "DELIVERED"
        val storedAt = updated.readAt ?: updated.deliveredAt
        if (finalStatus == "READ") {
            lastReadMessageIdByConvUser.merge("$conversationId:$userId", messageId, ::maxOf)
        }
        return GroupOpResult.Ok(
            ReceiptPayload(
                conversationId = conversationId,
                messageId = messageId,
                userId = userId,
                status = finalStatus,
                at = storedAt
            )
        )
    }

    override suspend fun editMessage(
        userId: Long,
        messageId: Long,
        newContent: String,
        nowIso: String
    ): GroupOpResult<MessageDto> {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Edited content cannot be blank")
        }
        for ((convId, list) in messagesByConversation) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val msg = list[idx]
                val members = membersByConversation[convId]
                if ((members != null && userId !in members) || msg.senderId != userId) {
                    return GroupOpResult.Err(403, "FORBIDDEN", "Only the sender can edit this message")
                }
                if (msg.type != "TEXT" || msg.deletedAt != null) {
                    return GroupOpResult.Err(400, "VALIDATION_ERROR", "Only active TEXT messages can be edited")
                }
                val updated = withDeliveryStatus(msg.copy(content = trimmed, editedAt = nowIso))
                list[idx] = updated
                return GroupOpResult.Ok(updated)
            }
        }
        return GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
    }

    override suspend fun recallMessage(
        userId: Long,
        messageId: Long,
        nowIso: String
    ): GroupOpResult<MessageDto> {
        for ((convId, list) in messagesByConversation) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val msg = list[idx]
                val members = membersByConversation[convId]
                if (members != null && userId !in members) {
                    return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
                }
                val role = memberRolesByConversation[convId]?.get(userId)?.role ?: "MEMBER"
                if (msg.senderId != userId && role != "OWNER" && role != "ADMIN") {
                    return GroupOpResult.Err(403, "FORBIDDEN", "Insufficient permission to recall message")
                }
                val updated = withDeliveryStatus(msg.copy(content = "", deletedAt = msg.deletedAt ?: nowIso))
                list[idx] = updated
                return GroupOpResult.Ok(updated)
            }
        }
        return GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
    }

    override suspend fun forwardMessage(
        userId: Long,
        sourceMessageId: Long,
        targetConversationId: Long,
        clientMessageId: String,
        nowIso: String
    ): GroupOpResult<MessageDto> {
        if (clientMessageId.isBlank() || targetConversationId <= 0L) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "clientMessageId and valid targetConversationId required")
        }
        val targetMembers = membersByConversation[targetConversationId]
        if (targetMembers != null && userId !in targetMembers) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not a member of target conversation")
        }
        val source = messagesByConversation.values.flatten().firstOrNull { it.id == sourceMessageId }
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Source message not found")
        val sourceMembers = membersByConversation[source.conversationId]
        if (sourceMembers != null && userId !in sourceMembers) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not a member of source conversation")
        }
        if (source.deletedAt != null) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Cannot forward a recalled message")
        }
        val newId = nextMessageId.getAndIncrement()
        val forwarded = withDeliveryStatus(
            MessageDto(
                id = newId,
                clientMessageId = clientMessageId,
                conversationId = targetConversationId,
                senderId = userId,
                type = source.type,
                content = source.content,
                mediaRef = source.mediaRef,
                forwardedFromId = source.id,
                metadata = source.metadata,
                createdAt = nowIso
            )
        )
        messagesByConversation.computeIfAbsent(targetConversationId) { CopyOnWriteArrayList() }.add(forwarded)
        return GroupOpResult.Ok(forwarded)
    }

    override suspend fun toggleReaction(
        userId: Long,
        messageId: Long,
        emoji: String,
        nowIso: String
    ): GroupOpResult<ReactionUpdatedPayload> {
        val trimmedEmoji = emoji.trim()
        if (trimmedEmoji.isEmpty() || trimmedEmoji.length > 16) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Invalid emoji")
        }
        val msg = messagesByConversation.values.flatten().firstOrNull { it.id == messageId }
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Message not found")
        val members = membersByConversation[msg.conversationId]
        if (members != null && userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        val byEmoji = reactionsByMessage.computeIfAbsent(messageId) { ConcurrentHashMap() }
        val users = byEmoji.computeIfAbsent(trimmedEmoji) { ConcurrentHashMap.newKeySet() }
        val added = if (userId in users) {
            users.remove(userId)
            false
        } else {
            users.add(userId)
            true
        }
        val counts = byEmoji.mapValues { it.value.size }.filterValues { it > 0 }
        return GroupOpResult.Ok(
            ReactionUpdatedPayload(
                conversationId = msg.conversationId,
                messageId = messageId,
                userId = userId,
                emoji = trimmedEmoji,
                added = added,
                counts = counts
            )
        )
    }

    override suspend fun searchMessages(
        userId: Long,
        conversationId: Long?,
        query: String,
        cursor: Long?,
        limit: Int
    ): GroupOpResult<CursorPage<MessageDto>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return GroupOpResult.Err(400, "VALIDATION_ERROR", "Search query cannot be blank")
        }
        if (conversationId != null) {
            val members = membersByConversation[conversationId]
            if (members != null && userId !in members) {
                return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member of this conversation")
            }
        }
        val clampedLimit = limit.coerceIn(1, 100)
        val allowedConvIds = if (conversationId != null) {
            listOf(conversationId)
        } else {
            membersByConversation.filterValues { userId in it }.keys.toList()
        }
        val matchingDesc = allowedConvIds
            .flatMap { cid -> messagesByConversation[cid].orEmpty() }
            .filter { msg ->
                msg.deletedAt == null &&
                    (cursor == null || msg.id < cursor) &&
                    (msg.content?.contains(trimmed, ignoreCase = true) == true)
            }
            .sortedByDescending { it.id }

        val hasMore = matchingDesc.size > clampedLimit
        val page = (if (hasMore) matchingDesc.take(clampedLimit) else matchingDesc).map { withDeliveryStatus(it) }
        val nextCursor = if (hasMore) page.lastOrNull()?.id?.toString() else null
        return GroupOpResult.Ok(CursorPage(items = page, nextCursor = nextCursor, hasMore = hasMore))
    }

    override suspend fun setConversationPinned(
        userId: Long,
        conversationId: Long,
        pinned: Boolean,
        nowIso: String
    ): GroupOpResult<ConversationSummaryDto> {
        val members = membersByConversation[conversationId]
        if (members == null || userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        val conv = conversations[conversationId]
            ?: return GroupOpResult.Err(404, "NOT_FOUND", "Conversation not found")
        val set = pinnedConversationsByUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        if (pinned) set.add(conversationId) else set.remove(conversationId)
        val updated = conv.copy(isPinned = pinned)
        conversations[conversationId] = updated
        return GroupOpResult.Ok(updated)
    }

    override suspend fun listPinnedMessages(
        userId: Long,
        conversationId: Long
    ): GroupOpResult<List<PinnedMessageDto>> {
        val members = membersByConversation[conversationId]
        if (members == null || userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        val items = pinnedMessagesByConversation[conversationId]?.values
            ?.sortedByDescending { it.pinnedAt }
            ?: emptyList()
        return GroupOpResult.Ok(items)
    }

    override suspend fun pinMessage(
        userId: Long,
        conversationId: Long,
        messageId: Long,
        nowIso: String
    ): GroupOpResult<PinnedMessageDto> {
        val members = membersByConversation[conversationId]
        if (members == null || userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        val dto = PinnedMessageDto(conversationId, messageId, userId, nowIso)
        pinnedMessagesByConversation
            .computeIfAbsent(conversationId) { ConcurrentHashMap() }[messageId] = dto
        return GroupOpResult.Ok(dto)
    }

    override suspend fun unpinMessage(
        userId: Long,
        conversationId: Long,
        messageId: Long
    ): GroupOpResult<Unit> {
        val members = membersByConversation[conversationId]
        if (members == null || userId !in members) {
            return GroupOpResult.Err(403, "FORBIDDEN", "Not an active member")
        }
        pinnedMessagesByConversation[conversationId]?.remove(messageId)
        return GroupOpResult.Ok(Unit)
    }

    private fun withDeliveryStatus(message: MessageDto): MessageDto {
        val byRecipient = receiptsByMessage[message.id]
        var anyRead = false
        var anyDelivered = false
        if (byRecipient != null) {
            for ((recipientId, receipt) in byRecipient) {
                if (recipientId == message.senderId) continue
                if (receipt.readAt != null) {
                    anyRead = true
                    break
                }
                anyDelivered = true
            }
        }
        val status = when {
            anyRead -> "READ"
            anyDelivered -> "DELIVERED"
            else -> "ACCEPTED"
        }
        return message.copy(deliveryStatus = status)
    }
}
