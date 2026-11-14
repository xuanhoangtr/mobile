package vn.edu.hcmute.androidchat.feature.chat.model

enum class ConversationType {
    DIRECT,
    GROUP
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE,
    STICKER,
    LOCATION,
    SYSTEM
}

enum class MessageDeliveryStatus {
    SENDING,
    ACCEPTED,
    DELIVERED,
    READ,
    FAILED
}

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class ConversationUiModel(
    val id: Long,
    val type: ConversationType,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageTime: String?,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isOnline: Boolean = false
)

data class MessageUiModel(
    val localId: String,
    val serverId: Long? = null,
    val clientMessageId: String,
    val conversationId: Long,
    val senderId: Long,
    val isFromCurrentUser: Boolean,
    val type: MessageType = MessageType.TEXT,
    val content: String,
    val createdAt: String,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENDING,
    val replyToId: Long? = null,
    val isEdited: Boolean = false,
    val isRecalled: Boolean = false
)

data class ChatListUiState(
    val isLoading: Boolean = false,
    val conversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val connectionState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val errorMessage: String? = null
) {
    val visibleConversations: List<ConversationUiModel>
        get() {
            val query = searchQuery.trim()
            val filtered = if (query.isEmpty()) {
                conversations
            } else {
                conversations.filter { it.title.contains(query, ignoreCase = true) }
            }
            return filtered.sortedByDescending { it.isPinned }
        }
}

data class ChatRoomUiState(
    val conversationId: Long = 0L,
    val conversationTitle: String = "",
    val isGroup: Boolean = false,
    val isLoading: Boolean = false,
    val messages: List<MessageUiModel> = emptyList(),
    val draftText: String = "",
    val typingUserNames: List<String> = emptyList(),
    val connectionState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val errorMessage: String? = null
)
