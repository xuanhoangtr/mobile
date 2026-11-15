package vn.edu.hcmute.androidchat.feature.groups.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.chat.data.ChatApiService
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupRole

data class GroupMembersUiState(
    val conversationId: Long = 0L,
    val members: List<GroupMemberUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val currentUserId: Long = 0L,
    val availableFriends: List<GroupMemberUiModel> = emptyList(),
    val addMemberQuery: String = "",
    val hasLeftGroup: Boolean = false
) {
    val currentUserRole: GroupRole?
        get() = members.firstOrNull { it.userId == currentUserId }?.role

    val canManageMembers: Boolean
        get() = currentUserRole == GroupRole.OWNER || currentUserRole == GroupRole.ADMIN

    val canChangeRoles: Boolean
        get() = currentUserRole == GroupRole.OWNER

    val addableFriends: List<GroupMemberUiModel>
        get() {
            val existingIds = members.mapTo(hashSetOf()) { it.userId }
            val query = addMemberQuery.trim()
            return availableFriends.filter { friend ->
                friend.userId !in existingIds &&
                    (query.isEmpty() ||
                        friend.displayName.contains(query, ignoreCase = true) ||
                        friend.userId.toString() == query)
            }
        }
}

/**
 * Manages group member listing, addition, role changes, and removal (Sprint C, D15).
 */
class GroupMembersViewModel(
    private val apiService: ChatApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupMembersUiState())
    val uiState: StateFlow<GroupMembersUiState> = _uiState.asStateFlow()

    fun setCurrentUserId(userId: Long) {
        if (userId > 0L) {
            _uiState.update { it.copy(currentUserId = userId) }
        }
    }

    fun setAvailableFriends(friends: List<GroupMemberUiModel>) {
        _uiState.update { it.copy(availableFriends = friends) }
    }

    fun onAddMemberQueryChanged(query: String) {
        _uiState.update { it.copy(addMemberQuery = query) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun loadMembers(baseUrl: String, accessToken: String, conversationId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(conversationId = conversationId, isLoading = true, errorMessage = null) }
            apiService.listGroupMembers(baseUrl, accessToken, conversationId)
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoading = false, members = list, errorMessage = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load members") }
                }
        }
    }

    fun addMembers(baseUrl: String, accessToken: String, conversationId: Long, userIds: List<Long>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiService.addGroupMembers(baseUrl, accessToken, conversationId, userIds)
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoading = false, members = list, errorMessage = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to add members") }
                }
        }
    }

    fun updateRole(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long,
        newRole: GroupRole
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiService.updateGroupMemberRole(baseUrl, accessToken, conversationId, targetUserId, newRole)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            members = state.members.map { if (it.userId == targetUserId) updated else it }
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to update role") }
                }
        }
    }

    fun removeMember(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        targetUserId: Long
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiService.removeGroupMember(baseUrl, accessToken, conversationId, targetUserId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            members = state.members.filterNot { it.userId == targetUserId }
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to remove member") }
                }
        }
    }

    fun leaveGroup(
        baseUrl: String,
        accessToken: String,
        conversationId: Long,
        currentUserId: Long,
        onLeft: () -> Unit = {}
    ) {
        if (currentUserId <= 0L) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiService.removeGroupMember(baseUrl, accessToken, conversationId, currentUserId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            members = state.members.filterNot { it.userId == currentUserId },
                            hasLeftGroup = true
                        )
                    }
                    onLeft()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to leave group") }
                }
        }
    }
}
