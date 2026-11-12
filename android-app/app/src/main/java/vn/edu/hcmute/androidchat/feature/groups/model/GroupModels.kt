package vn.edu.hcmute.androidchat.feature.groups.model

enum class GroupRole {
    OWNER,
    ADMIN,
    MEMBER
}

data class GroupMemberUiModel(
    val userId: Long,
    val displayName: String,
    val role: GroupRole = GroupRole.MEMBER
)

data class GroupCreateUiState(
    val groupName: String = "",
    val selectedMemberIds: Set<Long> = emptySet(),
    val availableContacts: List<GroupMemberUiModel> = emptyList(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
) {
    val canCreateGroup: Boolean
        get() = groupName.trim().isNotEmpty() && selectedMemberIds.size >= 2 && !isSubmitting
}
