package vn.edu.hcmute.androidchat.backend.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class CreateDirectConversationRequest(
    val targetUserId: Long
)

@Serializable
data class CreateGroupConversationRequest(
    val name: String,
    val memberIds: List<Long>
)

@Serializable
data class AddGroupMembersRequest(
    val userIds: List<Long>
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String
)

@Serializable
data class ConversationMemberDto(
    val conversationId: Long,
    val userId: Long,
    val role: String,
    val status: String,
    val joinedAt: String
)

@Serializable
data class ConversationSummaryDto(
    val id: Long,
    val type: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessage: MessageDto? = null,
    val updatedAt: String
)

@Serializable
data class MessageDto(
    val id: Long,
    val clientMessageId: String,
    val conversationId: Long,
    val senderId: Long,
    val type: String,
    val content: String? = null,
    val mediaRef: Long? = null,
    val replyToId: Long? = null,
    val forwardedFromId: Long? = null,
    val metadata: JsonObject = buildJsonObject {},
    val createdAt: String,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val reactions: Map<String, List<Long>> = emptyMap()
)

@Serializable
data class MessageSendPayload(
    val clientMessageId: String,
    val conversationId: Long,
    val type: String = "TEXT",
    val content: String? = null,
    val mediaRef: Long? = null,
    val replyToId: Long? = null,
    val forwardedFromId: Long? = null,
    val metadata: JsonObject = buildJsonObject {}
)

@Serializable
data class EditMessageRequest(
    val content: String
)

@Serializable
data class ForwardMessageRequest(
    val targetConversationId: Long,
    val clientMessageId: String
)

@Serializable
data class ToggleReactionRequest(
    val emoji: String
)

@Serializable
data class MessageUpdatedPayload(
    val conversationId: Long,
    val messageId: Long,
    val content: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null
)

@Serializable
data class ReactionUpdatedPayload(
    val conversationId: Long,
    val messageId: Long,
    val userId: Long,
    val emoji: String,
    val added: Boolean,
    val counts: Map<String, Int>
)

@Serializable
data class MessageAckPayload(
    val clientMessageId: String,
    val conversationId: Long,
    val messageId: Long,
    val status: String,
    val serverCreatedAt: String
)

@Serializable
data class TypingPayload(
    val conversationId: Long,
    val userId: Long? = null,
    val isTyping: Boolean,
    val expiresInMs: Long = 5_000L
)

@Serializable
data class PresencePayload(
    val userId: Long,
    val status: String,
    val lastSeenAt: String
)

@Serializable
data class ReceiptPayload(
    val conversationId: Long,
    val messageId: Long,
    val userId: Long? = null,
    val status: String,
    val at: String? = null
)
