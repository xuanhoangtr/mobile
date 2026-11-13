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
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState

/**
 * Holds chat list UI state across configuration changes (e.g. screen rotation)
 * and synchronizes REST conversations, local cache, and WebSocket updates.
 */
class ChatListViewModel(
    private val transport: ChatRealtimeTransport? = null,
    private val apiService: ChatApiService? = null,
    private val localCache: ChatLocalCache? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

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

    fun loadConversations(baseUrl: String, accessToken: String) {
        viewModelScope.launch {
            val cached = runCatching { localCache?.getConversations().orEmpty() }.getOrDefault(emptyList())
            if (cached.isNotEmpty() && _uiState.value.conversations.isEmpty()) {
                _uiState.update { it.copy(conversations = cached, isLoading = apiService != null) }
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
                runCatching { localCache?.saveConversations(page.items) }
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
                _uiState.update { state ->
                    val existing = state.conversations.any { it.id == conversation.id }
                    val updated = if (existing) {
                        state.conversations.map { if (it.id == conversation.id) conversation else it }
                    } else {
                        listOf(conversation) + state.conversations
                    }
                    state.copy(isLoading = false, conversations = updated, errorMessage = null)
                }
                runCatching { localCache?.saveConversations(_uiState.value.conversations) }
                onReady(conversation)
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
        _uiState.update { it.copy(isLoading = false, conversations = conversations, errorMessage = null) }
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

    private fun handleServerEvent(event: ChatServerEvent) {
        when (event) {
            is ChatServerEvent.MessageCreated -> {
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { item ->
                            if (item.id == event.conversationId) {
                                item.copy(
                                    lastMessagePreview = event.content,
                                    lastMessageTime = event.createdAt,
                                    unreadCount = item.unreadCount + 1
                                )
                            } else {
                                item
                            }
                        }
                    )
                }
                viewModelScope.launch {
                    runCatching { localCache?.saveConversations(_uiState.value.conversations) }
                }
            }

            is ChatServerEvent.Error -> setError(event.message)
            else -> Unit
        }
    }
}
