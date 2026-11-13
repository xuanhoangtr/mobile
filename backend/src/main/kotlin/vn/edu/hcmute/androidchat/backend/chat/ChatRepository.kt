package vn.edu.hcmute.androidchat.backend.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.common.CursorPage
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

/**
 * Persistence contract for Conversation and Message storage (module CHAT).
 */
interface ChatRepository {
    suspend fun listConversations(userId: Long, limit: Int): List<ConversationSummaryDto>
    suspend fun getOrCreateDirectConversation(userId: Long, targetUserId: Long, nowIso: String): ConversationSummaryDto
    suspend fun listMessages(userId: Long, conversationId: Long, cursor: Long? = null, limit: Int = 30): CursorPage<MessageDto>?
    suspend fun saveMessage(senderId: Long, payload: MessageSendPayload, nowIso: String): SaveMessageResult
    suspend fun activeMemberIds(conversationId: Long): Set<Long>
}

/**
 * PostgreSQL-backed repository aligned with `V001__chat_schema_draft.sql`.
 */
class PostgresChatRepository(private val dataSource: DataSource) : ChatRepository {

    override suspend fun listConversations(userId: Long, limit: Int): List<ConversationSummaryDto> =
        withContext(Dispatchers.IO) {
            val sql = """
                SELECT c.id, c.type, c.name, c.avatar_url, cm.is_pinned, c.updated_at
                FROM conversations c
                JOIN conversation_members cm ON cm.conversation_id = c.id
                WHERE cm.user_id = ? AND cm.status = 'ACTIVE'
                ORDER BY cm.is_pinned DESC, c.updated_at DESC
                LIMIT ?
            """.trimIndent()

            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.setInt(2, limit.coerceIn(1, 100))
                    stmt.executeQuery().use { rs ->
                        val items = mutableListOf<ConversationSummaryDto>()
                        while (rs.next()) {
                            items += ConversationSummaryDto(
                                id = rs.getLong("id"),
                                type = rs.getString("type"),
                                name = rs.getString("name"),
                                avatarUrl = rs.getString("avatar_url"),
                                isPinned = rs.getBoolean("is_pinned"),
                                updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
                            )
                        }
                        items
                    }
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
                ConversationSummaryDto(
                    id = conversationId,
                    type = "DIRECT",
                    updatedAt = updatedAt
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

                conn.prepareStatement(sql).use { stmt ->
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
                            val metadataText = rs.getString("metadata") ?: "{}"
                            rows += MessageDto(
                                id = rs.getLong("id"),
                                clientMessageId = rs.getObject("client_message_id").toString(),
                                conversationId = rs.getLong("conversation_id"),
                                senderId = rs.getLong("sender_id"),
                                type = rs.getString("type"),
                                content = rs.getString("content"),
                                mediaRef = rs.getLong("media_ref").takeUnless { rs.wasNull() },
                                replyToId = rs.getLong("reply_to_id").takeUnless { rs.wasNull() },
                                metadata = AppJson.decodeFromString<JsonObject>(metadataText),
                                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                                editedAt = rs.getTimestamp("edited_at")?.toInstant()?.toString(),
                                deletedAt = rs.getTimestamp("deleted_at")?.toInstant()?.toString()
                            )
                        }
                        val hasMore = rows.size > clampedLimit
                        val pageDesc = if (hasMore) rows.take(clampedLimit) else rows
                        val nextCursor = if (hasMore) pageDesc.lastOrNull()?.id?.toString() else null
                        CursorPage(
                            items = pageDesc.asReversed(),
                            nextCursor = nextCursor,
                            hasMore = hasMore
                        )
                    }
                }
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
                            createdAt = createdAt
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
}

/**
 * In-memory repository used only for testApplication and DB-free local skeleton mode.
 */
class InMemoryChatRepository : ChatRepository {
    private val conversations = ConcurrentHashMap<Long, ConversationSummaryDto>()
    private val directPairIndex = ConcurrentHashMap<String, Long>()
    private val membersByConversation = ConcurrentHashMap<Long, MutableSet<Long>>()
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

    override suspend fun getOrCreateDirectConversation(
        userId: Long,
        targetUserId: Long,
        nowIso: String
    ): ConversationSummaryDto {
        val pairKey = "${minOf(userId, targetUserId)}:${maxOf(userId, targetUserId)}"
        val existingId = directPairIndex[pairKey]
        if (existingId != null) {
            conversations[existingId]?.let { return it }
        }
        val newId = nextConversationId.getAndIncrement()
        val created = ConversationSummaryDto(
            id = newId,
            type = "DIRECT",
            name = "Direct $newId",
            updatedAt = nowIso
        )
        val racedId = directPairIndex.putIfAbsent(pairKey, newId)
        if (racedId != null) {
            return conversations[racedId] ?: created
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
            items = pageDesc.asReversed(),
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
        val created = MessageDto(
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
}
