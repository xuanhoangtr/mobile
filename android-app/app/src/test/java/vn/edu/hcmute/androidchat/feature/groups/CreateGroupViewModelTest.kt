package vn.edu.hcmute.androidchat.feature.groups

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.chat.data.ChatApiService
import vn.edu.hcmute.androidchat.feature.chat.data.CursorPageResult
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupRole
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.GroupMembersViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupMain() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun groupCreationRequiresNonBlankNameAndAtLeastTwoSelectedPeers() {
        val vm = CreateGroupViewModel()
        assertFalse(vm.uiState.value.canCreateGroup)

        vm.onGroupNameChanged("Nhom Android")
        vm.toggleMemberSelection(1L)
        assertFalse(vm.uiState.value.canCreateGroup)

        vm.toggleMemberSelection(2L)
        assertTrue(vm.uiState.value.canCreateGroup)

        vm.toggleMemberSelection(1L)
        assertFalse(vm.uiState.value.canCreateGroup)
    }

    @Test
    fun submitCreateGroupCallsApiAndReturnsGroupConversation() = runTest {
        val fakeApi = FakeGroupChatApiService()
        val vm = CreateGroupViewModel(apiService = fakeApi)
        vm.onGroupNameChanged("UTE Mobile")
        vm.toggleMemberSelection(2L)
        vm.toggleMemberSelection(3L)

        var created: ConversationUiModel? = null
        vm.submitCreateGroup(baseUrl = "http://localhost:8080", accessToken = "tok") {
            created = it
        }

        assertEquals(ConversationType.GROUP, created?.type)
        assertEquals("UTE Mobile", created?.title)
        assertEquals(listOf(2L, 3L), fakeApi.lastMemberIds)
    }

    @Test
    fun groupMembersViewModelLoadsAddsUpdatesRoleAndRemovesMember() = runTest {
        val fakeApi = FakeGroupChatApiService()
        val vm = GroupMembersViewModel(apiService = fakeApi)

        vm.loadMembers("http://localhost:8080", "tok", conversationId = 500L)
        assertEquals(3, vm.uiState.value.members.size)

        vm.addMembers("http://localhost:8080", "tok", conversationId = 500L, userIds = listOf(4L))
        assertEquals(4, vm.uiState.value.members.size)

        vm.updateRole("http://localhost:8080", "tok", conversationId = 500L, targetUserId = 2L, newRole = GroupRole.ADMIN)
        assertEquals(GroupRole.ADMIN, vm.uiState.value.members.first { it.userId == 2L }.role)

        vm.removeMember("http://localhost:8080", "tok", conversationId = 500L, targetUserId = 3L)
        assertFalse(vm.uiState.value.members.any { it.userId == 3L })
    }

    private class FakeGroupChatApiService : ChatApiService {
        var lastMemberIds: List<Long> = emptyList()
        private val members = mutableListOf(
            GroupMemberUiModel(1L, "Owner", GroupRole.OWNER),
            GroupMemberUiModel(2L, "Peer 2", GroupRole.MEMBER),
            GroupMemberUiModel(3L, "Peer 3", GroupRole.MEMBER)
        )

        override suspend fun listConversations(baseUrl: String, accessToken: String, cursor: String?, limit: Int) =
            Result.success(CursorPageResult<ConversationUiModel>(emptyList(), null, false))

        override suspend fun createOrGetDirectConversation(baseUrl: String, accessToken: String, targetUserId: Long) =
            Result.failure<ConversationUiModel>(UnsupportedOperationException())

        override suspend fun listMessages(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            currentUserId: Long,
            cursor: String?,
            limit: Int
        ) = Result.success(CursorPageResult<MessageUiModel>(emptyList(), null, false))

        override suspend fun createGroupConversation(
            baseUrl: String,
            accessToken: String,
            name: String,
            memberIds: List<Long>
        ): Result<ConversationUiModel> {
            lastMemberIds = memberIds
            return Result.success(
                ConversationUiModel(
                    id = 500L,
                    type = ConversationType.GROUP,
                    title = name,
                    lastMessagePreview = null,
                    lastMessageTime = "2026-11-13T11:30:00Z"
                )
            )
        }

        override suspend fun listGroupMembers(baseUrl: String, accessToken: String, conversationId: Long) =
            Result.success(members.toList())

        override suspend fun addGroupMembers(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            userIds: List<Long>
        ): Result<List<GroupMemberUiModel>> {
            userIds.forEach { uid ->
                if (members.none { it.userId == uid }) {
                    members.add(GroupMemberUiModel(uid, "User #$uid", GroupRole.MEMBER))
                }
            }
            return Result.success(members.toList())
        }

        override suspend fun updateGroupMemberRole(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            targetUserId: Long,
            role: GroupRole
        ): Result<GroupMemberUiModel> {
            val idx = members.indexOfFirst { it.userId == targetUserId }
            val updated = members[idx].copy(role = role)
            members[idx] = updated
            return Result.success(updated)
        }

        override suspend fun removeGroupMember(
            baseUrl: String,
            accessToken: String,
            conversationId: Long,
            targetUserId: Long
        ): Result<Unit> {
            members.removeAll { it.userId == targetUserId }
            return Result.success(Unit)
        }

        override suspend fun editMessage(baseUrl: String, accessToken: String, messageId: Long, currentUserId: Long, content: String) =
            Result.failure<MessageUiModel>(UnsupportedOperationException())

        override suspend fun recallMessage(baseUrl: String, accessToken: String, messageId: Long, currentUserId: Long) =
            Result.failure<MessageUiModel>(UnsupportedOperationException())

        override suspend fun forwardMessage(
            baseUrl: String,
            accessToken: String,
            sourceMessageId: Long,
            targetConversationId: Long,
            clientMessageId: String,
            currentUserId: Long
        ) = Result.failure<MessageUiModel>(UnsupportedOperationException())

        override suspend fun toggleReaction(baseUrl: String, accessToken: String, messageId: Long, emoji: String) =
            Result.success(emptyMap<String, Int>())
    }
}
