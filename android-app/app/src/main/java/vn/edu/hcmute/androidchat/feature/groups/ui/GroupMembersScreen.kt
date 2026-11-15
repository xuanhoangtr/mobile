package vn.edu.hcmute.androidchat.feature.groups.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupRole
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.GroupMembersUiState

/**
 * Full group members management screen: view members & roles, add members from friends or ID,
 * promote/demote ADMIN/MEMBER, remove members, and leave group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(
    uiState: GroupMembersUiState,
    onBackClick: () -> Unit,
    onAddMembersClick: (List<Long>) -> Unit,
    onUpdateRoleClick: (Long, GroupRole) -> Unit,
    onRemoveMemberClick: (Long) -> Unit,
    onLeaveGroupClick: () -> Unit,
    onAddMemberQueryChange: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    modifier: Modifier = Modifier,
    groupTitle: String = "Thành viên nhóm"
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveMember by remember { mutableStateOf<GroupMemberUiModel?>(null) }
    var showLeaveConfirm by rememberSaveable { mutableStateOf(false) }

    val canAddMembers = uiState.canManageMembers || uiState.currentUserId == 0L

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatStitchTokens.CardWhite
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                            tint = ChatStitchTokens.TextPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = groupTitle,
                            color = ChatStitchTokens.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.members.size} thành viên",
                            color = ChatStitchTokens.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    if (canAddMembers) {
                        IconButton(onClick = { showAddSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.PersonAdd,
                                contentDescription = "Thêm thành viên",
                                tint = ChatStitchTokens.BrandBlue
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.currentUserRole != GroupRole.OWNER) {
                Surface(
                    color = ChatStitchTokens.CardWhite,
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showLeaveConfirm = true },
                            enabled = !uiState.isLoading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ChatStitchTokens.AlertRed
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Rời nhóm")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    color = ChatStitchTokens.BrandBlue,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!uiState.errorMessage.isNullOrBlank()) {
                Surface(
                    color = ChatStitchTokens.AlertRed.copy(alpha = 0.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = ChatStitchTokens.AlertRed,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClearError,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Đóng",
                                tint = ChatStitchTokens.AlertRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (uiState.members.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa có thành viên nào.",
                        color = ChatStitchTokens.TextMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.members, key = { it.userId }) { member ->
                        GroupMemberRow(
                            member = member,
                            isCurrentUser = member.userId == uiState.currentUserId,
                            canChangeRole = (uiState.canChangeRoles || uiState.currentUserId == 0L) &&
                                member.userId != uiState.currentUserId &&
                                member.role != GroupRole.OWNER,
                            canRemove = member.userId != uiState.currentUserId &&
                                member.role != GroupRole.OWNER &&
                                (uiState.currentUserRole == GroupRole.OWNER ||
                                    (uiState.currentUserRole == GroupRole.ADMIN && member.role == GroupRole.MEMBER) ||
                                    uiState.currentUserId == 0L),
                            onUpdateRole = { newRole -> onUpdateRoleClick(member.userId, newRole) },
                            onRemove = { confirmRemoveMember = member }
                        )
                        HorizontalDivider(
                            color = ChatStitchTokens.SoftBlueContainer,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddMembersBottomSheet(
            addableFriends = uiState.addableFriends,
            searchQuery = uiState.addMemberQuery,
            onSearchQueryChange = onAddMemberQueryChange,
            onDismiss = { showAddSheet = false },
            onConfirmAdd = { userIds ->
                showAddSheet = false
                if (userIds.isNotEmpty()) {
                    onAddMembersClick(userIds)
                }
            }
        )
    }

    confirmRemoveMember?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemoveMember = null },
            title = { Text("Xóa thành viên?") },
            text = { Text("Bạn có chắc muốn xóa ${target.displayName} khỏi nhóm?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveMember = null
                        onRemoveMemberClick(target.userId)
                    }
                ) {
                    Text("Xóa", color = ChatStitchTokens.AlertRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveMember = null }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Rời nhóm?") },
            text = { Text("Bạn sẽ không còn nhận tin nhắn từ nhóm này nữa.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        onLeaveGroupClick()
                    }
                ) {
                    Text("Rời nhóm", color = ChatStitchTokens.AlertRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMemberUiModel,
    isCurrentUser: Boolean,
    canChangeRole: Boolean,
    canRemove: Boolean,
    onUpdateRole: (GroupRole) -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(color = ChatStitchTokens.CardWhite) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ChatStitchTokens.SoftBlueContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = member.displayName.firstOrNull()?.uppercase() ?: "?",
                    color = ChatStitchTokens.BrandBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCurrentUser) "${member.displayName} (Bạn)" else member.displayName,
                    color = ChatStitchTokens.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                RoleBadge(role = member.role)
            }

            if (canChangeRole || canRemove) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Tùy chọn thành viên",
                            tint = ChatStitchTokens.TextMuted
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (canChangeRole) {
                            if (member.role == GroupRole.MEMBER) {
                                DropdownMenuItem(
                                    text = { Text("Bổ nhiệm Phó nhóm") },
                                    onClick = {
                                        menuExpanded = false
                                        onUpdateRole(GroupRole.ADMIN)
                                    }
                                )
                            } else if (member.role == GroupRole.ADMIN) {
                                DropdownMenuItem(
                                    text = { Text("Hạ xuống Thành viên") },
                                    onClick = {
                                        menuExpanded = false
                                        onUpdateRole(GroupRole.MEMBER)
                                    }
                                )
                            }
                        }
                        if (canRemove) {
                            DropdownMenuItem(
                                text = { Text("Xóa khỏi nhóm", color = ChatStitchTokens.AlertRed) },
                                onClick = {
                                    menuExpanded = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(role: GroupRole) {
    val (label, bgColor, textColor) = when (role) {
        GroupRole.OWNER -> Triple("Trưởng nhóm", ChatStitchTokens.BrandBlue.copy(alpha = 0.12f), ChatStitchTokens.BrandBlue)
        GroupRole.ADMIN -> Triple("Phó nhóm", ChatStitchTokens.OnlineGreen.copy(alpha = 0.12f), ChatStitchTokens.OnlineGreen)
        GroupRole.MEMBER -> Triple("Thành viên", ChatStitchTokens.SoftBlueContainer, ChatStitchTokens.TextSecondary)
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersBottomSheet(
    addableFriends: List<GroupMemberUiModel>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmAdd: (List<Long>) -> Unit
) {
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var manualIdInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Thêm thành viên",
                color = ChatStitchTokens.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Tìm bạn bè theo tên hoặc ID…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualIdInput,
                onValueChange = { input -> manualIdInput = input.filter { it.isDigit() } },
                label = { Text("Hoặc nhập User ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (addableFriends.isEmpty()) {
                Text(
                    text = "Không có bạn bè khả dụng để thêm.",
                    color = ChatStitchTokens.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(addableFriends, key = { it.userId }) { friend ->
                        val checked = friend.userId in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (checked) selectedIds - friend.userId else selectedIds + friend.userId
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedIds = if (checked) selectedIds - friend.userId else selectedIds + friend.userId
                                }
                            )
                            Text(
                                text = friend.displayName,
                                color = ChatStitchTokens.TextPrimary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val manualId = manualIdInput.toLongOrNull()?.takeIf { it > 0L }
            val idsToAdd = (selectedIds + listOfNotNull(manualId)).toList()
            Button(
                onClick = { onConfirmAdd(idsToAdd) },
                enabled = idsToAdd.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Thêm (${idsToAdd.size})")
            }
        }
    }
}
