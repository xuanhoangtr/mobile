package vn.edu.hcmute.androidchat.feature.groups.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens
import vn.edu.hcmute.androidchat.feature.groups.model.GroupCreateUiState

/**
 * Group creation skeleton screen (Sprint C, D11-D15).
 * Note: VISUAL_UNVERIFIED — uses temporary Material 3 form layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    uiState: GroupCreateUiState,
    onBackClick: () -> Unit,
    onGroupNameChange: (String) -> Unit,
    onToggleMember: (Long) -> Unit,
    onSubmitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_create_title)) },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("←", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.groupName,
                onValueChange = onGroupNameChange,
                label = { Text(stringResource(R.string.group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.group_select_members_hint),
                color = ChatStitchTokens.TextMuted,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.availableContacts, key = { it.userId }) { contact ->
                    val checked = contact.userId in uiState.selectedMemberIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMember(contact.userId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleMember(contact.userId) }
                        )
                        Text(
                            text = contact.displayName,
                            color = ChatStitchTokens.TextPrimary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            Button(
                onClick = onSubmitClick,
                enabled = uiState.canCreateGroup,
                colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.group_create_action))
            }
        }
    }
}
