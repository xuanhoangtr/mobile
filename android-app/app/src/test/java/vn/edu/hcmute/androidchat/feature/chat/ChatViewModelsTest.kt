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
import org.junit.Before
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.data.ChatRealtimeTransport
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
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

    private class FakeChatRealtimeTransport : ChatRealtimeTransport {
        val connectionStateFlow = MutableStateFlow(WsConnectionState.DISCONNECTED)
        val eventsFlow = MutableSharedFlow<ChatServerEvent>(extraBufferCapacity = 16)
        val sentMessages = mutableListOf<String>()

        override val connectionState: StateFlow<WsConnectionState> = connectionStateFlow
        override val events: SharedFlow<ChatServerEvent> = eventsFlow

        override fun connect(wsUrl: String, accessToken: String) = Unit
        override fun disconnect() = Unit

        override fun sendMessage(
            clientMessageId: String,
            conversationId: Long,
            content: String,
            type: MessageType,
            replyToId: Long?
        ): Boolean {
            sentMessages += "$conversationId:$clientMessageId:$content"
            return true
        }

        override fun sendTyping(conversationId: Long, isTyping: Boolean): Boolean = true
        override fun sendReceipt(
            conversationId: Long,
            messageId: Long,
            status: MessageDeliveryStatus
        ): Boolean = true
    }
}
