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
        timestamp: String = Instant.now().toString(),
        mediaRef: Long? = null,
        metadataJson: String? = null
    ): String {
        val payload = JSONObject()
            .put("clientMessageId", clientMessageId)
            .put("conversationId", conversationId)
            .put("type", type.name)
            .put("content", content)
        if (replyToId != null) {
            payload.put("replyToId", replyToId)
        }
        if (mediaRef != null) {
            payload.put("mediaRef", mediaRef)
        }
        if (!metadataJson.isNullOrBlank()) {
            val metaObj = runCatching { JSONObject(metadataJson) }.getOrNull()
            if (metaObj != null) {
                payload.put("metadata", metaObj)
            }
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

    fun encodeCallSignal(
        conversationId: Long,
        callId: String,
        signalType: String,
        targetUserId: Long? = null,
        sdpOrCandidate: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        timestamp: String = Instant.now().toString()
    ): String {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("callId", callId)
            .put("signalType", signalType)
        if (targetUserId != null) payload.put("targetUserId", targetUserId)
        if (sdpOrCandidate != null) payload.put("sdpOrCandidate", sdpOrCandidate)
        return wrapEnvelope("call.signal", requestId, timestamp, payload)
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
                    replyToId = payload.optNullableLong("replyToId"),
                    mediaRef = payload.optNullableLong("mediaRef"),
                    forwardedFromId = payload.optNullableLong("forwardedFromId"),
                    metadataJson = extractMetadataJson(payload)
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

                "call.signal" -> ChatServerEvent.CallSignal(
                    conversationId = payload.getLong("conversationId"),
                    callId = payload.getString("callId"),
                    signalType = payload.getString("signalType"),
                    fromUserId = payload.optLong("fromUserId", 0L),
                    targetUserId = payload.optNullableLong("targetUserId"),
                    sdpOrCandidate = payload.optString("sdpOrCandidate").takeIf { it.isNotBlank() && it != "null" }
                )

                "message.pinned" -> {
                    val idsArray = payload.optJSONArray("pinnedMessageIds")
                    val pinnedIds = buildList {
                        if (idsArray != null) {
                            for (i in 0 until idsArray.length()) {
                                val id = idsArray.optLong(i, 0L)
                                if (id > 0L) add(id)
                            }
                        }
                    }
                    ChatServerEvent.MessagePinned(
                        conversationId = payload.getLong("conversationId"),
                        messageId = payload.optLong("messageId", 0L),
                        pinned = payload.optBoolean("pinned", false),
                        pinnedMessageIds = pinnedIds
                    )
                }

                "message.deleted" -> ChatServerEvent.MessageDeleted(
                    conversationId = payload.getLong("conversationId"),
                    messageId = payload.getLong("messageId"),
                    userId = payload.optLong("userId", 0L)
                )

                "group.members_changed" -> ChatServerEvent.GroupMembersChanged(
                    conversationId = payload.getLong("conversationId")
                )

                else -> null
            }
        }.getOrNull()
    }

    internal fun extractMetadataJson(obj: JSONObject): String? {
        if (!obj.has("metadata") || obj.isNull("metadata")) return null
        val metaObj = obj.optJSONObject("metadata")
        if (metaObj != null) {
            return if (metaObj.length() > 0) metaObj.toString() else null
        }
        return obj.optString("metadata").takeIf {
            it.isNotBlank() && it != "null" && it != "{}"
        }
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key, 0L).takeIf { it > 0L } else null

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
