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

    private class FakeChatApiService : ChatApiService {
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
            Result.success(emptyList<vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel>())

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
