package vn.edu.hcmute.androidchat.feature.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
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
        val lastPreview = lastMessageObj?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
        val lastTime = lastMessageObj?.optString("createdAt")?.takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" }
        val id = obj.getLong("id")
        val rawName = obj.optString("name").takeIf { it.isNotBlank() && it != "null" }
        return ConversationUiModel(
            id = id,
            type = type,
            title = rawName ?: "Chat #$id",
            lastMessagePreview = lastPreview,
            lastMessageTime = lastTime,
            unreadCount = obj.optInt("unreadCount", 0),
            isPinned = obj.optBoolean("isPinned", false)
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
            deliveryStatus = MessageDeliveryStatus.DELIVERED,
            replyToId = replyToId,
            isEdited = editedAt != null,
            isRecalled = deletedAt != null
        )
    }

    fun parseErrorMessage(rawJson: String?, fallback: String): String {
        if (rawJson.isNullOrBlank()) return fallback
        return runCatching {
            val root = JSONObject(rawJson)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
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
