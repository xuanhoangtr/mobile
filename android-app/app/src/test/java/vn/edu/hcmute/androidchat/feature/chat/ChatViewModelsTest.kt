package vn.edu.hcmute.androidchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel

class ChatViewModelsTest {
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
}
