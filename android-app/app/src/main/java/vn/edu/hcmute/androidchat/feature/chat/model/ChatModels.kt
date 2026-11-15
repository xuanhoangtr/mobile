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
    val isOnline: Boolean = false,
    val peerUserId: Long? = null,
    val peerLastSeenAt: String? = null
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
    val forwardedFromId: Long? = null,
    val isEdited: Boolean = false,
    val isRecalled: Boolean = false,
    val reactionCounts: Map<String, Int> = emptyMap(),
    val mediaRef: Long? = null,
    val metadataJson: String? = null,
    val myReactions: Set<String> = emptySet()
)

data class ChatListUiState(
    val isLoading: Boolean = false,
    val conversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val connectionState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val searchMessageResults: List<MessageUiModel> = emptyList()
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
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val nextHistoryCursor: String? = null,
    val messages: List<MessageUiModel> = emptyList(),
    val draftText: String = "",
    val replyingToMessage: MessageUiModel? = null,
    val editingMessage: MessageUiModel? = null,
    val searchQuery: String = "",
    val searchResults: List<MessageUiModel> = emptyList(),
    val pinnedMessageIds: List<Long> = emptyList(),
    val firstVisibleMessageIndex: Int = 0,
    val firstVisibleMessageOffset: Int = 0,
    val typingUserNames: List<String> = emptyList(),
    val peerUserId: Long? = null,
    val isPeerOnline: Boolean = false,
    val peerLastSeenAt: String? = null,
    val connectionState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val memberNamesByUserId: Map<Long, String> = emptyMap()
)

object ChatMessageFormatter {
    private val MEDIA_TAG_REGEX = Regex("""\s*\[media:\d+\]""")
    private val HASH_MEDIA_TAG_REGEX = Regex("""\s*#\d+""")
    private val VOICE_LEGACY_MMSS_REGEX = Regex("""^(Tin nhắn thoại\s*)\(0:(\d{2})\)(.*)$""")

    /** Formats duration in seconds (e.g. "2s") under one minute, or "m:ss" for 60s+. */
    fun formatDurationShort(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        return if (totalSeconds < 60L) {
            "${totalSeconds}s"
        } else {
            "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }
    }

    /** Strips internal media reference markers and normalizes legacy voice "0:XX" durations. */
    fun sanitizePreview(content: String?): String {
        val cleaned = content
            .orEmpty()
            .replace(MEDIA_TAG_REGEX, "")
            .trim()
        val withoutVoiceHash = if (cleaned.startsWith("Tin nhắn thoại")) {
            cleaned.replace(HASH_MEDIA_TAG_REGEX, "").trim()
        } else {
            cleaned
        }
        val legacyMatch = VOICE_LEGACY_MMSS_REGEX.matchEntire(withoutVoiceHash)
        return if (legacyMatch != null) {
            val seconds = legacyMatch.groupValues[2].toLongOrNull() ?: 0L
            "${legacyMatch.groupValues[1]}(${seconds}s)${legacyMatch.groupValues[3]}".trim()
        } else {
            withoutVoiceHash
        }
    }
}
