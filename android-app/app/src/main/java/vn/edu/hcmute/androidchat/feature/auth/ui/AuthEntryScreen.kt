package vn.edu.hcmute.androidchat.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthFormMode
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthUiState
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel

/** UI reference: VISUAL_UNVERIFIED for email/password; Stitch currently exposes OTP/login concepts only. */
@Composable
fun AuthEntryScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        if (state.isRestoringSession) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            AuthForm(
                state = state,
                onEmailChanged = viewModel::onEmailChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onUsernameChanged = viewModel::onUsernameChanged,
                onDisplayNameChanged = viewModel::onDisplayNameChanged,
                onModeChanged = viewModel::setMode,
                onSubmit = viewModel::submit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
            )
        }
    }
}

@Composable
private fun AuthForm(
    state: AuthUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onModeChanged: (AuthFormMode) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRegister = state.mode == AuthFormMode.REGISTER

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.auth_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(if (isRegister) R.string.auth_register_subtitle else R.string.auth_login_subtitle),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isRegister) {
                OutlinedButton(onClick = { onModeChanged(AuthFormMode.LOGIN) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auth_mode_login))
                }
                Button(onClick = { onModeChanged(AuthFormMode.REGISTER) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auth_mode_register))
                }
            } else {
                Button(onClick = { onModeChanged(AuthFormMode.LOGIN) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auth_mode_login))
                }
                OutlinedButton(onClick = { onModeChanged(AuthFormMode.REGISTER) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auth_mode_register))
                }
            }
        }

        if (isRegister) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                label = { Text(stringResource(R.string.auth_display_name)) },
                singleLine = true,
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.auth_username)) },
                singleLine = true,
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChanged,
            modifier = Modifier.fillMaxWidth().padding(top = if (isRegister) 12.dp else 4.dp),
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            enabled = !state.isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )

        state.validationError?.let { error ->
            Text(
                text = stringResource(error.toResource()),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        state.errorMessage?.let { error ->
            Text(
                text = stringResource(error.toResource()),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 14.dp),
            enabled = !state.isLoading
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(if (isRegister) R.string.auth_submit_register else R.string.auth_submit_login))
            }
        }

        if (!isRegister) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onModeChanged(AuthFormMode.REGISTER) }, enabled = !state.isLoading) {
                Text(stringResource(R.string.auth_create_account_prompt))
            }
        }
    }
}

private fun String.toResource(): Int = when (this) {
    "invalid_email" -> R.string.auth_error_invalid_email
    "password_too_short" -> R.string.auth_error_password_too_short
    "password_too_long" -> R.string.auth_error_password_too_long
    "invalid_display_name" -> R.string.auth_error_invalid_display_name
    "invalid_username" -> R.string.auth_error_invalid_username
    "invalid_credentials" -> R.string.auth_error_invalid_credentials
    "account_already_exists" -> R.string.auth_error_account_exists
    "network_unavailable" -> R.string.auth_error_network
    "service_unavailable" -> R.string.auth_error_service_unavailable
    else -> R.string.auth_error_request_failed
}
