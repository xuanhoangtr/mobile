package vn.edu.hcmute.androidchat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.chat.data.ChatApiService
import vn.edu.hcmute.androidchat.feature.chat.data.ChatLocalCache
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRealtimeTransport
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.model.ChatListUiState
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState

/**
 * Holds chat list UI state across configuration changes (e.g. screen rotation)
 * and synchronizes REST conversations, local cache, presence, and WebSocket updates.
 */
class ChatListViewModel(
    private val transport: ChatRealtimeTransport? = null,
    private val apiService: ChatApiService? = null,
    private val localCache: ChatLocalCache? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private var activeCurrentUserId: Long = 0L
    private var activeConversationId: Long? = null
    private val peerPresenceByUserId = mutableMapOf<Long, Pair<Boolean, String?>>()
    private val sentDeliveredReceiptIds = mutableSetOf<Long>()

    init {
        if (transport != null) {
            viewModelScope.launch {
                transport.connectionState.collect { state ->
                    onConnectionStateChanged(state)
                }
            }
            viewModelScope.launch {
                transport.events.collect { event ->
                    handleServerEvent(event)
                }
            }
        }
    }

    fun setCurrentUserId(userId: Long) {
        if (userId > 0L) {
            activeCurrentUserId = userId
        }
    }

    fun setActiveConversation(conversationId: Long?) {
        activeConversationId = conversationId
    }

    fun presenceForPeer(peerUserId: Long): Pair<Boolean, String?>? {
        if (peerUserId <= 0L) return null
        return peerPresenceByUserId[peerUserId]
            ?: _uiState.value.conversations
                .firstOrNull { it.type == ConversationType.DIRECT && it.peerUserId == peerUserId }
                ?.let { it.isOnline to it.peerLastSeenAt }
    }

    fun markConversationRead(conversationId: Long) {
        if (conversationId <= 0L) return
        var changed = false
        _uiState.update { state ->
            val updated = state.conversations.map { item ->
                if (item.id == conversationId && item.unreadCount > 0) {
                    changed = true
                    item.copy(unreadCount = 0)
                } else {
                    item
                }
            }
            if (changed) state.copy(conversations = updated) else state
        }
        if (changed) {
            viewModelScope.launch {
                runCatching { localCache?.saveConversations(_uiState.value.conversations) }
            }
        }
    }

    fun loadConversations(baseUrl: String, accessToken: String) {
        viewModelScope.launch {
            val cached = runCatching { localCache?.getConversations().orEmpty() }.getOrDefault(emptyList())
            if (cached.isNotEmpty() && _uiState.value.conversations.isEmpty()) {
                _uiState.update {
                    it.copy(
                        conversations = applyCachedPresence(cached),
                        isLoading = apiService != null
                    )
                }
            } else {
                setLoading(apiService != null)
            }

            val remote = apiService?.listConversations(baseUrl = baseUrl, accessToken = accessToken)
                ?: run {
                    setLoading(false)
                    return@launch
                }

            remote.onSuccess { page ->
                setConversations(page.items)
                runCatching { localCache?.saveConversations(_uiState.value.conversations) }
            }.onFailure { err ->
                setError(err.message ?: "Failed to load conversations")
            }
        }
    }

    fun openDirectConversation(
        baseUrl: String,
        accessToken: String,
        targetUserId: Long,
        onReady: (ConversationUiModel) -> Unit
    ) {
        val service = apiService ?: return
        viewModelScope.launch {
            setLoading(true)
            service.createOrGetDirectConversation(
                baseUrl = baseUrl,
                accessToken = accessToken,
                targetUserId = targetUserId
            ).onSuccess { conversation ->
                val resolvedConversation = applyCachedPresence(listOf(conversation)).single()
                _uiState.update { state ->
                    val existing = state.conversations.any { it.id == resolvedConversation.id }
                    val updated = if (existing) {
                        state.conversations.map { if (it.id == resolvedConversation.id) resolvedConversation else it }
                    } else {
                        listOf(resolvedConversation) + state.conversations
                    }
                    state.copy(isLoading = false, conversations = updated, errorMessage = null)
                }
                runCatching { localCache?.saveConversations(_uiState.value.conversations) }
                onReady(resolvedConversation)
            }.onFailure { err ->
                setError(err.message ?: "Failed to open direct conversation")
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onConnectionStateChanged(state: WsConnectionState) {
        _uiState.update { it.copy(connectionState = state) }
    }

    fun setConversations(conversations: List<ConversationUiModel>) {
        _uiState.update {
            it.copy(
                isLoading = false,
                conversations = applyCachedPresence(conversations),
                errorMessage = null
            )
        }
    }

    fun setLoading(loading: Boolean) {
        _uiState.update { it.copy(isLoading = loading) }
    }

    fun setError(message: String?) {
        _uiState.update { it.copy(isLoading = false, errorMessage = message) }
    }

    fun togglePinLocal(conversationId: Long) {
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { item ->
                    if (item.id == conversationId) item.copy(isPinned = !item.isPinned) else item
                }
            )
        }
    }

    fun togglePinConversation(baseUrl: String, accessToken: String, conversationId: Long) {
        val currentPinned = _uiState.value.conversations.firstOrNull { it.id == conversationId }?.isPinned ?: return
        val nextPinned = !currentPinned
        togglePinLocal(conversationId)
        viewModelScope.launch {
            runCatching { localCache?.saveConversations(_uiState.value.conversations) }
            apiService?.setConversationPinned(baseUrl, accessToken, conversationId, nextPinned)
                ?.onFailure { err ->
                    // Revert optimistic pin if server rejects
                    togglePinLocal(conversationId)
                    runCatching { localCache?.saveConversations(_uiState.value.conversations) }
                    setError(err.message ?: "Failed to update pin")
                }
        }
    }

    private fun applyCachedPresence(conversations: List<ConversationUiModel>): List<ConversationUiModel> {
        if (peerPresenceByUserId.isEmpty()) return conversations
        return conversations.map { conv ->
            val peerId = conv.peerUserId
            val cached = if (conv.type == ConversationType.DIRECT && peerId != null) {
                peerPresenceByUserId[peerId]
            } else {
                null
            }
            if (cached != null) {
                conv.copy(
                    isOnline = cached.first,
                    peerLastSeenAt = cached.second ?: conv.peerLastSeenAt
                )
            } else {
                conv
            }
        }
    }

    private fun handleServerEvent(event: ChatServerEvent) {
        when (event) {
            is ChatServerEvent.MessageCreated -> {
                val isIncoming = activeCurrentUserId == 0L || event.senderId != activeCurrentUserId
                val isInActiveRoom = event.conversationId == activeConversationId
                val shouldIncrementUnread = isIncoming && !isInActiveRoom
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { item ->
                            if (item.id == event.conversationId) {
                                item.copy(
                                    lastMessagePreview = event.content,
                                    lastMessageTime = event.createdAt,
                                    unreadCount = if (shouldIncrementUnread) item.unreadCount + 1 else item.unreadCount
                                )
                            } else {
                                item
                            }
                        }
                    )
                }
                if (isIncoming &&
                    !isInActiveRoom &&
                    event.id > 0L &&
                    event.id !in sentDeliveredReceiptIds
                ) {
                    val sent = transport?.sendReceipt(
                        conversationId = event.conversationId,
                        messageId = event.id,
                        status = MessageDeliveryStatus.DELIVERED
                    ) == true
                    if (sent) {
                        sentDeliveredReceiptIds += event.id
                    }
                }
                viewModelScope.launch {
                    runCatching { localCache?.saveConversations(_uiState.value.conversations) }
                }
            }

            is ChatServerEvent.Presence -> {
                if (event.userId <= 0L || (activeCurrentUserId > 0L && event.userId == activeCurrentUserId)) return
                val isOnline = event.status.equals("ONLINE", ignoreCase = true)
                val previousLastSeen = peerPresenceByUserId[event.userId]?.second
                val normalizedLastSeen = event.lastSeenAt.takeIf { it.isNotBlank() } ?: previousLastSeen
                peerPresenceByUserId[event.userId] = isOnline to normalizedLastSeen
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { item ->
                            if (item.type == ConversationType.DIRECT && item.peerUserId == event.userId) {
                                item.copy(
                                    isOnline = isOnline,
                                    peerLastSeenAt = normalizedLastSeen ?: item.peerLastSeenAt
                                )
                            } else {
                                item
                            }
                        }
                    )
                }
            }

            is ChatServerEvent.Error -> setError(event.message)
            else -> Unit
        }
    }
}
