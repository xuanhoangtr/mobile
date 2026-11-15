package vn.edu.hcmute.androidchat.feature.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.data.ChatApiService
import vn.edu.hcmute.androidchat.feature.chat.data.ChatLocalCache
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRealtimeTransport
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.data.CursorPageResult
import vn.edu.hcmute.androidchat.feature.chat.model.ChatMessageFormatter
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelsTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun chatListViewModelFiltersAndOrdersPinnedFirst() {
        val vm = ChatListViewModel()
        vm.setConversations(
            listOf(
                ConversationUiModel(
                    id = 1L,
                    type = ConversationType.DIRECT,
                    title = "Cong Huy",
                    lastMessagePreview = "Hi",
                    lastMessageTime = "12:00",
                    isPinned = false
                ),
                ConversationUiModel(
                    id = 2L,
                    type = ConversationType.GROUP,
                    title = "Android Team",
                    lastMessagePreview = "D02",
                    lastMessageTime = "12:05",
                    isPinned = true
                )
            )
        )

        val allVisible = vm.uiState.value.visibleConversations
        assertEquals(listOf(2L, 1L), allVisible.map { it.id })

        vm.onSearchQueryChanged("huy")
        val filtered = vm.uiState.value.visibleConversations
        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test
    fun chatRoomViewModelQueuesMessageWithClientIdAndAppliesAck() {
        val vm = ChatRoomViewModel()
        vm.openConversation(conversationId = 101L, title = "Quang Huy")
        vm.onDraftChanged("  Hello realtime  ")

        val queued = vm.queueOutgoingTextMessage(
            currentUserId = 3L,
            createdAtLabel = "13:00",
            isTransportConnected = true
        )
        assertNotNull(queued)
        assertEquals("", vm.uiState.value.draftText)
        assertEquals(MessageDeliveryStatus.SENDING, queued!!.deliveryStatus)

        vm.applyServerAck(
            clientMessageId = queued.clientMessageId,
            serverMessageId = 5002L,
            serverCreatedAt = "13:00:01"
        )
        val updated = vm.uiState.value.messages.single()
        assertEquals(5002L, updated.serverId)
        assertEquals(MessageDeliveryStatus.ACCEPTED, updated.deliveryStatus)
    }

    @Test
    fun chatRoomViewModelIgnoresBlankDraftAndMarksOfflineMessageFailed() {
        val vm = ChatRoomViewModel()
        vm.openConversation(conversationId = 101L, title = "Test")
        vm.onDraftChanged("   ")
        assertNull(vm.queueOutgoingTextMessage(currentUserId = 3L, createdAtLabel = "13:00"))

        vm.onDraftChanged("Offline msg")
        val offlineMsg = vm.queueOutgoingTextMessage(
            currentUserId = 3L,
            createdAtLabel = "13:01",
            isTransportConnected = false
        )
        assertNotNull(offlineMsg)
        assertEquals(MessageDeliveryStatus.FAILED, offlineMsg!!.deliveryStatus)
    }

    @Test
    fun chatRoomViewModelSendsOverTransportAndDeduplicatesEchoedCreatedEvent() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val vm = ChatRoomViewModel(transport = fakeTransport)

        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        assertEquals(WsConnectionState.CONNECTED, vm.uiState.value.connectionState)

        vm.openConversation(conversationId = 101L, title = "Group 101", currentUserId = 42L)
        vm.onDraftChanged("Realtime ping")
        val sent = vm.queueOutgoingTextMessage(currentUserId = 42L, createdAtLabel = "11:00")
        assertNotNull(sent)
        assertEquals(MessageDeliveryStatus.SENDING, sent!!.deliveryStatus)
        assertEquals(1, fakeTransport.sentMessages.size)

        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageAck(
                clientMessageId = sent.clientMessageId,
                conversationId = 101L,
                messageId = 7001L,
                status = "ACCEPTED",
                serverCreatedAt = "2026-11-13T04:00:01Z"
            )
        )
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 7001L,
                clientMessageId = sent.clientMessageId,
                conversationId = 101L,
                senderId = 42L,
                type = MessageType.TEXT,
                content = "Realtime ping",
                createdAt = "2026-11-13T04:00:01Z"
            )
        )
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 7002L,
                clientMessageId = "peer-msg-1",
                conversationId = 101L,
                senderId = 99L,
                type = MessageType.TEXT,
                content = "Reply from peer",
                createdAt = "2026-11-13T04:00:02Z"
            )
        )

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(7001L, messages[0].serverId)
        assertEquals(MessageDeliveryStatus.ACCEPTED, messages[0].deliveryStatus)
        assertEquals(7002L, messages[1].serverId)
        assertFalse(messages[1].isFromCurrentUser)
    }

    @Test
    fun chatRoomViewModelRetriesFailedMessagesIdempotentlyOnReconnect() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeCache = FakeChatLocalCache()
        val vm = ChatRoomViewModel(transport = fakeTransport, localCache = fakeCache)

        vm.openConversation(conversationId = 101L, title = "Quang Huy", currentUserId = 42L)
        vm.onDraftChanged("Offline retry message")
        val failed = vm.queueOutgoingTextMessage(currentUserId = 42L, createdAtLabel = "11:10")
        assertNotNull(failed)
        assertEquals(MessageDeliveryStatus.FAILED, failed!!.deliveryStatus)
        assertEquals(0, fakeTransport.sentMessages.size)

        // Simulate WebSocket reconnect -> auto-retry with the same clientMessageId
        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        assertEquals(1, fakeTransport.sentMessages.size)
        assertTrue(fakeTransport.sentMessages.single().contains(failed.clientMessageId))
        assertEquals(MessageDeliveryStatus.SENDING, vm.uiState.value.messages.single().deliveryStatus)

        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageAck(
                clientMessageId = failed.clientMessageId,
                conversationId = 101L,
                messageId = 9001L,
                status = "DUPLICATE",
                serverCreatedAt = "2026-11-13T04:10:05Z"
            )
        )
        val finalMsg = vm.uiState.value.messages.single()
        assertEquals(9001L, finalMsg.serverId)
        assertEquals(MessageDeliveryStatus.ACCEPTED, finalMsg.deliveryStatus)
        assertEquals(1, fakeCache.getMessages(101L).size)
    }

    @Test
    fun chatRoomViewModelLoadsPaginatedHistoryAndPrependsOlderWithoutDuplicates() = runTest {
        val fakeApi = FakeChatApiService()
        val fakeCache = FakeChatLocalCache()
        val vm = ChatRoomViewModel(apiService = fakeApi, localCache = fakeCache)

        vm.openConversation(conversationId = 202L, title = "Paginated", currentUserId = 42L)
        vm.loadInitialHistory(baseUrl = "http://localhost:8080", accessToken = "tok", currentUserId = 42L, limit = 2)

        assertEquals(listOf("msg-3", "msg-4"), vm.uiState.value.messages.map { it.content })
        assertTrue(vm.uiState.value.hasMoreHistory)
        assertEquals("3", vm.uiState.value.nextHistoryCursor)

        vm.loadOlderHistory(baseUrl = "http://localhost:8080", accessToken = "tok", currentUserId = 42L, limit = 2)
        assertEquals(listOf("msg-1", "msg-2", "msg-3", "msg-4"), vm.uiState.value.messages.map { it.content })
        assertFalse(vm.uiState.value.hasMoreHistory)
        assertEquals(4, fakeCache.getMessages(202L).size)
    }

    @Test
    fun chatListViewModelUpdatesConnectionStateAndPreviewFromTransport() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val vm = ChatListViewModel(transport = fakeTransport)
        vm.setConversations(
            listOf(
                ConversationUiModel(
                    id = 101L,
                    type = ConversationType.DIRECT,
                    title = "Quang Huy",
                    lastMessagePreview = "Old",
                    lastMessageTime = "10:00",
                    unreadCount = 0
                )
            )
        )

        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 8001L,
                clientMessageId = "c-8001",
                conversationId = 101L,
                senderId = 99L,
                type = MessageType.TEXT,
                content = "New realtime msg",
                createdAt = "11:05"
            )
        )

        assertEquals(WsConnectionState.CONNECTED, vm.uiState.value.connectionState)
        val updated = vm.uiState.value.conversations.single()
        assertEquals("New realtime msg", updated.lastMessagePreview)
        assertEquals(1, updated.unreadCount)
    }

    @Test
    fun chatListViewModelLoadsConversationsAndOpensDirectChat() = runTest {
        val fakeApi = FakeChatApiService()
        val fakeCache = FakeChatLocalCache()
        val vm = ChatListViewModel(apiService = fakeApi, localCache = fakeCache)

        vm.loadConversations(baseUrl = "http://localhost:8080", accessToken = "tok")
        assertEquals(1, vm.uiState.value.conversations.size)
        assertEquals(1, fakeCache.getConversations().size)

        var openedId = 0L
        vm.openDirectConversation(baseUrl = "http://localhost:8080", accessToken = "tok", targetUserId = 99L) {
            openedId = it.id
        }
        assertEquals(303L, openedId)
        assertTrue(vm.uiState.value.conversations.any { it.id == 303L })
    }

    @Test
    fun chatRoomViewModelTracksPresenceAndEnforcesMonotonicReceiptStatus() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val vm = ChatRoomViewModel(transport = fakeTransport)

        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        vm.openConversation(conversationId = 101L, title = "Quang Huy", currentUserId = 42L)
        vm.onDraftChanged("Status check")
        val msg = vm.queueOutgoingTextMessage(currentUserId = 42L, createdAtLabel = "11:25")!!

        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageAck(msg.clientMessageId, 101L, 777L, "ACCEPTED", "11:25:01")
        )
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.Receipt(101L, 777L, 99L, MessageDeliveryStatus.READ, "11:25:02")
        )
        // Downgrade attempt from READ -> DELIVERED must be ignored
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.Receipt(101L, 777L, 99L, MessageDeliveryStatus.DELIVERED, "11:25:03")
        )
        assertEquals(MessageDeliveryStatus.READ, vm.uiState.value.messages.single().deliveryStatus)

        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "ONLINE", "2026-11-13T11:25:04Z"))
        assertTrue(vm.uiState.value.isPeerOnline)
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "OFFLINE", "2026-11-13T11:26:00Z"))
        assertFalse(vm.uiState.value.isPeerOnline)
        assertEquals("2026-11-13T11:26:00Z", vm.uiState.value.peerLastSeenAt)
    }

    @Test
    fun chatRoomViewModelHandlesTypingAndReplyJumpEditRecallForwardCopyAndReactions() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService()
        val vm = ChatRoomViewModel(transport = fakeTransport, apiService = fakeApi)

        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        vm.openConversation(conversationId = 101L, title = "Room", currentUserId = 42L)

        // Typing from other conversation is ignored; typing from current conversation shows up
        fakeTransport.eventsFlow.emit(ChatServerEvent.Typing(999L, 99L, true, 5_000L))
        assertTrue(vm.uiState.value.typingUserNames.isEmpty())
        fakeTransport.eventsFlow.emit(ChatServerEvent.Typing(101L, 99L, true, 5_000L))
        assertEquals(listOf("User #99"), vm.uiState.value.typingUserNames)
        fakeTransport.eventsFlow.emit(ChatServerEvent.Typing(101L, 99L, false, 0L))
        assertTrue(vm.uiState.value.typingUserNames.isEmpty())

        // Send original message + reply to it + jump-to-message index
        vm.onDraftChanged("Parent message")
        val parent = vm.queueOutgoingTextMessage(42L, "11:30")!!
        fakeTransport.eventsFlow.emit(ChatServerEvent.MessageAck(parent.clientMessageId, 101L, 501L, "ACCEPTED", "11:30:01"))

        val parentMsg = vm.uiState.value.messages.single()
        assertEquals("Parent message", vm.getCopyableText(parentMsg))
        vm.setReplyingTo(parentMsg)
        vm.onDraftChanged("Reply message")
        val reply = vm.queueOutgoingTextMessage(42L, "11:31")!!
        assertEquals(501L, reply.replyToId)
        assertEquals(0, vm.findReplyTargetIndex(reply.replyToId!!))

        // Edit parent message
        vm.startEditing(parentMsg)
        vm.onDraftChanged("Edited parent")
        vm.submitEdit("http://localhost:8080", "tok")
        assertEquals("Edited parent", vm.uiState.value.messages[0].content)
        assertTrue(vm.uiState.value.messages[0].isEdited)

        // Toggle reaction
        vm.toggleReaction("http://localhost:8080", "tok", 501L, "👍")
        assertEquals(1, vm.uiState.value.messages[0].reactionCounts["👍"])

        // Forward message
        var forwardedTargetId = 0L
        vm.forwardMessage("http://localhost:8080", "tok", 501L, 202L) { forwardedTargetId = it.conversationId }
        assertEquals(202L, forwardedTargetId)

        // Recall message -> copyable text becomes null
        vm.recallMessage("http://localhost:8080", "tok", 501L)
        assertTrue(vm.uiState.value.messages[0].isRecalled)
        assertNull(vm.getCopyableText(vm.uiState.value.messages[0]))
    }

    @Test
    fun chatRoomViewModelSearchesAndPinsMessagesAndListViewModelPersistsPin() = runTest {
        val fakeApi = FakeChatApiService()
        val fakeCache = FakeChatLocalCache()
        val roomVm = ChatRoomViewModel(apiService = fakeApi, localCache = fakeCache)
        roomVm.openConversation(conversationId = 202L, title = "Searchable", currentUserId = 42L)

        roomVm.searchInConversation("http://localhost:8080", "tok", "kotlin")
        assertEquals(1, roomVm.uiState.value.searchResults.size)
        assertEquals("Kotlin search hit", roomVm.uiState.value.searchResults.single().content)
        roomVm.clearSearch()
        assertTrue(roomVm.uiState.value.searchResults.isEmpty())

        roomVm.pinMessage("http://localhost:8080", "tok", 900L)
        assertEquals(listOf(900L), roomVm.uiState.value.pinnedMessageIds)
        roomVm.unpinMessage("http://localhost:8080", "tok", 900L)
        assertTrue(roomVm.uiState.value.pinnedMessageIds.isEmpty())

        val listVm = ChatListViewModel(apiService = fakeApi, localCache = fakeCache)
        listVm.loadConversations("http://localhost:8080", "tok")
        listVm.togglePinConversation("http://localhost:8080", "tok", 202L)
        assertTrue(listVm.uiState.value.conversations.single().isPinned)
        assertTrue(fakeCache.getConversations().single().isPinned)
    }

    @Test
    fun transientWsConnectionFailureDoesNotClearListAndConnectedClearsErrors() = runTest {
        val transport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService()
        val listVm = ChatListViewModel(transport = transport, apiService = fakeApi)
        val roomVm = ChatRoomViewModel(transport = transport, apiService = fakeApi)

        listVm.loadConversations("http://localhost:8080", "tok")
        assertEquals(1, listVm.uiState.value.conversations.size)

        // Simulate transient HTTP 502 handshake failure during server restart
        transport.connectionStateFlow.value = WsConnectionState.RECONNECTING
        transport.eventsFlow.emit(
            ChatServerEvent.Error(
                requestId = null,
                code = "WS_CONNECTION_FAILED",
                message = "Expected HTTP 101 response but was '502 Bad Gateway'"
            )
        )
        assertNull(listVm.uiState.value.errorMessage)
        assertNull(roomVm.uiState.value.errorMessage)
        assertEquals(1, listVm.uiState.value.conversations.size)

        // Any explicit non-transient error clears as soon as WebSocket reconnects
        transport.eventsFlow.emit(ChatServerEvent.Error(null, "SERVER_ERR", "Tmp"))
        assertEquals("Tmp", listVm.uiState.value.errorMessage)
        assertEquals("Tmp", roomVm.uiState.value.errorMessage)

        transport.connectionStateFlow.value = WsConnectionState.CONNECTED
        assertNull(listVm.uiState.value.errorMessage)
        assertNull(roomVm.uiState.value.errorMessage)
    }

    @Test
    fun chatRoomViewModelPreservesScrollPositionCancelsFailedAndOrdersMessages() = runTest {
        val fakeApi = FakeChatApiService()
        val vm = ChatRoomViewModel(apiService = fakeApi)
        vm.openConversation(conversationId = 202L, title = "Rotation", currentUserId = 42L)

        vm.updateScrollPosition(firstVisibleIndex = 12, firstVisibleOffset = 48)
        assertEquals(12, vm.uiState.value.firstVisibleMessageIndex)
        assertEquals(48, vm.uiState.value.firstVisibleMessageOffset)

        vm.onDraftChanged("Will cancel")
        val failed = vm.queueOutgoingTextMessage(42L, "12:00", isTransportConnected = false)!!
        assertEquals(1, vm.uiState.value.messages.size)
        vm.cancelFailedMessage(failed.localId)
        assertTrue(vm.uiState.value.messages.isEmpty())

        // Load history and verify serverId chronological ordering
        vm.loadInitialHistory("http://localhost:8080", "tok", currentUserId = 42L, limit = 2)
        val ids = vm.uiState.value.messages.mapNotNull { it.serverId }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun chatListViewModelTracksPresenceClearsUnreadAndSendsDeliveredReceiptForBackgroundMessages() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeCache = FakeChatLocalCache()
        val vm = ChatListViewModel(transport = fakeTransport, localCache = fakeCache)
        vm.setCurrentUserId(42L)
        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED

        // Presence arrives before conversations are loaded -> cached and applied on setConversations
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "ONLINE", "2026-11-14T10:00:00Z"))
        assertEquals(true to "2026-11-14T10:00:00Z", vm.presenceForPeer(99L))

        vm.setConversations(
            listOf(
                ConversationUiModel(
                    id = 101L,
                    type = ConversationType.DIRECT,
                    title = "Peer 99",
                    lastMessagePreview = "Hi",
                    lastMessageTime = "09:50",
                    unreadCount = 2,
                    isOnline = false,
                    peerUserId = 99L,
                    peerLastSeenAt = "2026-11-14T09:00:00Z"
                ),
                ConversationUiModel(
                    id = 202L,
                    type = ConversationType.GROUP,
                    title = "Group",
                    lastMessagePreview = "Hey",
                    lastMessageTime = "09:55",
                    unreadCount = 0,
                    peerUserId = 99L
                )
            )
        )
        assertTrue(vm.uiState.value.conversations[0].isOnline)
        assertFalse(vm.uiState.value.conversations[1].isOnline)

        // Presence goes OFFLINE -> updates DIRECT conversation only
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "OFFLINE", "2026-11-14T10:05:00Z"))
        assertFalse(vm.uiState.value.conversations[0].isOnline)
        assertEquals("2026-11-14T10:05:00Z", vm.uiState.value.conversations[0].peerLastSeenAt)

        // markConversationRead resets unreadCount and persists
        vm.markConversationRead(101L)
        assertEquals(0, vm.uiState.value.conversations[0].unreadCount)
        assertEquals(0, fakeCache.getConversations().first { it.id == 101L }.unreadCount)

        // Background incoming message -> increments unread and sends DELIVERED once
        val bgMsg = ChatServerEvent.MessageCreated(
            id = 5001L,
            clientMessageId = "c-5001",
            conversationId = 101L,
            senderId = 99L,
            type = MessageType.TEXT,
            content = "Ping",
            createdAt = "2026-11-14T10:06:00Z"
        )
        fakeTransport.eventsFlow.emit(bgMsg)
        fakeTransport.eventsFlow.emit(bgMsg) // duplicate should not send second receipt
        assertEquals(listOf("101:5001:DELIVERED"), fakeTransport.sentReceipts)
        assertEquals(2, vm.uiState.value.conversations[0].unreadCount)

        // Own message or active room message -> no DELIVERED receipt and no unread increment
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 5002L,
                clientMessageId = "c-5002",
                conversationId = 101L,
                senderId = 42L,
                type = MessageType.TEXT,
                content = "Own echo",
                createdAt = "2026-11-14T10:07:00Z"
            )
        )
        vm.setActiveConversation(202L)
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 5003L,
                clientMessageId = "c-5003",
                conversationId = 202L,
                senderId = 99L,
                type = MessageType.TEXT,
                content = "Active room msg",
                createdAt = "2026-11-14T10:08:00Z"
            )
        )
        assertEquals(listOf("101:5001:DELIVERED"), fakeTransport.sentReceipts)
        assertEquals(2, vm.uiState.value.conversations[0].unreadCount)
        assertEquals(0, vm.uiState.value.conversations[1].unreadCount)
    }

    @Test
    fun chatRoomViewModelScopesPresenceAndAutoSendsReadReceiptsOncePerUnreadPeerMessage() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService(
            customMessages = listOf(
                MessageUiModel(
                    localId = "peer-1",
                    serverId = 10L,
                    clientMessageId = "peer-1",
                    conversationId = 101L,
                    senderId = 99L,
                    isFromCurrentUser = false,
                    content = "Unread from peer",
                    createdAt = "2026-11-14T10:00:00Z",
                    deliveryStatus = MessageDeliveryStatus.DELIVERED
                ),
                MessageUiModel(
                    localId = "peer-read",
                    serverId = 11L,
                    clientMessageId = "peer-read",
                    conversationId = 101L,
                    senderId = 99L,
                    isFromCurrentUser = false,
                    content = "Already read",
                    createdAt = "2026-11-14T10:01:00Z",
                    deliveryStatus = MessageDeliveryStatus.READ
                ),
                MessageUiModel(
                    localId = "mine-1",
                    serverId = 12L,
                    clientMessageId = "mine-1",
                    conversationId = 101L,
                    senderId = 42L,
                    isFromCurrentUser = true,
                    content = "My message",
                    createdAt = "2026-11-14T10:02:00Z",
                    deliveryStatus = MessageDeliveryStatus.ACCEPTED
                )
            )
        )
        val vm = ChatRoomViewModel(transport = fakeTransport, apiService = fakeApi)
        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED

        vm.openConversation(
            conversationId = 101L,
            title = "Peer 99",
            isGroup = false,
            currentUserId = 42L,
            peerUserId = 99L,
            initialPeerOnline = false,
            initialPeerLastSeenAt = "2026-11-14T09:50:00Z"
        )

        // Presence from unrelated peer 88 is ignored; matching peer 99 updates state
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(88L, "ONLINE", "2026-11-14T10:00:00Z"))
        assertFalse(vm.uiState.value.isPeerOnline)
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "ONLINE", "2026-11-14T10:03:00Z"))
        assertTrue(vm.uiState.value.isPeerOnline)

        // History load sends READ only for unread peer message 10
        vm.loadInitialHistory("http://localhost:8080", "tok", currentUserId = 42L)
        assertEquals(listOf("101:10:READ"), fakeTransport.sentReceipts)

        // Incoming peer MessageCreated sends READ once
        val incoming = ChatServerEvent.MessageCreated(
            id = 13L,
            clientMessageId = "peer-13",
            conversationId = 101L,
            senderId = 99L,
            type = MessageType.TEXT,
            content = "Live msg",
            createdAt = "2026-11-14T10:04:00Z"
        )
        fakeTransport.eventsFlow.emit(incoming)
        fakeTransport.eventsFlow.emit(incoming)
        assertEquals(listOf("101:10:READ", "101:13:READ"), fakeTransport.sentReceipts)

        // Echo receipt from currentUserId is ignored
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.Receipt(101L, 12L, 42L, MessageDeliveryStatus.READ, "2026-11-14T10:05:00Z")
        )
        assertEquals(
            MessageDeliveryStatus.ACCEPTED,
            vm.uiState.value.messages.first { it.serverId == 12L }.deliveryStatus
        )

        // After leaving room, no READ receipt is sent for new messages and Presence is ignored
        vm.clearActiveConversation()
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "OFFLINE", "2026-11-14T10:06:00Z"))
        assertTrue(vm.uiState.value.isPeerOnline)
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 14L,
                clientMessageId = "peer-14",
                conversationId = 101L,
                senderId = 99L,
                type = MessageType.TEXT,
                content = "While away",
                createdAt = "2026-11-14T10:07:00Z"
            )
        )
        assertEquals(listOf("101:10:READ", "101:13:READ"), fakeTransport.sentReceipts)

        // Group chat ignores Presence events
        vm.openConversation(conversationId = 303L, title = "Group", isGroup = true, currentUserId = 42L)
        fakeTransport.eventsFlow.emit(ChatServerEvent.Presence(99L, "ONLINE", "2026-11-14T10:08:00Z"))
        assertFalse(vm.uiState.value.isPeerOnline)
    }

    @Test
    fun chatRoomViewModelFlushesReadReceiptsWhenTransportReconnects() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService(
            customMessages = listOf(
                MessageUiModel(
                    localId = "offline-peer-1",
                    serverId = 77L,
                    clientMessageId = "offline-peer-1",
                    conversationId = 101L,
                    senderId = 99L,
                    isFromCurrentUser = false,
                    content = "Fetched while disconnected",
                    createdAt = "2026-11-14T10:00:00Z",
                    deliveryStatus = MessageDeliveryStatus.DELIVERED
                )
            )
        )
        val vm = ChatRoomViewModel(transport = fakeTransport, apiService = fakeApi)
        vm.openConversation(conversationId = 101L, title = "Peer 99", currentUserId = 42L, peerUserId = 99L)
        vm.loadInitialHistory("http://localhost:8080", "tok", currentUserId = 42L)
        assertTrue(fakeTransport.sentReceipts.isEmpty())

        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        assertEquals(listOf("101:77:READ"), fakeTransport.sentReceipts)
    }

    @Test
    fun chatRoomViewModelHandlesAttachmentsDeleteCallSignalEditDraftAndGroupTypingNames() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService(
            groupMembers = listOf(
                vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel(42L, "Me"),
                vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel(99L, "Quang Huy")
            )
        )
        val vm = ChatRoomViewModel(transport = fakeTransport, apiService = fakeApi)
        fakeTransport.connectionStateFlow.value = WsConnectionState.CONNECTED
        vm.openConversation(conversationId = 202L, title = "Group 202", isGroup = true, currentUserId = 42L)
        vm.loadInitialHistory("http://localhost:8080", "tok", currentUserId = 42L)

        // Group typing uses member display name
        fakeTransport.eventsFlow.emit(ChatServerEvent.Typing(202L, 99L, true, 5_000L))
        assertEquals(listOf("Quang Huy"), vm.uiState.value.typingUserNames)

        // Enqueue media, location, and sticker messages -> dispatches typing(false) + attachment metadata
        val mediaMsg = vm.queueOutgoingMediaMessage(
            currentUserId = 42L,
            mediaId = 55L,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            byteSize = 1024L,
            createdAtLabel = "12:00"
        )!!
        assertEquals(MessageType.IMAGE, mediaMsg.type)
        assertEquals(55L, mediaMsg.mediaRef)
        assertTrue(mediaMsg.metadataJson?.contains("photo.jpg") == true)

        val locMsg = vm.queueOutgoingLocationMessage(
            currentUserId = 42L,
            latitude = 10.8505,
            longitude = 106.7717,
            label = "HCMUTE",
            createdAtLabel = "12:01"
        )!!
        assertEquals(MessageType.LOCATION, locMsg.type)
        assertTrue(locMsg.metadataJson?.contains("10.8505") == true)

        val stickerMsg = vm.queueOutgoingStickerMessage(
            currentUserId = 42L,
            stickerId = "fire",
            unicode = "🔥",
            label = "Fire",
            createdAtLabel = "12:02"
        )!!
        assertEquals(MessageType.STICKER, stickerMsg.type)
        assertEquals("🔥", stickerMsg.content)

        // Sending stops typing indicator
        assertTrue(fakeTransport.sentTypingEvents.any { it == (202L to false) })

        // Edit preserves pre-edit draft on cancel and clears replyingToMessage on start
        val existingMsg = vm.uiState.value.messages.first { it.serverId == 3L }
        vm.setReplyingTo(existingMsg)
        vm.onDraftChanged("unsent draft")
        vm.startEditing(existingMsg)
        assertNull(vm.uiState.value.replyingToMessage)
        assertEquals("msg-3", vm.uiState.value.draftText)
        vm.startEditing(null)
        assertNull(vm.uiState.value.editingMessage)
        assertEquals("unsent draft", vm.uiState.value.draftText)

        // Call signal delegates to transport
        assertTrue(vm.sendCallSignal(callId = "call-1", signalType = "OFFER", targetUserId = 99L, sdpOrCandidate = "sdp"))
        assertEquals(1, fakeTransport.sentCallSignals.size)

        // Delete message removes locally & handles MessageDeleted WS event
        vm.deleteMessage("http://localhost:8080", "tok", 3L)
        assertFalse(vm.uiState.value.messages.any { it.serverId == 3L })
        fakeTransport.eventsFlow.emit(ChatServerEvent.MessageDeleted(202L, 4L, 42L))
        assertFalse(vm.uiState.value.messages.any { it.serverId == 4L })

        // clearActiveConversation sends typing(false)
        fakeTransport.sentTypingEvents.clear()
        vm.clearActiveConversation()
        assertEquals(listOf(202L to false), fakeTransport.sentTypingEvents)
    }

    @Test
    fun chatListViewModelUpdatesPreviewOnMessageUpdatedAndRecalledAndSearchesGlobalMessages() = runTest {
        val fakeTransport = FakeChatRealtimeTransport()
        val fakeApi = FakeChatApiService()
        val vm = ChatListViewModel(transport = fakeTransport, apiService = fakeApi)
        vm.setConversations(
            listOf(
                ConversationUiModel(
                    id = 202L,
                    type = ConversationType.DIRECT,
                    title = "Cong Huy",
                    lastMessagePreview = "Original",
                    lastMessageTime = "10:00"
                )
            )
        )

        fakeTransport.eventsFlow.emit(ChatServerEvent.MessageUpdated(202L, 3L, "Updated text", "10:01"))
        assertEquals("Updated text", vm.uiState.value.conversations.single().lastMessagePreview)

        fakeTransport.eventsFlow.emit(ChatServerEvent.MessageRecalled(202L, 3L, "10:02"))
        assertEquals("Tin nhắn đã được thu hồi", vm.uiState.value.conversations.single().lastMessagePreview)

        vm.searchGlobalMessages("http://localhost:8080", "tok", "kotlin")
        assertEquals(1, vm.uiState.value.searchMessageResults.size)
        vm.onSearchQueryChanged("")
        assertTrue(vm.uiState.value.searchMessageResults.isEmpty())
    }

    @Test
    fun voiceMessageFormattingUsesSecondsUnderOneMinuteAndHidesMediaTag() = runTest {
        assertEquals("2s", ChatMessageFormatter.formatDurationShort(2_000L))
        assertEquals("59s", ChatMessageFormatter.formatDurationShort(59_000L))
        assertEquals("1:05", ChatMessageFormatter.formatDurationShort(65_000L))
        assertEquals(
            "Tin nhắn thoại (2s)",
            ChatMessageFormatter.sanitizePreview("Tin nhắn thoại (0:02) [media:8955789940]")
        )
        assertEquals("Hình ảnh", ChatMessageFormatter.sanitizePreview("Hình ảnh [media:42]"))

        val fakeTransport = FakeChatRealtimeTransport()
        val roomVm = ChatRoomViewModel(transport = fakeTransport)
        roomVm.openConversation(conversationId = 3L, title = "Nhom Do An UTE")
        val queued = roomVm.queueOutgoingVoiceMessage(
            currentUserId = 1L,
            mediaId = 8955789940L,
            durationMs = 2_000L,
            createdAtLabel = "10:05",
            isTransportConnected = true
        )
        assertNotNull(queued)
        assertEquals("Tin nhắn thoại (2s)", queued!!.content)
        assertEquals(8955789940L, queued.mediaRef)
        assertFalse(queued.content.contains("[media:"))

        val listVm = ChatListViewModel(transport = fakeTransport, apiService = FakeChatApiService())
        listVm.setConversations(
            listOf(
                ConversationUiModel(
                    id = 3L,
                    type = ConversationType.GROUP,
                    title = "Nhom Do An UTE",
                    lastMessagePreview = "Old",
                    lastMessageTime = "10:00"
                )
            )
        )
        fakeTransport.eventsFlow.emit(
            ChatServerEvent.MessageCreated(
                id = 99L,
                clientMessageId = "c-99",
                conversationId = 3L,
                senderId = 2L,
                type = MessageType.VOICE,
                content = "Tin nhắn thoại (0:02) [media:8955789940]",
                createdAt = "2026-11-15T01:06:00Z"
            )
        )
        assertEquals("Tin nhắn thoại (2s)", listVm.uiState.value.conversations.single().lastMessagePreview)
    }

    private class FakeChatRealtimeTransport : ChatRealtimeTransport {
        val connectionStateFlow = MutableStateFlow(WsConnectionState.DISCONNECTED)
        val eventsFlow = MutableSharedFlow<ChatServerEvent>(extraBufferCapacity = 16)
        val sentMessages = mutableListOf<String>()
        val sentReceipts = mutableListOf<String>()
        val sentTypingEvents = mutableListOf<Pair<Long, Boolean>>()
        val sentCallSignals = mutableListOf<String>()

        override val connectionState: StateFlow<WsConnectionState> = connectionStateFlow
        override val events: SharedFlow<ChatServerEvent> = eventsFlow

        override fun connect(wsUrl: String, accessToken: String) = Unit
        override fun disconnect() = Unit

        override fun sendMessage(
            clientMessageId: String,
            conversationId: Long,
            content: String,
            type: MessageType,
            replyToId: Long?,
            mediaRef: Long?,
            metadataJson: String?
        ): Boolean {
            sentMessages += "$conversationId:$clientMessageId:$content"
            return true
        }

        override fun sendTyping(conversationId: Long, isTyping: Boolean): Boolean {
            sentTypingEvents += conversationId to isTyping
            return true
        }

        override fun sendReceipt(
            conversationId: Long,
            messageId: Long,
            status: MessageDeliveryStatus
        ): Boolean {
            sentReceipts += "$conversationId:$messageId:${status.name}"
            return true
        }

        override fun sendCallSignal(
            conversationId: Long,
            callId: String,
            signalType: String,
            targetUserId: Long?,
            sdpOrCandidate: String?
        ): Boolean {
            sentCallSignals += "$conversationId:$callId:$signalType"
            return true
        }
    }

    private class FakeChatApiService(
        private val customMessages: List<MessageUiModel>? = null,
        private val groupMembers: List<vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel> = emptyList()
    ) : ChatApiService {
        override suspend fun listConversations(
            baseUrl: String,
            accessToken: String,
            cursor: String?,
            limit: Int
        ): Result<CursorPageResult<ConversationUiModel>> {
            return Result.success(
                CursorPageResult(
                    items = listOf(
                        ConversationUiModel(
                            id = 202L,
                            type = ConversationType.DIRECT,
                            title = "Cong Huy",
                            lastMessagePreview = "msg-4",
                            lastMessageTime = "11:00"
                        )
                    ),
                    nextCursor = null,
                    hasMore = false
                )
            )
        }

        override suspend fun createOrGetDirectConversation(
            baseUrl: String,
            accessToken: String,
            targetUserId: Long
        ): Result<ConversationUiModel> {
            return Result.success(
                ConversationUiModel(
                    id = 303L,
                    type = ConversationType.DIRECT,
                    title = "User $targetUserId",
                    lastMessagePreview = null,
                    lastMessageTime = "11:15"
                )
            )
        }

        override suspend fun listMessages(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            currentUserId: Long,
            cursor: String?,
            limit: Int
        ): Result<CursorPageResult<MessageUiModel>> {
            if (customMessages != null) {
                return Result.success(
                    CursorPageResult(
                        items = customMessages,
                        nextCursor = null,
                        hasMore = false
                    )
                )
            }
            return if (cursor == null) {
                Result.success(
                    CursorPageResult(
                        items = listOf(
                            sampleMsg(3L, conversationId, "msg-3"),
                            sampleMsg(4L, conversationId, "msg-4")
                        ),
                        nextCursor = "3",
                        hasMore = true
                    )
                )
            } else {
                Result.success(
                    CursorPageResult(
                        items = listOf(
                            sampleMsg(1L, conversationId, "msg-1"),
                            sampleMsg(2L, conversationId, "msg-2")
                        ),
                        nextCursor = null,
                        hasMore = false
                    )
                )
            }
        }

        private fun sampleMsg(id: Long, convId: Long, content: String) = MessageUiModel(
            localId = "c-$id",
            serverId = id,
            clientMessageId = "c-$id",
            conversationId = convId,
            senderId = 42L,
            isFromCurrentUser = true,
            content = content,
            createdAt = "2026-11-13T11:0$id:00Z",
            deliveryStatus = MessageDeliveryStatus.DELIVERED
        )

        override suspend fun createGroupConversation(
            baseUrl: String,
            accessToken: String,
            name: String,
            memberIds: List<Long>
        ) = Result.failure<ConversationUiModel>(UnsupportedOperationException())

        override suspend fun listGroupMembers(baseUrl: String, accessToken: String, conversationId: Long) =
            Result.success(groupMembers)

        override suspend fun addGroupMembers(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            userIds: List<Long>
        ) = Result.success(emptyList<vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel>())

        override suspend fun updateGroupMemberRole(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            targetUserId: Long,
            role: vn.edu.hcmute.androidchat.feature.groups.model.GroupRole
        ) = Result.failure<vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel>(UnsupportedOperationException())

        override suspend fun removeGroupMember(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            targetUserId: Long
        ) = Result.success(Unit)

        override suspend fun editMessage(
            baseUrl: String,
            accessToken: String,
            messageId: Long,
            currentUserId: Long,
            content: String
        ) = Result.success(sampleMsg(messageId, 101L, content).copy(isEdited = true))

        override suspend fun recallMessage(
            baseUrl: String,
            accessToken: String,
            messageId: Long,
            currentUserId: Long
        ) = Result.success(sampleMsg(messageId, 101L, "").copy(isRecalled = true))

        override suspend fun forwardMessage(
            baseUrl: String,
            accessToken: String,
            sourceMessageId: Long,
            targetConversationId: Long,
            clientMessageId: String,
            currentUserId: Long
        ) = Result.success(sampleMsg(999L, targetConversationId, "Forwarded").copy(forwardedFromId = sourceMessageId))

        override suspend fun toggleReaction(
            baseUrl: String,
            accessToken: String,
            messageId: Long,
            emoji: String
        ) = Result.success(mapOf(emoji to 1))

        override suspend fun searchMessages(
            baseUrl: String,
            accessToken: String,
            conversationId: Long?,
            query: String,
            currentUserId: Long,
            cursor: String?,
            limit: Int
        ) = Result.success(
            CursorPageResult(
                items = listOf(sampleMsg(701L, conversationId ?: 202L, "Kotlin search hit")),
                nextCursor = null,
                hasMore = false
            )
        )

        override suspend fun setConversationPinned(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            pinned: Boolean
        ) = Result.success(
            ConversationUiModel(
                id = conversationId,
                type = ConversationType.DIRECT,
                title = "Cong Huy",
                lastMessagePreview = "msg-4",
                lastMessageTime = "11:00",
                isPinned = pinned
            )
        )

        override suspend fun listPinnedMessageIds(
            baseUrl: String,
            accessToken: String,
            conversationId: Long
        ) = Result.success(emptyList<Long>())

        override suspend fun pinMessage(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            messageId: Long
        ) = Result.success(Unit)

        override suspend fun unpinMessage(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            messageId: Long
        ) = Result.success(Unit)

        override suspend fun deleteMessage(
            baseUrl: String,
            accessToken: String,
            messageId: Long
        ) = Result.success(true)
    }

    private class FakeChatLocalCache : ChatLocalCache {
        private var cachedConversations: List<ConversationUiModel> = emptyList()
        private val cachedMessages = mutableMapOf<Long, List<MessageUiModel>>()

        override suspend fun saveConversations(conversations: List<ConversationUiModel>) {
            cachedConversations = conversations
        }

        override suspend fun getConversations(): List<ConversationUiModel> = cachedConversations

        override suspend fun saveMessages(conversationId: Long, messages: List<MessageUiModel>) {
            cachedMessages[conversationId] = messages
        }

        override suspend fun getMessages(conversationId: Long): List<MessageUiModel> =
            cachedMessages[conversationId].orEmpty()

        override suspend fun clear() {
            cachedConversations = emptyList()
            cachedMessages.clear()
        }
    }
}
