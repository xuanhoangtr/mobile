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
import vn.edu.hcmute.androidchat.feature.chat.model.ChatRoomUiState
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import java.util.UUID

/**
 * Holds active conversation state (including message draft) across screen rotation
 * and wires outgoing/incoming realtime events through [ChatRealtimeTransport].
 */
class ChatRoomViewModel(
    private val transport: ChatRealtimeTransport? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatRoomUiState())
    val uiState: StateFlow<ChatRoomUiState> = _uiState.asStateFlow()

    private var activeCurrentUserId: Long = 0L

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

    fun openConversation(
        conversationId: Long,
        title: String,
        isGroup: Boolean = false,
        currentUserId: Long = activeCurrentUserId
    ) {
        if (currentUserId > 0L) {
            activeCurrentUserId = currentUserId
        }
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
     * When [transport] is attached and connected, dispatches `message.send` over WebSocket.
     */
    fun queueOutgoingTextMessage(
        currentUserId: Long,
        createdAtLabel: String,
        isTransportConnected: Boolean = false
    ): MessageUiModel? {
        val currentState = _uiState.value
        val trimmed = currentState.draftText.trim()
        if (trimmed.isEmpty() || currentState.conversationId <= 0L) return null

        activeCurrentUserId = currentUserId
        val clientMsgId = UUID.randomUUID().toString()
        val transportConnected = isTransportConnected ||
            (transport?.connectionState?.value == WsConnectionState.CONNECTED)

        val dispatched = if (transport != null && transportConnected) {
            transport.sendMessage(
                clientMessageId = clientMsgId,
                conversationId = currentState.conversationId,
                content = trimmed,
                type = MessageType.TEXT
            )
        } else {
            transport == null && transportConnected
        }

        val pendingMessage = MessageUiModel(
            localId = clientMsgId,
            clientMessageId = clientMsgId,
            conversationId = currentState.conversationId,
            senderId = currentUserId,
            isFromCurrentUser = true,
            type = MessageType.TEXT,
            content = trimmed,
            createdAt = createdAtLabel,
            deliveryStatus = if (dispatched) {
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

    private fun handleServerEvent(event: ChatServerEvent) {
        val currentConversationId = _uiState.value.conversationId
        when (event) {
            is ChatServerEvent.MessageAck -> {
                if (event.conversationId == currentConversationId) {
                    applyServerAck(
                        clientMessageId = event.clientMessageId,
                        serverMessageId = event.messageId,
                        serverCreatedAt = event.serverCreatedAt
                    )
                }
            }

            is ChatServerEvent.MessageCreated -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    val existingIndex = state.messages.indexOfFirst { msg ->
                        msg.clientMessageId == event.clientMessageId ||
                            (msg.serverId != null && msg.serverId == event.id)
                    }
                    if (existingIndex >= 0) {
                        val updated = state.messages.toMutableList()
                        val existing = updated[existingIndex]
                        updated[existingIndex] = existing.copy(
                            serverId = event.id,
                            createdAt = event.createdAt,
                            deliveryStatus = if (existing.deliveryStatus == MessageDeliveryStatus.SENDING) {
                                MessageDeliveryStatus.ACCEPTED
                            } else {
                                existing.deliveryStatus
                            }
                        )
                        state.copy(messages = updated)
                    } else {
                        val incoming = MessageUiModel(
                            localId = event.clientMessageId,
                            serverId = event.id,
                            clientMessageId = event.clientMessageId,
                            conversationId = event.conversationId,
                            senderId = event.senderId,
                            isFromCurrentUser = activeCurrentUserId > 0L && event.senderId == activeCurrentUserId,
                            type = event.type,
                            content = event.content,
                            createdAt = event.createdAt,
                            deliveryStatus = MessageDeliveryStatus.DELIVERED,
                            replyToId = event.replyToId
                        )
                        state.copy(messages = state.messages + incoming)
                    }
                }
            }

            is ChatServerEvent.Receipt -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.serverId == event.messageId) {
                                msg.copy(deliveryStatus = event.status)
                            } else {
                                msg
                            }
                        }
                    )
                }
            }

            is ChatServerEvent.Error -> {
                _uiState.update { it.copy(errorMessage = event.message) }
            }

            else -> Unit
        }
    }
}
