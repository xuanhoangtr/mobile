package vn.edu.hcmute.androidchat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.edu.hcmute.androidchat.feature.chat.model.ChatListUiState
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState

/**
 * Holds chat list UI state across configuration changes (e.g. screen rotation).
 * Data loading and WebSocket synchronization will be wired via ChatRepository in D04-D09.
 */
class ChatListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

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
}
