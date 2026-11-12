package vn.edu.hcmute.androidchat.feature.groups.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.edu.hcmute.androidchat.feature.groups.model.GroupCreateUiState
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel

/**
 * Manages group creation UI state (Sprint C, D11-D15).
 * Requires at least 2 selected peers (+ creator = 3 members minimum per plan DoD).
 */
class CreateGroupViewModel : ViewModel() {
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
}
