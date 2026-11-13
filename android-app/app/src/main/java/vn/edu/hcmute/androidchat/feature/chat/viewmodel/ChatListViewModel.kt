package vn.edu.hcmute.androidchat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRealtimeTransport
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.model.ChatListUiState
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState

/**
 * Holds chat list UI state across configuration changes (e.g. screen rotation)
 * and synchronizes connection status + incoming message previews from [ChatRealtimeTransport].
 */
class ChatListViewModel(
    private val transport: ChatRealtimeTransport? = null
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
            }

            is ChatServerEvent.Error -> setError(event.message)
            else -> Unit
        }
    }
}
