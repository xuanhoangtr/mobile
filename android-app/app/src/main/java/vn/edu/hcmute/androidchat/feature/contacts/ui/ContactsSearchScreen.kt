package vn.edu.hcmute.androidchat.feature.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsUiState
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsSearchScreen(
    viewModel: ContactsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_search_title)) },
                navigationIcon = {
                    TextButton(onClick = onBackClick) { Text(stringResource(R.string.chat_back)) }
                }
            )
        }
    ) { insets ->
        ContactsSearchContent(
            state = state,
            onQueryChanged = viewModel::onQueryChanged,
            onSearchClick = viewModel::searchUsers,
            onPhoneChanged = viewModel::onPhoneChanged,
            onPhoneSearchClick = viewModel::findByPhone,
            onLoadMoreClick = viewModel::loadNextPage,
            modifier = Modifier.fillMaxSize().padding(insets)
        )
    }
}

@Composable
private fun ContactsSearchContent(
    state: ContactsUiState,
    onQueryChanged: (String) -> Unit,
    onSearchClick: () -> Unit,
    onPhoneChanged: (String) -> Unit,
    onPhoneSearchClick: () -> Unit,
    onLoadMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.contacts_name_section), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.contacts_name_hint)) },
            singleLine = true,
            enabled = !state.isLoading
        )
        Button(
            onClick = onSearchClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text(stringResource(R.string.contacts_search_action)) }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.contacts_phone_section), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.phone,
            onValueChange = onPhoneChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.contacts_phone_hint)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        OutlinedButton(
            onClick = onPhoneSearchClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text(stringResource(R.string.contacts_phone_search_action)) }

        state.errorMessage?.let { error ->
            Text(
                text = stringResource(error.toContactsStringResource()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }

        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }

        state.phoneResult?.let { user ->
            UserRow(user = user, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.phoneSearchCompleted && state.phoneResult == null && state.errorMessage == null) {
            Text(stringResource(R.string.contacts_user_not_found), modifier = Modifier.padding(top = 12.dp))
        }
        if (state.hasSearched && state.users.isEmpty() && state.errorMessage == null) {
            Text(stringResource(R.string.contacts_no_results), modifier = Modifier.padding(top = 12.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.users, key = User::id) { user -> UserRow(user) }
            if (state.hasMore) {
                item {
                    TextButton(
                        onClick = onLoadMoreClick,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.contacts_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: User, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(
                    text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Column {
            Text(user.displayName, fontWeight = FontWeight.SemiBold)
            Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun String.toContactsStringResource(): Int = when (this) {
    "invalid_search_query" -> R.string.contacts_error_invalid_query
    "invalid_phone" -> R.string.contacts_error_invalid_phone
    "user_not_found" -> R.string.contacts_user_not_found
    "session_expired" -> R.string.auth_error_invalid_credentials
    "network_unavailable" -> R.string.auth_error_network
    "service_unavailable" -> R.string.auth_error_service_unavailable
    else -> R.string.auth_error_request_failed
}
