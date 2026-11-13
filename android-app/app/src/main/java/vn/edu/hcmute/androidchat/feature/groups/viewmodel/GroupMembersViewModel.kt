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
    val errorMessage: String? = null
)

/**
 * Manages group member listing, addition, role changes, and removal (Sprint C, D15).
 */
class GroupMembersViewModel(
    private val apiService: ChatApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupMembersUiState())
    val uiState: StateFlow<GroupMembersUiState> = _uiState.asStateFlow()

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
}
