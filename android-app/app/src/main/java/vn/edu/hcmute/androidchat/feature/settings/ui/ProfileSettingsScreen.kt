package vn.edu.hcmute.androidchat.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.ui.UserAvatar
import vn.edu.hcmute.androidchat.feature.auth.ui.decodeScaledBitmapBytes
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthUiState
import vn.edu.hcmute.androidchat.feature.settings.model.AppLanguage
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    uiState: AuthUiState,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onLogout: () -> Unit,
    onBackClick: () -> Unit,
    themePreference: AppThemePreference,
    onThemePreferenceChanged: (AppThemePreference) -> Unit,
    languagePreference: AppLanguage,
    onLanguagePreferenceChanged: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    avatarBaseUrl: String = "",
    onUploadAvatar: ((ByteArray, String) -> Unit)? = null
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            android.util.Log.i("ProfileAvatar", "Avatar picker returned null URI")
            return@rememberLauncherForActivityResult
        }
        val upload = onUploadAvatar ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                decodeScaledBitmapBytes(context.contentResolver, uri)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                android.util.Log.i("ProfileAvatar", "Uploading avatar (${bytes.size} bytes)")
                upload(bytes, "image/jpeg")
            } else {
                android.util.Log.w("ProfileAvatar", "Decoded avatar bytes null or empty")
            }
        }
    }
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
            modifier = Modifier.fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            val currentUser = uiState.authenticatedUser?.user
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAvatar(
                    avatarUrl = currentUser?.avatarUrl,
                    displayName = uiState.profileDisplayName.ifBlank { currentUser?.displayName.orEmpty() },
                    baseUrl = avatarBaseUrl,
                    size = 88.dp,
                    modifier = if (onUploadAvatar != null && !uiState.isLoading) {
                        Modifier.clickable { avatarPickerLauncher.launch("image/*") }
                    } else {
                        Modifier
                    }
                )
                if (onUploadAvatar != null) {
                    TextButton(
                        onClick = { avatarPickerLauncher.launch("image/*") },
                        enabled = !uiState.isLoading
                    ) {
                        Text(stringResource(R.string.profile_change_avatar))
                    }
                }
            }
            Text(
                text = currentUser?.email.orEmpty(),
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
            Text(
                text = stringResource(R.string.profile_theme_title),
                style = MaterialTheme.typography.titleMedium
            )
            Column(Modifier.selectableGroup()) {
                AppThemePreference.entries.forEach { preference ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = themePreference == preference,
                                onClick = { onThemePreferenceChanged(preference) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themePreference == preference,
                            onClick = null
                        )
                        Text(
                            text = stringResource(preference.toThemeStringResource()),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.profile_language_title),
                style = MaterialTheme.typography.titleMedium
            )
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = languagePreference == language,
                                onClick = { onLanguagePreferenceChanged(language) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(selected = languagePreference == language, onClick = null)
                        Text(
                            text = stringResource(language.toLanguageStringResource()),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
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

private fun AppThemePreference.toThemeStringResource(): Int = when (this) {
    AppThemePreference.SYSTEM -> R.string.profile_theme_system
    AppThemePreference.LIGHT -> R.string.profile_theme_light
    AppThemePreference.DARK -> R.string.profile_theme_dark
}

private fun AppLanguage.toLanguageStringResource(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.profile_language_system
    AppLanguage.VIETNAMESE -> R.string.profile_language_vietnamese
    AppLanguage.ENGLISH -> R.string.profile_language_english
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
