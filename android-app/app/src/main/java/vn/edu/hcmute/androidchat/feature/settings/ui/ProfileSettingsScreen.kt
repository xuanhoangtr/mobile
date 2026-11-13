package vn.edu.hcmute.androidchat.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    uiState: AuthUiState,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onLogout: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    TextButton(onClick = onBackClick) { Text(stringResource(R.string.chat_back)) }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = uiState.authenticatedUser?.user?.email.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = uiState.profileDisplayName,
                onValueChange = onDisplayNameChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                label = { Text(stringResource(R.string.profile_display_name)) },
                singleLine = true,
                enabled = !uiState.isLoading
            )
            OutlinedTextField(
                value = uiState.profileBio,
                onValueChange = onBioChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.profile_bio)) },
                supportingText = { Text("${uiState.profileBio.length}/160") },
                enabled = !uiState.isLoading,
                minLines = 2,
                maxLines = 4
            )
            uiState.validationError?.let { error ->
                Text(
                    text = stringResource(error.toProfileStringResource()),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            uiState.errorMessage?.let { error ->
                Text(
                    text = stringResource(error.toProfileStringResource()),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (uiState.profileSaved) {
                Text(
                    text = stringResource(R.string.profile_saved),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onSaveProfile,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                if (uiState.isLoading) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text(stringResource(R.string.profile_save))
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.profile_logout)) }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.profile_logout_confirm_title)) },
            text = { Text(stringResource(R.string.profile_logout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        onLogout()
                    }
                ) { Text(stringResource(R.string.profile_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.profile_logout_cancel))
                }
            }
        )
    }
}

private fun String.toProfileStringResource(): Int = when (this) {
    "invalid_display_name" -> R.string.auth_error_invalid_display_name
    "bio_too_long" -> R.string.auth_error_bio_too_long
    "invalid_credentials" -> R.string.auth_error_invalid_credentials
    "logout_failed" -> R.string.profile_logout_failed
    "network_unavailable" -> R.string.auth_error_network
    "service_unavailable" -> R.string.auth_error_service_unavailable
    else -> R.string.auth_error_request_failed
}
