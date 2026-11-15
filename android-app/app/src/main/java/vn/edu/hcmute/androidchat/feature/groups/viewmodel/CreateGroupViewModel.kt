package vn.edu.hcmute.androidchat.feature.groups.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.chat.data.ChatApiService
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupCreateUiState
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel

/**
 * Manages group creation UI state (Sprint C, D11-D15).
 * Requires at least 2 selected peers (+ creator = 3 members minimum per plan DoD).
 */
class CreateGroupViewModel(
    private val apiService: ChatApiService? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupCreateUiState())
    val uiState: StateFlow<GroupCreateUiState> = _uiState.asStateFlow()

    fun onGroupNameChanged(name: String) {
        _uiState.update { it.copy(groupName = name, errorMessage = null) }
    }

    fun setContacts(contacts: List<GroupMemberUiModel>) {
        _uiState.update { it.copy(availableContacts = contacts) }
    }

    fun toggleMemberSelection(userId: Long) {
        _uiState.update { state ->
            val updated = if (userId in state.selectedMemberIds) {
                state.selectedMemberIds - userId
            } else {
                state.selectedMemberIds + userId
            }
            state.copy(selectedMemberIds = updated, errorMessage = null)
        }
    }

    fun submitCreateGroup(
        baseUrl: String,
        accessToken: String,
        onCreated: (ConversationUiModel) -> Unit
    ) {
        val current = _uiState.value
        if (!current.canCreateGroup) {
            _uiState.update { it.copy(errorMessage = "Group name and at least 2 peers are required") }
            return
        }
        val service = apiService ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            service.createGroupConversation(
                baseUrl = baseUrl,
                accessToken = accessToken,
                name = current.groupName.trim(),
                memberIds = current.selectedMemberIds.toList()
            ).onSuccess { conversation ->
                _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                onCreated(conversation)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = err.message ?: "Failed to create group")
                }
            }
        }
    }
}
