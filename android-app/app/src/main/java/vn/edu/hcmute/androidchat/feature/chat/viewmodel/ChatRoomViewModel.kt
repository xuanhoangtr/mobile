package vn.edu.hcmute.androidchat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var currentOpenConversationId: Long? = null
    private var typingDebounceJob: Job? = null
    private val typingExpiryJobs = mutableMapOf<Long, Job>()
    private val sentReadReceiptIds = mutableSetOf<Long>()

    init {
        if (transport != null) {
            viewModelScope.launch {
                transport.connectionState.collect { state ->
                    val previous = _uiState.value.connectionState
                    onConnectionStateChanged(state)
                    if (state == WsConnectionState.CONNECTED && previous != WsConnectionState.CONNECTED) {
                        retryPendingMessages()
                        sendReadReceiptsForUnread(_uiState.value.messages)
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
        currentUserId: Long = activeCurrentUserId,
        peerUserId: Long? = null,
        initialPeerOnline: Boolean = false,
        initialPeerLastSeenAt: String? = null
    ) {
        if (currentUserId > 0L) {
            activeCurrentUserId = currentUserId
        }
        val isReenteringOrSwitching = currentOpenConversationId != conversationId
        currentOpenConversationId = conversationId
        _uiState.update { state ->
            if (state.conversationId == conversationId) {
                state.copy(
                    conversationTitle = title,
                    isGroup = isGroup,
                    peerUserId = peerUserId ?: state.peerUserId,
                    isPeerOnline = when {
                        isGroup -> false
                        isReenteringOrSwitching -> initialPeerOnline
                        else -> state.isPeerOnline
                    },
                    peerLastSeenAt = when {
                        isGroup -> null
                        isReenteringOrSwitching -> initialPeerLastSeenAt ?: state.peerLastSeenAt
                        else -> state.peerLastSeenAt
                    }
                )
            } else {
                ChatRoomUiState(
                    conversationId = conversationId,
                    conversationTitle = title,
                    isGroup = isGroup,
                    peerUserId = peerUserId,
                    isPeerOnline = if (isGroup) false else initialPeerOnline,
                    peerLastSeenAt = if (isGroup) null else initialPeerLastSeenAt,
                    connectionState = state.connectionState
                )
            }
        }
        sendReadReceiptsForUnread(_uiState.value.messages)
    }

    fun clearActiveConversation() {
        currentOpenConversationId = null
        typingDebounceJob?.cancel()
        typingExpiryJobs.values.forEach { it.cancel() }
        typingExpiryJobs.clear()
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
                sendReadReceiptsForUnread(cached)
            }

            val service = apiService ?: return@launch
            val currentState = _uiState.value
            if (!currentState.isGroup && currentState.peerUserId == null) {
                resolvePeerUserIdIfNeeded(baseUrl, accessToken, conversationId, currentUserId, service)
            }
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
                sendReadReceiptsForUnread(_uiState.value.messages)
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
                sendReadReceiptsForUnread(_uiState.value.messages)
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
        val convId = _uiState.value.conversationId
        val activeTransport = transport
        if (convId > 0L && activeTransport != null &&
            activeTransport.connectionState.value == WsConnectionState.CONNECTED
        ) {
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(300L)
                activeTransport.sendTyping(convId, newText.isNotBlank())
            }
        }
    }

    fun setReplyingTo(message: MessageUiModel?) {
        _uiState.update { it.copy(replyingToMessage = message) }
    }

    fun findReplyTargetIndex(replyToId: Long): Int =
        _uiState.value.messages.indexOfFirst { it.serverId == replyToId }

    fun startEditing(message: MessageUiModel?) {
        _uiState.update {
            it.copy(
                editingMessage = message,
                draftText = message?.content ?: ""
            )
        }
    }

    fun getCopyableText(message: MessageUiModel): String? {
        if (message.isRecalled || message.content.isBlank()) return null
        return message.content
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
        val trimmed = _uiState.value.draftText.trim()
        if (trimmed.isEmpty()) return null
        return enqueueOutgoingMessage(
            currentUserId = currentUserId,
            content = trimmed,
            type = MessageType.TEXT,
            createdAtLabel = createdAtLabel,
            isTransportConnected = isTransportConnected,
            clearDraft = true
        )
    }

    /**
     * Enqueues and dispatches an uploaded voice message (`MessageType.VOICE`) over WebSocket.
     */
    fun queueOutgoingVoiceMessage(
        currentUserId: Long,
        mediaId: Long,
        durationMs: Long,
        createdAtLabel: String,
        isTransportConnected: Boolean = false
    ): MessageUiModel? {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(1L)
        val durationLabel = "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        return enqueueOutgoingMessage(
            currentUserId = currentUserId,
            content = "Tin nhắn thoại ($durationLabel) [media:$mediaId]",
            type = MessageType.VOICE,
            createdAtLabel = createdAtLabel,
            isTransportConnected = isTransportConnected,
            clearDraft = false
        )
    }

    private fun enqueueOutgoingMessage(
        currentUserId: Long,
        content: String,
        type: MessageType,
        createdAtLabel: String,
        isTransportConnected: Boolean,
        clearDraft: Boolean
    ): MessageUiModel? {
        val currentState = _uiState.value
        if (content.isBlank() || currentState.conversationId <= 0L) return null

        activeCurrentUserId = currentUserId
        val clientMsgId = UUID.randomUUID().toString()
        val transportConnected = isTransportConnected ||
            (transport?.connectionState?.value == WsConnectionState.CONNECTED)

        val replyId = currentState.replyingToMessage?.serverId
        val dispatched = if (transport != null && transportConnected) {
            transport.sendMessage(
                clientMessageId = clientMsgId,
                conversationId = currentState.conversationId,
                content = content,
                type = type,
                replyToId = replyId
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
            type = type,
            content = content,
            createdAt = createdAtLabel,
            replyToId = replyId,
            deliveryStatus = if (dispatched) {
                MessageDeliveryStatus.SENDING
            } else {
                MessageDeliveryStatus.FAILED
            }
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + pendingMessage,
                draftText = if (clearDraft) "" else state.draftText,
                replyingToMessage = null
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

    fun updateScrollPosition(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        _uiState.update {
            it.copy(
                firstVisibleMessageIndex = firstVisibleIndex.coerceAtLeast(0),
                firstVisibleMessageOffset = firstVisibleOffset.coerceAtLeast(0)
            )
        }
    }

    fun cancelFailedMessage(localId: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot {
                    it.localId == localId && it.deliveryStatus == MessageDeliveryStatus.FAILED
                }
            )
        }
        persistCurrentMessages()
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

    fun submitEdit(baseUrl: String, accessToken: String) {
        val editing = _uiState.value.editingMessage ?: return
        val serverId = editing.serverId ?: return
        val newContent = _uiState.value.draftText.trim()
        val service = apiService ?: return
        if (newContent.isEmpty()) return

        viewModelScope.launch {
            service.editMessage(baseUrl, accessToken, serverId, activeCurrentUserId, newContent)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            editingMessage = null,
                            draftText = "",
                            messages = state.messages.map {
                                if (it.serverId == serverId) it.copy(content = updated.content, isEdited = true) else it
                            }
                        )
                    }
                    persistCurrentMessages()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.message ?: "Failed to edit message") }
                }
        }
    }

    fun recallMessage(baseUrl: String, accessToken: String, messageId: Long) {
        val service = apiService ?: return
        viewModelScope.launch {
            service.recallMessage(baseUrl, accessToken, messageId, activeCurrentUserId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.serverId == messageId) it.copy(content = "", isRecalled = true) else it
                            }
                        )
                    }
                    persistCurrentMessages()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.message ?: "Failed to recall message") }
                }
        }
    }

    fun forwardMessage(
        baseUrl: String,
        accessToken: String,
        sourceMessageId: Long,
        targetConversationId: Long,
        onForwarded: (MessageUiModel) -> Unit = {}
    ) {
        val service = apiService ?: return
        val clientMsgId = UUID.randomUUID().toString()
        viewModelScope.launch {
            service.forwardMessage(
                baseUrl = baseUrl,
                accessToken = accessToken,
                sourceMessageId = sourceMessageId,
                targetConversationId = targetConversationId,
                clientMessageId = clientMsgId,
                currentUserId = activeCurrentUserId
            ).onSuccess { forwarded ->
                onForwarded(forwarded)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to forward message") }
            }
        }
    }

    fun toggleReaction(baseUrl: String, accessToken: String, messageId: Long, emoji: String) {
        val service = apiService ?: return
        viewModelScope.launch {
            service.toggleReaction(baseUrl, accessToken, messageId, emoji)
                .onSuccess { counts ->
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.serverId == messageId) it.copy(reactionCounts = counts) else it
                            }
                        )
                    }
                    persistCurrentMessages()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.message ?: "Failed to react") }
                }
        }
    }

    fun searchInConversation(baseUrl: String, accessToken: String, query: String) {
        val conversationId = _uiState.value.conversationId
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = query) }
        if (conversationId <= 0L || trimmed.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        val service = apiService ?: return
        viewModelScope.launch {
            service.searchMessages(
                baseUrl = baseUrl,
                accessToken = accessToken,
                conversationId = conversationId,
                query = trimmed,
                currentUserId = activeCurrentUserId
            ).onSuccess { page ->
                _uiState.update { it.copy(searchResults = page.items) }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Search failed") }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    fun loadPinnedMessages(baseUrl: String, accessToken: String) {
        val conversationId = _uiState.value.conversationId
        if (conversationId <= 0L) return
        val service = apiService ?: return
        viewModelScope.launch {
            service.listPinnedMessageIds(baseUrl, accessToken, conversationId)
                .onSuccess { ids -> _uiState.update { it.copy(pinnedMessageIds = ids) } }
        }
    }

    fun pinMessage(baseUrl: String, accessToken: String, messageId: Long) {
        val conversationId = _uiState.value.conversationId
        if (conversationId <= 0L || messageId <= 0L) return
        val service = apiService ?: return
        viewModelScope.launch {
            service.pinMessage(baseUrl, accessToken, conversationId, messageId)
                .onSuccess {
                    _uiState.update { state ->
                        if (messageId in state.pinnedMessageIds) state
                        else state.copy(pinnedMessageIds = listOf(messageId) + state.pinnedMessageIds)
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.message ?: "Failed to pin message") }
                }
        }
    }

    fun unpinMessage(baseUrl: String, accessToken: String, messageId: Long) {
        val conversationId = _uiState.value.conversationId
        if (conversationId <= 0L || messageId <= 0L) return
        val service = apiService ?: return
        viewModelScope.launch {
            service.unpinMessage(baseUrl, accessToken, conversationId, messageId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(pinnedMessageIds = state.pinnedMessageIds.filterNot { it == messageId })
                    }
                }
        }
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
                val isFromCurrentUser = activeCurrentUserId > 0L && event.senderId == activeCurrentUserId
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
                            isFromCurrentUser = isFromCurrentUser,
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
                if (!isFromCurrentUser) {
                    sendReadReceiptsForUnread(_uiState.value.messages)
                }
            }

            is ChatServerEvent.Receipt -> {
                if (event.conversationId != currentConversationId) return
                if (activeCurrentUserId > 0L && event.userId == activeCurrentUserId) return
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
                val state = _uiState.value
                if (currentOpenConversationId != null &&
                    !state.isGroup &&
                    event.userId != activeCurrentUserId &&
                    (state.peerUserId == null || event.userId == state.peerUserId)
                ) {
                    _uiState.update {
                        it.copy(
                            isPeerOnline = event.status.equals("ONLINE", ignoreCase = true),
                            peerLastSeenAt = event.lastSeenAt.takeIf { ts -> ts.isNotBlank() } ?: it.peerLastSeenAt
                        )
                    }
                }
            }

            is ChatServerEvent.Typing -> {
                if (event.conversationId != currentConversationId || event.userId == activeCurrentUserId) return
                val label = "User #${event.userId}"
                typingExpiryJobs.remove(event.userId)?.cancel()
                if (event.isTyping) {
                    _uiState.update { state ->
                        if (label in state.typingUserNames) state
                        else state.copy(typingUserNames = state.typingUserNames + label)
                    }
                    typingExpiryJobs[event.userId] = viewModelScope.launch {
                        delay(event.expiresInMs.coerceAtLeast(1_000L))
                        _uiState.update { state ->
                            state.copy(typingUserNames = state.typingUserNames - label)
                        }
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(typingUserNames = state.typingUserNames - label)
                    }
                }
            }

            is ChatServerEvent.MessageUpdated -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.serverId == event.messageId) it.copy(content = event.content, isEdited = true) else it
                        }
                    )
                }
                persistCurrentMessages()
            }

            is ChatServerEvent.MessageRecalled -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.serverId == event.messageId) it.copy(content = "", isRecalled = true) else it
                        }
                    )
                }
                persistCurrentMessages()
            }

            is ChatServerEvent.ReactionUpdated -> {
                if (event.conversationId != currentConversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.serverId == event.messageId) it.copy(reactionCounts = event.counts) else it
                        }
                    )
                }
                persistCurrentMessages()
            }

            is ChatServerEvent.Error -> {
                _uiState.update { it.copy(errorMessage = event.message) }
            }

            is ChatServerEvent.CallSignal -> Unit
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
        val (confirmed, pending) = byKey.values.partition { it.serverId != null }
        return confirmed.sortedBy { it.serverId } + pending
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

    private fun sendReadReceiptsForUnread(messages: List<MessageUiModel>) {
        val activeTransport = transport ?: return
        if (activeTransport.connectionState.value != WsConnectionState.CONNECTED) return
        val conversationId = currentOpenConversationId ?: return
        if (conversationId <= 0L || conversationId != _uiState.value.conversationId) return

        for (msg in messages) {
            val serverId = msg.serverId ?: continue
            if (!msg.isFromCurrentUser &&
                msg.conversationId == conversationId &&
                serverId > 0L &&
                msg.deliveryStatus != MessageDeliveryStatus.READ &&
                serverId !in sentReadReceiptIds
            ) {
                if (markMessageRead(serverId)) {
                    sentReadReceiptIds += serverId
                }
            }
        }
    }

    private fun resolvePeerUserIdIfNeeded(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        currentUserId: Long,
        service: ChatApiService
    ) {
        viewModelScope.launch {
            service.listGroupMembers(baseUrl, accessToken, conversationId)
                .onSuccess { members ->
                    val resolvedPeerId = members.firstOrNull { it.userId != currentUserId }?.userId
                    if (resolvedPeerId != null) {
                        _uiState.update { state ->
                            if (state.conversationId == conversationId && !state.isGroup && state.peerUserId == null) {
                                state.copy(peerUserId = resolvedPeerId)
                            } else {
                                state
                            }
                        }
                    }
                }
        }
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
