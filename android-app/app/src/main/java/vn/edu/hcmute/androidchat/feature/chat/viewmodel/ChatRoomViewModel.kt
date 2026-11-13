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
import vn.edu.hcmute.androidchat.feature.chat.model.ChatRoomUiState
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import java.util.UUID

/**
 * Holds active conversation state across screen rotation, handles cursor-paginated history,
 * local cache persistence, realtime events, and idempotent retry on WebSocket reconnect.
 */
class ChatRoomViewModel(
    private val transport: ChatRealtimeTransport? = null,
    private val apiService: ChatApiService? = null,
    private val localCache: ChatLocalCache? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatRoomUiState())
    val uiState: StateFlow<ChatRoomUiState> = _uiState.asStateFlow()

    private var activeCurrentUserId: Long = 0L

    init {
        if (transport != null) {
            viewModelScope.launch {
                transport.connectionState.collect { state ->
                    val previous = _uiState.value.connectionState
                    onConnectionStateChanged(state)
                    if (state == WsConnectionState.CONNECTED && previous != WsConnectionState.CONNECTED) {
                        retryPendingMessages()
                    }
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

    fun loadInitialHistory(baseUrl: String, accessToken: String, currentUserId: Long, limit: Int = 30) {
        val conversationId = _uiState.value.conversationId
        if (conversationId <= 0L) return
        activeCurrentUserId = currentUserId

        viewModelScope.launch {
            val cached = runCatching { localCache?.getMessages(conversationId).orEmpty() }
                .getOrDefault(emptyList())
            if (cached.isNotEmpty() && _uiState.value.messages.isEmpty()) {
                _uiState.update { it.copy(messages = cached) }
            }

            val service = apiService ?: return@launch
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            service.listMessages(
                baseUrl = baseUrl,
                accessToken = accessToken,
                conversationId = conversationId,
                currentUserId = currentUserId,
                cursor = null,
                limit = limit
            ).onSuccess { page ->
                _uiState.update { state ->
                    val merged = mergeMessages(existing = page.items, incoming = state.messages)
                    state.copy(
                        isLoading = false,
                        messages = merged,
                        hasMoreHistory = page.hasMore,
                        nextHistoryCursor = page.nextCursor,
                        errorMessage = null
                    )
                }
                runCatching { localCache?.saveMessages(conversationId, _uiState.value.messages) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load history")
                }
            }
        }
    }

    fun loadOlderHistory(baseUrl: String, accessToken: String, currentUserId: Long, limit: Int = 30) {
        val state = _uiState.value
        val conversationId = state.conversationId
        val cursor = state.nextHistoryCursor
        if (conversationId <= 0L || !state.hasMoreHistory || state.isLoadingOlder || cursor == null) return
        val service = apiService ?: return
        activeCurrentUserId = currentUserId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOlder = true) }
            service.listMessages(
                baseUrl = baseUrl,
                accessToken = accessToken,
                conversationId = conversationId,
                currentUserId = currentUserId,
                cursor = cursor,
                limit = limit
            ).onSuccess { page ->
                _uiState.update { current ->
                    val merged = mergeMessages(existing = page.items, incoming = current.messages)
                    current.copy(
                        isLoadingOlder = false,
                        messages = merged,
                        hasMoreHistory = page.hasMore,
                        nextHistoryCursor = page.nextCursor
                    )
                }
                runCatching { localCache?.saveMessages(conversationId, _uiState.value.messages) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoadingOlder = false, errorMessage = err.message ?: "Failed to load older messages")
                }
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
        persistCurrentMessages()
        return pendingMessage
    }

    fun retryMessage(localId: String): Boolean {
        val activeTransport = transport ?: return false
        if (activeTransport.connectionState.value != WsConnectionState.CONNECTED) return false
        val target = _uiState.value.messages.firstOrNull { it.localId == localId } ?: return false
        val sent = activeTransport.sendMessage(
            clientMessageId = target.clientMessageId,
            conversationId = target.conversationId,
            content = target.content,
            type = target.type,
            replyToId = target.replyToId
        )
        if (sent) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.localId == localId) msg.copy(deliveryStatus = MessageDeliveryStatus.SENDING) else msg
                    }
                )
            }
        }
        return sent
    }

    fun retryPendingMessages() {
        val activeTransport = transport ?: return
        if (activeTransport.connectionState.value != WsConnectionState.CONNECTED) return
        val currentConversationId = _uiState.value.conversationId
        if (currentConversationId <= 0L) return

        val pending = _uiState.value.messages.filter {
            it.conversationId == currentConversationId &&
                it.isFromCurrentUser &&
                (it.deliveryStatus == MessageDeliveryStatus.FAILED || it.deliveryStatus == MessageDeliveryStatus.SENDING)
        }
        if (pending.isEmpty()) return

        val resentIds = mutableSetOf<String>()
        for (msg in pending) {
            val ok = activeTransport.sendMessage(
                clientMessageId = msg.clientMessageId,
                conversationId = msg.conversationId,
                content = msg.content,
                type = msg.type,
                replyToId = msg.replyToId
            )
            if (ok) {
                resentIds += msg.clientMessageId
            }
        }
        if (resentIds.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.clientMessageId in resentIds) {
                            msg.copy(deliveryStatus = MessageDeliveryStatus.SENDING)
                        } else {
                            msg
                        }
                    }
                )
            }
        }
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
        persistCurrentMessages()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun markMessageRead(messageId: Long): Boolean {
        val activeTransport = transport ?: return false
        val conversationId = _uiState.value.conversationId
        if (conversationId <= 0L || messageId <= 0L) return false
        return activeTransport.sendReceipt(
            conversationId = conversationId,
            messageId = messageId,
            status = MessageDeliveryStatus.READ
        )
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
                            deliveryStatus = if (existing.deliveryStatus == MessageDeliveryStatus.SENDING ||
                                existing.deliveryStatus == MessageDeliveryStatus.FAILED
                            ) {
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
                persistCurrentMessages()
            }

            is ChatServerEvent.Receipt -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.serverId == event.messageId) {
                                msg.copy(deliveryStatus = nextMonotonicStatus(msg.deliveryStatus, event.status))
                            } else {
                                msg
                            }
                        }
                    )
                }
                persistCurrentMessages()
            }

            is ChatServerEvent.Presence -> {
                if (event.userId != activeCurrentUserId) {
                    _uiState.update {
                        it.copy(
                            isPeerOnline = event.status.equals("ONLINE", ignoreCase = true),
                            peerLastSeenAt = event.lastSeenAt
                        )
                    }
                }
            }

            is ChatServerEvent.Error -> {
                _uiState.update { it.copy(errorMessage = event.message) }
            }

            else -> Unit
        }
    }

    private fun mergeMessages(
        existing: List<MessageUiModel>,
        incoming: List<MessageUiModel>
    ): List<MessageUiModel> {
        val byKey = LinkedHashMap<String, MessageUiModel>()
        for (msg in existing) {
            byKey[msg.clientMessageId] = msg
        }
        for (msg in incoming) {
            val duplicateByServerId = msg.serverId != null &&
                byKey.values.any { it.serverId == msg.serverId }
            if (!duplicateByServerId && !byKey.containsKey(msg.clientMessageId)) {
                byKey[msg.clientMessageId] = msg
            }
        }
        return byKey.values.toList()
    }

    private fun nextMonotonicStatus(
        current: MessageDeliveryStatus,
        incoming: MessageDeliveryStatus
    ): MessageDeliveryStatus {
        val currentRank = statusRank(current)
        val incomingRank = statusRank(incoming)
        return if (incomingRank >= currentRank) incoming else current
    }

    private fun statusRank(status: MessageDeliveryStatus): Int = when (status) {
        MessageDeliveryStatus.FAILED -> 0
        MessageDeliveryStatus.SENDING -> 1
        MessageDeliveryStatus.ACCEPTED -> 2
        MessageDeliveryStatus.DELIVERED -> 3
        MessageDeliveryStatus.READ -> 4
    }

    private fun persistCurrentMessages() {
        val cache = localCache ?: return
        val convId = _uiState.value.conversationId
        if (convId <= 0L) return
        val snapshot = _uiState.value.messages
        viewModelScope.launch {
            runCatching { cache.saveMessages(convId, snapshot) }
        }
    }
}
