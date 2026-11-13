package vn.edu.hcmute.androidchat.feature.chat.data

import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import java.time.Instant
import java.util.UUID

/**
 * Encodes and decodes `/ws/chat` JSON envelopes matching `docs/api-contract.md`.
 * Uses `org.json.JSONObject` (built into Android SDK and testable with `org.json:json`).
 */
object ChatRealtimeParser {
    fun encodeMessageSend(
        clientMessageId: String,
        conversationId: Long,
        content: String,
        type: MessageType = MessageType.TEXT,
        replyToId: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
        timestamp: String = Instant.now().toString()
    ): String {
        val payload = JSONObject()
            .put("clientMessageId", clientMessageId)
            .put("conversationId", conversationId)
            .put("type", type.name)
            .put("content", content)
        if (replyToId != null) {
            payload.put("replyToId", replyToId)
        }
        return wrapEnvelope("message.send", requestId, timestamp, payload)
    }

    fun encodeTyping(
        conversationId: Long,
        isTyping: Boolean,
        expiresInMs: Long = 5_000L,
        requestId: String = UUID.randomUUID().toString(),
        timestamp: String = Instant.now().toString()
    ): String {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("isTyping", isTyping)
            .put("expiresInMs", expiresInMs)
        return wrapEnvelope("typing", requestId, timestamp, payload)
    }

    fun encodeReceipt(
        conversationId: Long,
        messageId: Long,
        status: MessageDeliveryStatus,
        requestId: String = UUID.randomUUID().toString(),
        timestamp: String = Instant.now().toString()
    ): String {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("messageId", messageId)
            .put("status", status.name)
        return wrapEnvelope("receipt", requestId, timestamp, payload)
    }

    fun decodeEvent(rawJson: String): ChatServerEvent? {
        return runCatching {
            val root = JSONObject(rawJson)
            val type = root.optString("type", "")
            val requestId = root.optString("requestId").takeIf { it.isNotBlank() && it != "null" }
            val payload = root.optJSONObject("payload") ?: return@runCatching null

            when (type) {
                "message.ack" -> ChatServerEvent.MessageAck(
                    clientMessageId = payload.getString("clientMessageId"),
                    conversationId = payload.getLong("conversationId"),
                    messageId = payload.getLong("messageId"),
                    status = payload.optString("status", "ACCEPTED"),
                    serverCreatedAt = payload.optString("serverCreatedAt", "")
                )

                "message.created" -> ChatServerEvent.MessageCreated(
                    id = payload.getLong("id"),
                    clientMessageId = payload.getString("clientMessageId"),
                    conversationId = payload.getLong("conversationId"),
                    senderId = payload.getLong("senderId"),
                    type = parseMessageType(payload.optString("type", "TEXT")),
                    content = payload.optString("content", ""),
                    createdAt = payload.optString("createdAt", ""),
                    replyToId = if (payload.has("replyToId") && !payload.isNull("replyToId")) {
                        payload.getLong("replyToId")
                    } else {
                        null
                    }
                )

                "typing" -> ChatServerEvent.Typing(
                    conversationId = payload.getLong("conversationId"),
                    userId = payload.optLong("userId", 0L),
                    isTyping = payload.optBoolean("isTyping", false),
                    expiresInMs = payload.optLong("expiresInMs", 5_000L)
                )

                "presence" -> ChatServerEvent.Presence(
                    userId = payload.getLong("userId"),
                    status = payload.optString("status", "OFFLINE"),
                    lastSeenAt = payload.optString("lastSeenAt", "")
                )

                "receipt" -> ChatServerEvent.Receipt(
                    conversationId = payload.getLong("conversationId"),
                    messageId = payload.getLong("messageId"),
                    userId = payload.optLong("userId", 0L),
                    status = parseDeliveryStatus(payload.optString("status", "DELIVERED")),
                    at = payload.optString("at", "")
                )

                "error" -> ChatServerEvent.Error(
                    requestId = requestId,
                    code = payload.optString("code", "UNKNOWN_ERROR"),
                    message = payload.optString("message", "WebSocket error")
                )

                "message.updated" -> ChatServerEvent.MessageUpdated(
                    conversationId = payload.getLong("conversationId"),
                    messageId = payload.getLong("messageId"),
                    content = payload.optString("content", ""),
                    editedAt = payload.optString("editedAt", "")
                )

                "message.recalled" -> ChatServerEvent.MessageRecalled(
                    conversationId = payload.getLong("conversationId"),
                    messageId = payload.getLong("messageId"),
                    deletedAt = payload.optString("deletedAt", "")
                )

                "message.reaction" -> {
                    val countsObj = payload.optJSONObject("counts") ?: JSONObject()
                    val counts = buildMap {
                        val keys = countsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            put(k, countsObj.optInt(k, 0))
                        }
                    }
                    ChatServerEvent.ReactionUpdated(
                        conversationId = payload.getLong("conversationId"),
                        messageId = payload.getLong("messageId"),
                        userId = payload.optLong("userId", 0L),
                        emoji = payload.optString("emoji", ""),
                        added = payload.optBoolean("added", false),
                        counts = counts
                    )
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun wrapEnvelope(
        type: String,
        requestId: String,
        timestamp: String,
        payload: JSONObject
    ): String {
        return JSONObject()
            .put("type", type)
            .put("requestId", requestId)
            .put("timestamp", timestamp)
            .put("payload", payload)
            .toString()
    }

    private fun parseMessageType(raw: String): MessageType {
        return MessageType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: MessageType.TEXT
    }

    private fun parseDeliveryStatus(raw: String): MessageDeliveryStatus {
        return MessageDeliveryStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: MessageDeliveryStatus.DELIVERED
    }
}
