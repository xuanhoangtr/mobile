package vn.edu.hcmute.androidchat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.edu.hcmute.androidchat.feature.chat.model.ChatRoomUiState
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import java.util.UUID

/**
 * Holds active conversation state (including message draft) across screen rotation.
 * Local outgoing messages stay in SENDING/FAILED until real WebSocket message.ack arrives (D07).
 */
class ChatRoomViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatRoomUiState())
    val uiState: StateFlow<ChatRoomUiState> = _uiState.asStateFlow()

    fun openConversation(conversationId: Long, title: String, isGroup: Boolean = false) {
        _uiState.update { state ->
            if (state.conversationId == conversationId) {
                state.copy(conversationTitle = title, isGroup = isGroup)
            } else {
                ChatRoomUiState(
                    conversationId = conversationId,
                    conversationTitle = title,
                    isGroup = isGroup,
                    connectionState = state.connectionState
                )
            }
        }
    }

    fun onDraftChanged(newText: String) {
        _uiState.update { it.copy(draftText = newText) }
    }

    fun onConnectionStateChanged(state: WsConnectionState) {
        _uiState.update { it.copy(connectionState = state) }
    }

    /**
     * Enqueues a pending local message with a unique clientMessageId for idempotent send.
     * In skeleton mode (without active repository), marks status as FAILED so UI does not fake delivery.
     */
    fun queueOutgoingTextMessage(
        currentUserId: Long,
        createdAtLabel: String,
        isTransportConnected: Boolean = false
    ): MessageUiModel? {
        val currentState = _uiState.value
        val trimmed = currentState.draftText.trim()
        if (trimmed.isEmpty() || currentState.conversationId <= 0L) return null

        val clientMsgId = UUID.randomUUID().toString()
        val pendingMessage = MessageUiModel(
            localId = clientMsgId,
            clientMessageId = clientMsgId,
            conversationId = currentState.conversationId,
            senderId = currentUserId,
            isFromCurrentUser = true,
            type = MessageType.TEXT,
            content = trimmed,
            createdAt = createdAtLabel,
            deliveryStatus = if (isTransportConnected) {
                MessageDeliveryStatus.SENDING
            } else {
                MessageDeliveryStatus.FAILED
            }
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + pendingMessage,
                draftText = ""
            )
        }
        return pendingMessage
    }

    fun applyServerAck(clientMessageId: String, serverMessageId: Long, serverCreatedAt: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.clientMessageId == clientMessageId) {
                        msg.copy(
                            serverId = serverMessageId,
                            createdAt = serverCreatedAt,
                            deliveryStatus = MessageDeliveryStatus.ACCEPTED
                        )
                    } else {
                        msg
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
