package vn.edu.hcmute.androidchat.feature.contacts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequest
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsUiState
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel
import vn.edu.hcmute.androidchat.feature.contacts.sync.ContactPhoneNumberReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsSearchScreen(
    viewModel: ContactsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showPhoneSyncConsent by rememberSaveable { mutableStateOf(false) }
    var pendingPhoneSyncConsent by rememberSaveable { mutableStateOf(false) }
    var isReadingPhoneNumbers by remember { mutableStateOf(false) }
    var phoneReadError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.loadConnections()
    }

    val readAndSyncPhoneNumbers = {
        phoneReadError = null
        isReadingPhoneNumbers = true
        coroutineScope.launch {
            try {
                val numbers = ContactPhoneNumberReader.readPhoneNumbers(context)
                viewModel.syncPhoneContacts(numbers)
            } catch (exception: CancellationException) {
                viewModel.revokePhoneContactsSyncConsent()
                throw exception
            } catch (_: SecurityException) {
                viewModel.revokePhoneContactsSyncConsent()
                phoneReadError = "permission_denied"
            } catch (_: Exception) {
                viewModel.revokePhoneContactsSyncConsent()
                phoneReadError = "read_failed"
            } finally {
                isReadingPhoneNumbers = false
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingPhoneSyncConsent) {
            viewModel.grantPhoneContactsSyncConsent(
                inAppConsentGranted = true,
                contactsPermissionGranted = true
            )
            pendingPhoneSyncConsent = false
            readAndSyncPhoneNumbers()
        } else {
            pendingPhoneSyncConsent = false
            viewModel.revokePhoneContactsSyncConsent()
            phoneReadError = "permission_denied"
        }
    }

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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SearchFields(
                    state = state,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearchClick = viewModel::searchUsers,
                    onPhoneChanged = viewModel::onPhoneChanged,
                    onPhoneSearchClick = viewModel::findByPhone
                )
            }

            state.errorMessage?.let { error ->
                item {
                    Text(
                        text = stringResource(error.toContactsStringResource()),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            state.connectionsLoadErrorMessage?.let { error ->
                item {
                    Text(text = stringResource(error.toContactsStringResource()), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::loadConnections) {
                        Text(stringResource(R.string.contacts_retry_connections))
                    }
                }
            }
            phoneReadError?.let { error ->
                item {
                    Text(
                        text = stringResource(error.toPhoneReadStringResource()),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (
                state.isLoading || state.isLoadingConnections || state.isSyncingPhoneContacts ||
                state.isUpdatingPhoneDiscovery || state.isFriendRequestLoading ||
                state.isSendingFriendRequest || state.blockedUserInProgressId != null || isReadingPhoneNumbers
            ) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            if (state.phoneSearchCompleted) {
                item { SectionTitle(stringResource(R.string.contacts_phone_results)) }
                val phoneResult = state.phoneResult
                if (phoneResult == null && state.errorMessage == null) {
                    item { Text(stringResource(R.string.contacts_user_not_found)) }
                } else if (phoneResult != null) {
                    item {
                        ContactUserRow(
                            user = phoneResult,
                            state = state,
                            actionLabel = stringResource(phoneResult.friendActionLabel(state)),
                            actionEnabled = phoneResult.canSendFriendRequest(state) &&
                                !state.isFriendRequestLoading && !state.isSendingFriendRequest,
                            onAction = { viewModel.sendFriendRequest(phoneResult.id) },
                            onBlock = viewModel::blockUser,
                            onUnblock = viewModel::unblockUser
                        )
                    }
                }
            }

            if (state.hasSearched) {
                item { SectionTitle(stringResource(R.string.contacts_search_results)) }
                if (state.users.isEmpty() && state.errorMessage == null) {
                    item { Text(stringResource(R.string.contacts_no_results)) }
                }
                items(state.users, key = { "search-${it.id}" }) { user ->
                    ContactUserRow(
                        user = user,
                        state = state,
                        actionLabel = stringResource(user.friendActionLabel(state)),
                        actionEnabled = user.canSendFriendRequest(state) &&
                            !state.isFriendRequestLoading && !state.isSendingFriendRequest,
                        onAction = { viewModel.sendFriendRequest(user.id) },
                        onBlock = viewModel::blockUser,
                        onUnblock = viewModel::unblockUser
                    )
                }
                if (state.hasMore) {
                    item {
                        TextButton(
                            onClick = viewModel::loadNextPage,
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.contacts_load_more)) }
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionTitle(stringResource(R.string.contacts_phone_discovery_title)) }
            val discovery = state.phoneDiscovery
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.contacts_phone_discovery_label))
                        Text(
                            text = stringResource(
                                if (discovery?.phoneVerified == true) {
                                    R.string.contacts_phone_verified
                                } else {
                                    R.string.contacts_phone_not_verified
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = discovery?.discoverable == true,
                        onCheckedChange = viewModel::setPhoneDiscoverable,
                        enabled = discovery?.phoneVerified == true && !state.isUpdatingPhoneDiscovery
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.contacts_phone_discovery_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        phoneReadError = null
                        showPhoneSyncConsent = true
                    },
                    enabled = !state.isLoading && !state.isSyncingPhoneContacts &&
                        !state.phoneContactsSyncConsentGranted && !isReadingPhoneNumbers,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (isReadingPhoneNumbers) R.string.contacts_syncing_phones
                            else R.string.contacts_sync_phones
                        )
                    )
                }
            }

            if (state.hasSyncedPhoneContacts) {
                item {
                    Text(
                        stringResource(
                            R.string.contacts_phone_sync_status,
                            state.phoneContactsProcessedCount,
                            state.phoneContactsInvalidCount,
                            state.phoneContactMatches.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.isSyncingPhoneContacts) {
                item {
                    Text(
                        stringResource(
                            R.string.contacts_phone_sync_progress,
                            state.phoneContactsProcessedCount,
                            state.phoneContactsTotalCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.phoneContactMatches.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.contacts_phone_matches)) }
                items(state.phoneContactMatches, key = { "match-${it.id}" }) { user ->
                    ContactUserRow(
                        user = user,
                        state = state,
                        actionLabel = stringResource(user.friendActionLabel(state)),
                        actionEnabled = user.canSendFriendRequest(state) &&
                            !state.isFriendRequestLoading && !state.isSendingFriendRequest,
                        onAction = { viewModel.sendFriendRequest(user.id) },
                        onBlock = viewModel::blockUser,
                        onUnblock = viewModel::unblockUser
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionTitle(stringResource(R.string.contacts_incoming_requests)) }
            if (state.hasLoadedConnections && state.incomingRequests.isEmpty()) {
                item { Text(stringResource(R.string.contacts_no_incoming_requests)) }
            }
            items(state.incomingRequests, key = { "incoming-${it.id}" }) { request ->
                FriendRequestRow(
                    user = request.sender,
                    actionLabel = stringResource(R.string.contacts_accept_request),
                    secondaryActionLabel = stringResource(R.string.contacts_reject_request),
                    actionsEnabled = !state.isFriendRequestLoading,
                    onAction = { viewModel.acceptRequest(request.id) },
                    onSecondaryAction = { viewModel.rejectRequest(request.id) }
                )
            }

            item { SectionTitle(stringResource(R.string.contacts_outgoing_requests)) }
            if (state.hasLoadedConnections && state.outgoingRequests.isEmpty()) {
                item { Text(stringResource(R.string.contacts_no_outgoing_requests)) }
            }
            items(state.outgoingRequests, key = { "outgoing-${it.id}" }) { request ->
                FriendRequestRow(
                    user = request.recipient,
                    actionLabel = stringResource(R.string.contacts_cancel_request),
                    actionsEnabled = !state.isFriendRequestLoading,
                    onAction = { viewModel.cancelRequest(request.id) }
                )
            }

            item { SectionTitle(stringResource(R.string.contacts_friends)) }
            if (state.hasLoadedConnections && state.friends.isEmpty()) {
                item { Text(stringResource(R.string.contacts_no_friends)) }
            }
            items(state.friends, key = { "friend-${it.id}" }) { user ->
                ContactUserRow(
                    user = user,
                    state = state,
                    onBlock = viewModel::blockUser,
                    onUnblock = viewModel::unblockUser
                )
            }

            item { SectionTitle(stringResource(R.string.contacts_blocked_users)) }
            if (state.hasLoadedConnections && state.blockedUsers.isEmpty()) {
                item { Text(stringResource(R.string.contacts_no_blocked_users)) }
            }
            items(state.blockedUsers, key = { "blocked-${it.id}" }) { user ->
                UserRow(
                    user = user,
                    secondaryActionLabel = stringResource(R.string.contacts_unblock_user),
                    secondaryActionEnabled = state.blockedUserInProgressId == null,
                    onSecondaryAction = { viewModel.unblockUser(user.id) }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showPhoneSyncConsent) {
        AlertDialog(
            onDismissRequest = { showPhoneSyncConsent = false },
            title = { Text(stringResource(R.string.contacts_sync_consent_title)) },
            text = { Text(stringResource(R.string.contacts_sync_consent_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPhoneSyncConsent = false
                        pendingPhoneSyncConsent = true
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.grantPhoneContactsSyncConsent(
                                inAppConsentGranted = pendingPhoneSyncConsent,
                                contactsPermissionGranted = true
                            )
                            pendingPhoneSyncConsent = false
                            readAndSyncPhoneNumbers()
                        } else {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }
                ) { Text(stringResource(R.string.contacts_sync_consent_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPhoneSyncConsent = false }) {
                    Text(stringResource(R.string.contacts_sync_consent_cancel))
                }
            }
        )
    }
}

@Composable
private fun SearchFields(
    state: ContactsUiState,
    onQueryChanged: (String) -> Unit,
    onSearchClick: () -> Unit,
    onPhoneChanged: (String) -> Unit,
    onPhoneSearchClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.contacts_name_section))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contacts_name_hint)) },
            singleLine = true,
            enabled = !state.isLoading
        )
        Button(
            onClick = onSearchClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.contacts_search_action)) }

        Spacer(Modifier.height(4.dp))
        SectionTitle(stringResource(R.string.contacts_phone_section))
        OutlinedTextField(
            value = state.phone,
            onValueChange = onPhoneChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contacts_phone_hint)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        OutlinedButton(
            onClick = onPhoneSearchClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.contacts_phone_search_action)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun UserRow(
    user: User,
    actionLabel: String? = null,
    actionEnabled: Boolean = false,
    onAction: () -> Unit = {},
    secondaryActionLabel: String? = null,
    secondaryActionEnabled: Boolean = false,
    onSecondaryAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
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
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, fontWeight = FontWeight.SemiBold)
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null || secondaryActionLabel != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (actionLabel != null) {
                    TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
                }
                if (secondaryActionLabel != null) {
                    TextButton(onClick = onSecondaryAction, enabled = secondaryActionEnabled) {
                        Text(secondaryActionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactUserRow(
    user: User,
    state: ContactsUiState,
    actionLabel: String? = null,
    actionEnabled: Boolean = false,
    onAction: () -> Unit = {},
    onBlock: (User) -> Unit,
    onUnblock: (Long) -> Unit
) {
    val isBlocked = state.blockedUsers.any { it.id == user.id }
    UserRow(
        user = user,
        actionLabel = actionLabel?.takeUnless { isBlocked },
        actionEnabled = actionEnabled && !isBlocked && state.blockedUserInProgressId == null,
        onAction = onAction,
        secondaryActionLabel = stringResource(
            if (isBlocked) R.string.contacts_unblock_user else R.string.contacts_block_user
        ),
        secondaryActionEnabled = state.blockedUserInProgressId == null &&
            !state.isFriendRequestLoading && !state.isSendingFriendRequest,
        onSecondaryAction = {
            if (isBlocked) onUnblock(user.id) else onBlock(user)
        }
    )
}

@Composable
private fun FriendRequestRow(
    user: User,
    actionLabel: String,
    secondaryActionLabel: String? = null,
    actionsEnabled: Boolean,
    onAction: () -> Unit,
    onSecondaryAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, fontWeight = FontWeight.SemiBold)
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (secondaryActionLabel != null) {
            TextButton(onClick = onSecondaryAction, enabled = actionsEnabled) {
                Text(secondaryActionLabel)
            }
        }
        TextButton(onClick = onAction, enabled = actionsEnabled) { Text(actionLabel) }
    }
}

private fun User.canSendFriendRequest(state: ContactsUiState): Boolean {
    return state.friends.none { it.id == id } &&
        state.blockedUsers.none { it.id == id } &&
        state.outgoingRequests.none { it.recipient.id == id } &&
        state.incomingRequests.none { it.sender.id == id }
}

private fun User.friendActionLabel(state: ContactsUiState): Int = when {
    state.friends.any { it.id == id } -> R.string.contacts_already_friends
    state.incomingRequests.any { it.sender.id == id } -> R.string.contacts_request_received
    state.outgoingRequests.any { it.recipient.id == id } -> R.string.contacts_request_pending
    else -> R.string.contacts_add_friend
}

private fun String.toContactsStringResource(): Int = when (this) {
    "invalid_search_query" -> R.string.contacts_error_invalid_query
    "invalid_phone" -> R.string.contacts_error_invalid_phone
    "user_not_found" -> R.string.contacts_user_not_found
    "session_expired" -> R.string.auth_error_invalid_credentials
    "network_unavailable" -> R.string.auth_error_network
    "service_unavailable" -> R.string.auth_error_service_unavailable
    "contacts_consent_required" -> R.string.contacts_error_consent_required
    "contacts_permission_required" -> R.string.contacts_error_permission_required
    "contacts_no_valid_phone_numbers" -> R.string.contacts_error_no_valid_phone_numbers
    "contacts_sync_failed" -> R.string.contacts_error_sync_failed
    "phone_not_verified" -> R.string.contacts_error_phone_not_verified
    "friend_request_forbidden" -> R.string.contacts_error_friend_request_forbidden
    "friend_request_conflict" -> R.string.contacts_error_friend_request_conflict
    "invalid_friend_request_target" -> R.string.contacts_error_invalid_friend_request_target
    "invalid_friend_request_id" -> R.string.contacts_error_invalid_friend_request_id
    "invalid_block_target" -> R.string.contacts_error_block_failed
    "contacts_block_conflict" -> R.string.contacts_error_block_conflict
    "contacts_block_failed" -> R.string.contacts_error_block_failed
    else -> R.string.auth_error_request_failed
}

private fun String.toPhoneReadStringResource(): Int = when (this) {
    "permission_denied" -> R.string.contacts_permission_denied
    else -> R.string.contacts_read_failed
}
