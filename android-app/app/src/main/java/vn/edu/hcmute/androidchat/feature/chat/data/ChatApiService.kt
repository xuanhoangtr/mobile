package vn.edu.hcmute.androidchat.feature.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.chat.model.ChatMessageFormatter
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupRole
import java.io.IOException

data class CursorPageResult<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean
)

interface ChatApiService {
    suspend fun listConversations(
        baseUrl: String,
        accessToken: String,
        cursor: String? = null,
        limit: Int = 30
    ): Result<CursorPageResult<ConversationUiModel>>

    suspend fun createOrGetDirectConversation(
        baseUrl: String,
        accessToken: String,
        targetUserId: Long
    ): Result<ConversationUiModel>

    suspend fun listMessages(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        currentUserId: Long,
        cursor: String? = null,
        limit: Int = 30
    ): Result<CursorPageResult<MessageUiModel>>

    suspend fun createGroupConversation(
        baseUrl: String,
        accessToken: String,
        name: String,
        memberIds: List<Long>
    ): Result<ConversationUiModel>

    suspend fun listGroupMembers(
        baseUrl: String,
        accessToken: String,
        conversationId: Long
    ): Result<List<GroupMemberUiModel>>

    suspend fun addGroupMembers(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        userIds: List<Long>
    ): Result<List<GroupMemberUiModel>>

    suspend fun updateGroupMemberRole(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long,
        role: GroupRole
    ): Result<GroupMemberUiModel>

    suspend fun removeGroupMember(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long
    ): Result<Unit>

    suspend fun editMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        currentUserId: Long,
        content: String
    ): Result<MessageUiModel>

    suspend fun recallMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        currentUserId: Long
    ): Result<MessageUiModel>

    suspend fun forwardMessage(
        baseUrl: String,
        accessToken: String,
        sourceMessageId: Long,
        targetConversationId: Long,
        clientMessageId: String,
        currentUserId: Long
    ): Result<MessageUiModel>

    suspend fun toggleReaction(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        emoji: String
    ): Result<Map<String, Int>>

    suspend fun searchMessages(
        baseUrl: String,
        accessToken: String,
        conversationId: Long?,
        query: String,
        currentUserId: Long,
        cursor: String? = null,
        limit: Int = 20
    ): Result<CursorPageResult<MessageUiModel>>

    suspend fun setConversationPinned(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        pinned: Boolean
    ): Result<ConversationUiModel>

    suspend fun listPinnedMessageIds(
        baseUrl: String,
        accessToken: String,
        conversationId: Long
    ): Result<List<Long>>

    suspend fun pinMessage(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): Result<Unit>

    suspend fun unpinMessage(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): Result<Unit>

    suspend fun deleteMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long
    ): Result<Boolean> = Result.success(true)
}

object ChatRestParser {
    fun parseConversationsPage(rawJson: String): CursorPageResult<ConversationUiModel> {
        val root = JSONObject(rawJson)
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                add(parseConversationSummary(itemsJson.getJSONObject(i)))
            }
        }
        val nextCursor = root.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
        val hasMore = root.optBoolean("hasMore", false)
        return CursorPageResult(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    fun parseConversationSummary(obj: JSONObject): ConversationUiModel {
        val type = if (obj.optString("type").equals("GROUP", ignoreCase = true)) {
            ConversationType.GROUP
        } else {
            ConversationType.DIRECT
        }
        val lastMessageObj = obj.optJSONObject("lastMessage")
        val lastPreview = lastMessageObj?.optString("content")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(ChatMessageFormatter::sanitizePreview)
            ?.takeIf { it.isNotBlank() }
        val lastTime = lastMessageObj?.optString("createdAt")?.takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" }
        val id = obj.getLong("id")
        val rawName = obj.optString("name").takeIf { it.isNotBlank() && it != "null" }
        val peerUserId = if (obj.has("peerUserId") && !obj.isNull("peerUserId")) {
            obj.optLong("peerUserId", 0L).takeIf { it > 0L }
        } else {
            null
        }
        val isOnline = obj.optBoolean("isOnline", false)
        val peerLastSeenAt = if (obj.has("peerLastSeenAt") && !obj.isNull("peerLastSeenAt")) {
            obj.optString("peerLastSeenAt").takeIf { it.isNotBlank() && it != "null" }
        } else {
            null
        }
        return ConversationUiModel(
            id = id,
            type = type,
            title = rawName ?: "Chat #$id",
            lastMessagePreview = lastPreview,
            lastMessageTime = lastTime,
            unreadCount = obj.optInt("unreadCount", 0),
            isPinned = obj.optBoolean("isPinned", false),
            isOnline = isOnline,
            peerUserId = peerUserId,
            peerLastSeenAt = peerLastSeenAt
        )
    }

    fun parseMessagesPage(rawJson: String, currentUserId: Long): CursorPageResult<MessageUiModel> {
        val root = JSONObject(rawJson)
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                add(parseMessageDto(itemsJson.getJSONObject(i), currentUserId))
            }
        }
        val nextCursor = root.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
        val hasMore = root.optBoolean("hasMore", false)
        return CursorPageResult(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    fun parseMessageDto(obj: JSONObject, currentUserId: Long): MessageUiModel {
        val serverId = obj.getLong("id")
        val clientMessageId = obj.optString("clientMessageId").ifBlank { serverId.toString() }
        val senderId = obj.getLong("senderId")
        val typeName = obj.optString("type", "TEXT")
        val type = MessageType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
            ?: MessageType.TEXT
        val deletedAt = obj.optString("deletedAt").takeIf { it.isNotBlank() && it != "null" }
        val editedAt = obj.optString("editedAt").takeIf { it.isNotBlank() && it != "null" }
        val replyToId = if (obj.has("replyToId") && !obj.isNull("replyToId")) obj.getLong("replyToId") else null
        val forwardedFromId = if (obj.has("forwardedFromId") && !obj.isNull("forwardedFromId")) obj.getLong("forwardedFromId") else null
        val mediaRef = if (obj.has("mediaRef") && !obj.isNull("mediaRef")) {
            obj.optLong("mediaRef", 0L).takeIf { it > 0L }
        } else {
            null
        }
        val metadataJson = ChatRealtimeParser.extractMetadataJson(obj)
        val (reactionCounts, myReactions) = parseReactions(obj.optJSONObject("reactions"), currentUserId)
        val deliveryStatusRaw = if (obj.has("deliveryStatus") && !obj.isNull("deliveryStatus")) {
            obj.optString("deliveryStatus").takeIf { it.isNotBlank() && it != "null" }
        } else {
            null
        }
        val deliveryStatus = deliveryStatusRaw?.let { raw ->
            MessageDeliveryStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: MessageDeliveryStatus.DELIVERED

        return MessageUiModel(
            localId = clientMessageId,
            serverId = serverId,
            clientMessageId = clientMessageId,
            conversationId = obj.getLong("conversationId"),
            senderId = senderId,
            isFromCurrentUser = currentUserId > 0L && senderId == currentUserId,
            type = type,
            content = obj.optString("content", ""),
            createdAt = obj.optString("createdAt", ""),
            deliveryStatus = deliveryStatus,
            replyToId = replyToId,
            forwardedFromId = forwardedFromId,
            isEdited = editedAt != null,
            isRecalled = deletedAt != null,
            reactionCounts = reactionCounts,
            mediaRef = mediaRef,
            metadataJson = metadataJson,
            myReactions = myReactions
        )
    }

    private fun parseReactions(
        reactionsObj: JSONObject?,
        currentUserId: Long
    ): Pair<Map<String, Int>, Set<String>> {
        if (reactionsObj == null) return emptyMap<String, Int>() to emptySet()
        val counts = linkedMapOf<String, Int>()
        val mine = linkedSetOf<String>()
        val keys = reactionsObj.keys()
        while (keys.hasNext()) {
            val emoji = keys.next()
            val userIds = reactionsObj.optJSONArray(emoji)
            if (userIds != null) {
                val count = userIds.length()
                if (count > 0) {
                    counts[emoji] = count
                    if (currentUserId > 0L) {
                        for (i in 0 until count) {
                            if (userIds.optLong(i) == currentUserId) {
                                mine += emoji
                                break
                            }
                        }
                    }
                }
            } else {
                val count = reactionsObj.optInt(emoji, 0)
                if (count > 0) {
                    counts[emoji] = count
                }
            }
        }
        return counts to mine
    }

    fun parseErrorMessage(rawJson: String?, fallback: String): String {
        if (rawJson.isNullOrBlank()) return fallback
        return runCatching {
            val root = JSONObject(rawJson)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    fun parseGroupMembers(rawJson: String): List<GroupMemberUiModel> {
        val array = JSONArray(rawJson)
        return buildList {
            for (i in 0 until array.length()) {
                add(parseGroupMember(array.getJSONObject(i)))
            }
        }
    }

    fun parseGroupMember(obj: JSONObject): GroupMemberUiModel {
        val userId = obj.getLong("userId")
        val roleName = obj.optString("role", "MEMBER")
        val role = GroupRole.entries.firstOrNull { it.name.equals(roleName, ignoreCase = true) }
            ?: GroupRole.MEMBER
        val displayName = obj.optString("displayName").takeIf { it.isNotBlank() && it != "null" }
            ?: "User #$userId"
        val avatarUrl = obj.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" }
        return GroupMemberUiModel(
            userId = userId,
            displayName = displayName,
            role = role,
            avatarUrl = avatarUrl
        )
    }
}

/**
 * OkHttp REST client for CHAT endpoints (`/conversations`, `/conversations/direct`, `/conversations/{id}/messages`).
 * Never logs Authorization headers or raw access tokens.
 */
class OkHttpChatApiService(
    private val client: OkHttpClient = OkHttpClient()
) : ChatApiService {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun listConversations(
        baseUrl: String,
        accessToken: String,
        cursor: String?,
        limit: Int
    ): Result<CursorPageResult<ConversationUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val url = buildString {
                append("$cleanBase/conversations?limit=${limit.coerceIn(1, 100)}")
                if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseConversationsPage(body)
        }
    }

    override suspend fun createOrGetDirectConversation(
        baseUrl: String,
        accessToken: String,
        targetUserId: Long
    ): Result<ConversationUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("targetUserId", targetUserId).toString()
            val request = Request.Builder()
                .url("$cleanBase/conversations/direct")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseConversationSummary(JSONObject(body))
        }
    }

    override suspend fun listMessages(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        currentUserId: Long,
        cursor: String?,
        limit: Int
    ): Result<CursorPageResult<MessageUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val url = buildString {
                append("$cleanBase/conversations/$conversationId/messages?limit=${limit.coerceIn(1, 100)}")
                if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseMessagesPage(body, currentUserId)
        }
    }

    override suspend fun createGroupConversation(
        baseUrl: String,
        accessToken: String,
        name: String,
        memberIds: List<Long>
    ): Result<ConversationUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject()
                .put("name", name)
                .put("memberIds", JSONArray(memberIds))
                .toString()
            val request = Request.Builder()
                .url("$cleanBase/conversations/group")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseConversationSummary(JSONObject(body))
        }
    }

    override suspend fun listGroupMembers(
        baseUrl: String,
        accessToken: String,
        conversationId: Long
    ): Result<List<GroupMemberUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/members")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseGroupMembers(body)
        }
    }

    override suspend fun addGroupMembers(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        userIds: List<Long>
    ): Result<List<GroupMemberUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("userIds", JSONArray(userIds)).toString()
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/members")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseGroupMembers(body)
        }
    }

    override suspend fun updateGroupMemberRole(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long,
        role: GroupRole
    ): Result<GroupMemberUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("role", role.name).toString()
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/members/$targetUserId")
                .header("Authorization", "Bearer $accessToken")
                .patch(payload.toRequestBody(jsonMediaType))
                .build()
            val body = executeRequest(request)
            ChatRestParser.parseGroupMember(JSONObject(body))
        }
    }

    override suspend fun removeGroupMember(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/members/$targetUserId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
            executeRequest(request)
            Unit
        }
    }

    override suspend fun editMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        currentUserId: Long,
        content: String
    ): Result<MessageUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("content", content).toString()
            val request = Request.Builder()
                .url("$cleanBase/messages/$messageId")
                .header("Authorization", "Bearer $accessToken")
                .patch(payload.toRequestBody(jsonMediaType))
                .build()
            ChatRestParser.parseMessageDto(JSONObject(executeRequest(request)), currentUserId)
        }
    }

    override suspend fun recallMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        currentUserId: Long
    ): Result<MessageUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/messages/$messageId/recall")
                .header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            ChatRestParser.parseMessageDto(JSONObject(executeRequest(request)), currentUserId)
        }
    }

    override suspend fun forwardMessage(
        baseUrl: String,
        accessToken: String,
        sourceMessageId: Long,
        targetConversationId: Long,
        clientMessageId: String,
        currentUserId: Long
    ): Result<MessageUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject()
                .put("targetConversationId", targetConversationId)
                .put("clientMessageId", clientMessageId)
                .toString()
            val request = Request.Builder()
                .url("$cleanBase/messages/$sourceMessageId/forward")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            ChatRestParser.parseMessageDto(JSONObject(executeRequest(request)), currentUserId)
        }
    }

    override suspend fun toggleReaction(
        baseUrl: String,
        accessToken: String,
        messageId: Long,
        emoji: String
    ): Result<Map<String, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("emoji", emoji).toString()
            val request = Request.Builder()
                .url("$cleanBase/messages/$messageId/reactions")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val root = JSONObject(executeRequest(request))
            val countsObj = root.optJSONObject("counts") ?: JSONObject()
            buildMap {
                val keys = countsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, countsObj.optInt(k, 0))
                }
            }
        }
    }

    override suspend fun searchMessages(
        baseUrl: String,
        accessToken: String,
        conversationId: Long?,
        query: String,
        currentUserId: Long,
        cursor: String?,
        limit: Int
    ): Result<CursorPageResult<MessageUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = buildString {
                append("$cleanBase/messages/search?q=$encodedQuery&limit=${limit.coerceIn(1, 100)}")
                if (conversationId != null) append("&conversationId=$conversationId")
                if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            ChatRestParser.parseMessagesPage(executeRequest(request), currentUserId)
        }
    }

    override suspend fun setConversationPinned(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        pinned: Boolean
    ): Result<ConversationUiModel> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().put("pinned", pinned).toString()
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/pin")
                .header("Authorization", "Bearer $accessToken")
                .patch(payload.toRequestBody(jsonMediaType))
                .build()
            ChatRestParser.parseConversationSummary(JSONObject(executeRequest(request)))
        }
    }

    override suspend fun listPinnedMessageIds(
        baseUrl: String,
        accessToken: String,
        conversationId: Long
    ): Result<List<Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/pinned-messages")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val arr = JSONArray(executeRequest(request))
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getJSONObject(i).getLong("messageId"))
                }
            }
        }
    }

    override suspend fun pinMessage(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/pinned-messages/$messageId")
                .header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            executeRequest(request)
            Unit
        }
    }

    override suspend fun unpinMessage(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/conversations/$conversationId/pinned-messages/$messageId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
            executeRequest(request)
            Unit
        }
    }

    override suspend fun deleteMessage(
        baseUrl: String,
        accessToken: String,
        messageId: Long
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$cleanBase/messages/$messageId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
            executeRequest(request)
            true
        }
    }

    private fun executeRequest(request: Request): String {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMessage = ChatRestParser.parseErrorMessage(
                    rawJson = bodyText,
                    fallback = "HTTP ${response.code}"
                )
                throw IOException(errorMessage)
            }
            return bodyText
        }
    }
}
