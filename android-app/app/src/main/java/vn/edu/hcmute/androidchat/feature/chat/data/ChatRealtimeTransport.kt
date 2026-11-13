package vn.edu.hcmute.androidchat.feature.chat.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState

sealed interface ChatServerEvent {
    data class MessageAck(
        val clientMessageId: String,
        val conversationId: Long,
        val messageId: Long,
        val status: String,
        val serverCreatedAt: String
    ) : ChatServerEvent

    data class MessageCreated(
        val id: Long,
        val clientMessageId: String,
        val conversationId: Long,
        val senderId: Long,
        val type: MessageType,
        val content: String,
        val createdAt: String,
        val replyToId: Long? = null
    ) : ChatServerEvent

    data class Typing(
        val conversationId: Long,
        val userId: Long,
        val isTyping: Boolean,
        val expiresInMs: Long
    ) : ChatServerEvent

    data class Presence(
        val userId: Long,
        val status: String,
        val lastSeenAt: String
    ) : ChatServerEvent

    data class Receipt(
        val conversationId: Long,
        val messageId: Long,
        val userId: Long,
        val status: MessageDeliveryStatus,
        val at: String
    ) : ChatServerEvent

    data class Error(
        val requestId: String?,
        val code: String,
        val message: String
    ) : ChatServerEvent

    data class MessageUpdated(
        val conversationId: Long,
        val messageId: Long,
        val content: String,
        val editedAt: String
    ) : ChatServerEvent

    data class MessageRecalled(
        val conversationId: Long,
        val messageId: Long,
        val deletedAt: String
    ) : ChatServerEvent

    data class ReactionUpdated(
        val conversationId: Long,
        val messageId: Long,
        val userId: Long,
        val emoji: String,
        val added: Boolean,
        val counts: Map<String, Int>
    ) : ChatServerEvent
}

interface ChatRealtimeTransport {
    val connectionState: StateFlow<WsConnectionState>
    val events: SharedFlow<ChatServerEvent>

    fun connect(wsUrl: String, accessToken: String)
    fun disconnect()

    fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        content: String,
        type: MessageType = MessageType.TEXT,
        replyToId: Long? = null
    ): Boolean

    fun sendTyping(conversationId: Long, isTyping: Boolean): Boolean

    fun sendReceipt(
        conversationId: Long,
        messageId: Long,
        status: MessageDeliveryStatus
    ): Boolean
}
