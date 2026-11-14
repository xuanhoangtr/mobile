package vn.edu.hcmute.androidchat.feature.contacts.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthNetworkException
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.data.ContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequest
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestDirection
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestStatus
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneDiscoverySettings
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneNumberNormalizer

data class ContactsUiState(
    val query: String = "",
    val phone: String = "",
    val users: List<User> = emptyList(),
    val phoneResult: User? = null,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val phoneSearchCompleted: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val friends: List<User> = emptyList(),
    val incomingRequests: List<FriendRequest> = emptyList(),
    val outgoingRequests: List<FriendRequest> = emptyList(),
    val phoneDiscovery: PhoneDiscoverySettings? = null,
    val hasLoadedConnections: Boolean = false,
    val isLoadingConnections: Boolean = false,
    val isUpdatingPhoneDiscovery: Boolean = false,
    val isFriendRequestLoading: Boolean = false,
    val friendRequestInProgressId: String? = null,
    val isSendingFriendRequest: Boolean = false,
    val phoneContactMatches: List<User> = emptyList(),
    val phoneContactsProcessedCount: Int = 0,
    val phoneContactsTotalCount: Int = 0,
    val phoneContactsInvalidCount: Int = 0,
    val hasSyncedPhoneContacts: Boolean = false,
    val isSyncingPhoneContacts: Boolean = false,
    val phoneContactsSyncConsentGranted: Boolean = false,
    val errorMessage: String? = null
)

class ContactsViewModel(
    private val repository: ContactsRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val viewModelScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    fun onQueryChanged(value: String) = _uiState.update {
        it.copy(
            query = value,
            users = emptyList(),
            phoneResult = null,
            phoneSearchCompleted = false,
            nextCursor = null,
            hasMore = false,
            hasSearched = false,
            errorMessage = null
        )
    }

    fun onPhoneChanged(value: String) = _uiState.update {
        it.copy(phone = value, phoneResult = null, phoneSearchCompleted = false, errorMessage = null)
    }

    fun searchUsers() {
        val query = _uiState.value.query.trim()
        if (query.length !in 2..80) {
            _uiState.update { it.copy(errorMessage = "invalid_search_query", hasSearched = false) }
            return
        }
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    users = emptyList(),
                    nextCursor = null,
                    hasMore = false,
                    hasSearched = false,
                    errorMessage = null
                )
            }
            try {
                val page = repository.searchUsers(query)
                _uiState.update {
                    it.copy(
                        users = page.items,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        hasSearched = true,
                        isLoading = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, hasSearched = true, errorMessage = exception.toUiError())
                }
            }
        }
    }

    fun loadNextPage() {
        val snapshot = _uiState.value
        val cursor = snapshot.nextCursor ?: return
        if (!snapshot.hasMore || snapshot.isLoading || snapshot.query.trim().length !in 2..80) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val page = repository.searchUsers(snapshot.query.trim(), cursor)
                _uiState.update {
                    it.copy(
                        users = (it.users + page.items).distinctBy(User::id),
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        isLoading = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = exception.toUiError()) }
            }
        }
    }

    fun findByPhone() {
        val phone = PhoneNumberNormalizer.toE164(_uiState.value.phone)
        if (phone == null) {
            _uiState.update { it.copy(errorMessage = "invalid_phone", phoneSearchCompleted = false) }
            return
        }
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, phoneResult = null, phoneSearchCompleted = false, errorMessage = null) }
            try {
                val user = repository.findUserByPhone(phone)
                _uiState.update {
                    it.copy(phoneResult = user, phoneSearchCompleted = true, isLoading = false)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        phoneSearchCompleted = true,
                        isLoading = false,
                        errorMessage = exception.toUiError()
                    )
                }
            }
        }
    }

    fun loadConnections() {
        if (_uiState.value.isLoadingConnections) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingConnections = true, errorMessage = null) }
            try {
                val friends = repository.loadFriends()
                val incoming = repository.loadFriendRequests(FriendRequestDirection.INCOMING)
                val outgoing = repository.loadFriendRequests(FriendRequestDirection.OUTGOING)
                val phoneDiscovery = repository.getPhoneDiscoverySettings()
                _uiState.update {
                    it.copy(
                        friends = friends,
                        incomingRequests = incoming,
                        outgoingRequests = outgoing,
                        phoneDiscovery = phoneDiscovery,
                        hasLoadedConnections = true,
                        isLoadingConnections = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(isLoadingConnections = false, errorMessage = exception.toUiError()) }
            }
        }
    }

    fun sendFriendRequest(targetUserId: Long) {
        if (targetUserId <= 0L) {
            _uiState.update { it.copy(errorMessage = "invalid_friend_request_target") }
            return
        }
        if (_uiState.value.isSendingFriendRequest) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingFriendRequest = true, errorMessage = null) }
            try {
                val request = repository.sendFriendRequest(targetUserId)
                _uiState.update { current ->
                    current.copy(
                        outgoingRequests = current.outgoingRequests.upsert(request),
                        isSendingFriendRequest = false,
                        hasLoadedConnections = true
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isSendingFriendRequest = false, errorMessage = exception.toUiError())
                }
            }
        }
    }

    fun acceptRequest(requestId: String) {
        mutateFriendRequest(requestId, action = { repository.acceptRequest(requestId) }) { current, updated ->
            current.copy(
                incomingRequests = current.incomingRequests.filterNot { it.id == requestId },
                friends = if (updated?.status == FriendRequestStatus.ACCEPTED) {
                    current.friends.upsert(updated.sender)
                } else current.friends
            )
        }
    }

    fun rejectRequest(requestId: String) {
        mutateFriendRequest(requestId, action = { repository.rejectRequest(requestId) }) { current, _ ->
            current.copy(incomingRequests = current.incomingRequests.filterNot { it.id == requestId })
        }
    }

    fun cancelRequest(requestId: String) {
        mutateFriendRequest(requestId, action = {
            repository.cancelRequest(requestId)
            null
        }) { current, _ ->
            current.copy(outgoingRequests = current.outgoingRequests.filterNot { it.id == requestId })
        }
    }

    fun setPhoneDiscoverable(discoverable: Boolean) {
        val settings = _uiState.value.phoneDiscovery
        if (discoverable && settings?.phoneVerified == false) {
            _uiState.update { it.copy(errorMessage = "phone_not_verified") }
            return
        }
        if (_uiState.value.isUpdatingPhoneDiscovery) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingPhoneDiscovery = true, errorMessage = null) }
            try {
                val updated = repository.setPhoneDiscoverable(discoverable)
                _uiState.update {
                    it.copy(phoneDiscovery = updated, isUpdatingPhoneDiscovery = false)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isUpdatingPhoneDiscovery = false, errorMessage = exception.toUiError())
                }
            }
        }
    }

    /** Grants one sync attempt only when both the disclosure and Android permission were accepted. */
    fun grantPhoneContactsSyncConsent(inAppConsentGranted: Boolean, contactsPermissionGranted: Boolean) {
        if (!inAppConsentGranted || !contactsPermissionGranted) {
            _uiState.update {
                it.copy(
                    phoneContactsSyncConsentGranted = false,
                    errorMessage = if (!contactsPermissionGranted) {
                        "contacts_permission_required"
                    } else {
                        "contacts_consent_required"
                    }
                )
            }
            return
        }
        _uiState.update { it.copy(phoneContactsSyncConsentGranted = true, errorMessage = null) }
    }

    fun revokePhoneContactsSyncConsent() = _uiState.update {
        it.copy(phoneContactsSyncConsentGranted = false)
    }

    /** Matches contacts against the existing by-phone API after consuming a one-shot consent grant. */
    fun syncPhoneContacts(phoneNumbers: List<String>) {
        val snapshot = _uiState.value
        if (!snapshot.phoneContactsSyncConsentGranted) {
            _uiState.update { it.copy(errorMessage = "contacts_consent_required") }
            return
        }
        if (snapshot.isSyncingPhoneContacts) return

        val validNumbers = phoneNumbers.mapNotNull(PhoneNumberNormalizer::toE164)
        val uniqueNumbers = validNumbers.distinct()
        val invalidCount = phoneNumbers.size - validNumbers.size
        _uiState.update {
            it.copy(
                phoneContactsSyncConsentGranted = false,
                isSyncingPhoneContacts = uniqueNumbers.isNotEmpty(),
                phoneContactMatches = emptyList(),
                phoneContactsProcessedCount = 0,
                phoneContactsTotalCount = uniqueNumbers.size,
                phoneContactsInvalidCount = invalidCount,
                hasSyncedPhoneContacts = false,
                errorMessage = null
            )
        }

        if (uniqueNumbers.isEmpty()) {
            _uiState.update {
                it.copy(
                    hasSyncedPhoneContacts = true,
                    errorMessage = if (phoneNumbers.isEmpty()) null else "contacts_no_valid_phone_numbers"
                )
            }
            return
        }

        viewModelScope.launch {
            val matches = mutableListOf<User>()
            var processedCount = 0
            try {
                for (phone in uniqueNumbers) {
                    try {
                        matches += repository.findUserByPhone(phone)
                    } catch (exception: AuthHttpException) {
                        if (exception.statusCode != 404) throw exception
                    }
                    processedCount += 1
                    val uniqueMatches = matches.distinctBy(User::id)
                    _uiState.update {
                        it.copy(
                            phoneContactMatches = uniqueMatches,
                            phoneContactsProcessedCount = processedCount
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        phoneContactMatches = matches.distinctBy(User::id),
                        hasSyncedPhoneContacts = true,
                        isSyncingPhoneContacts = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        phoneContactMatches = matches.distinctBy(User::id),
                        hasSyncedPhoneContacts = true,
                        isSyncingPhoneContacts = false,
                        errorMessage = exception.toUiError()
                    )
                }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.cancel()
        super.onCleared()
    }

    private fun mutateFriendRequest(
        requestId: String,
        action: suspend () -> FriendRequest?,
        onSuccess: (ContactsUiState, FriendRequest?) -> ContactsUiState
    ) {
        if (requestId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "invalid_friend_request_id") }
            return
        }
        if (_uiState.value.isFriendRequestLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFriendRequestLoading = true,
                    friendRequestInProgressId = requestId,
                    errorMessage = null
                )
            }
            try {
                val updated = action()
                _uiState.update { current ->
                    onSuccess(current, updated).copy(
                        isFriendRequestLoading = false,
                        friendRequestInProgressId = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isFriendRequestLoading = false,
                        friendRequestInProgressId = null,
                        errorMessage = exception.toUiError()
                    )
                }
            }
        }
    }

    private fun Exception.toUiError(): String = when (this) {
        is AuthHttpException -> when (statusCode) {
            400 -> "invalid_phone"
            401 -> "session_expired"
            403 -> "friend_request_forbidden"
            404 -> "user_not_found"
            409 -> "friend_request_conflict"
            503 -> "service_unavailable"
            else -> "request_failed"
        }
        is AuthNetworkException -> "network_unavailable"
        else -> "request_failed"
    }

    private fun List<FriendRequest>.upsert(request: FriendRequest): List<FriendRequest> =
        filterNot { it.id == request.id } + request

    private fun List<User>.upsert(user: User): List<User> =
        filterNot { it.id == user.id } + user
}
