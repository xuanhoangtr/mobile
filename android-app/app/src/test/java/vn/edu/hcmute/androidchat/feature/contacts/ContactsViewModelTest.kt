package vn.edu.hcmute.androidchat.feature.contacts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.data.ContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequest
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestDirection
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestStatus
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneDiscoverySettings
import vn.edu.hcmute.androidchat.feature.contacts.model.UserSearchPage
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel

class ContactsViewModelTest {
    @Test
    fun invalidSearchDoesNotCallRepositoryAndNoMatchesAreRepresented() {
        val repository = FakeContactsRepository()
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.onQueryChanged("a")
        viewModel.searchUsers()
        assertEquals(0, repository.searchCalls)
        assertEquals("invalid_search_query", viewModel.uiState.value.errorMessage)

        repository.searchResult = UserSearchPage(emptyList(), null, false)
        viewModel.onQueryChanged("nobody")
        viewModel.searchUsers()
        assertTrue(viewModel.uiState.value.hasSearched)
        assertTrue(viewModel.uiState.value.users.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun searchAppendsNextCursorPageWithoutDuplicates() {
        val first = user(2L, "huy_two", "Huy Two")
        val second = user(3L, "huy_three", "Huy Three")
        val repository = FakeContactsRepository().apply {
            pages += UserSearchPage(listOf(first), "cursor-2", true)
            pages += UserSearchPage(listOf(first, second), null, false)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.onQueryChanged("huy")

        viewModel.searchUsers()
        viewModel.loadNextPage()

        assertEquals(listOf(first, second), viewModel.uiState.value.users)
        assertFalse(viewModel.uiState.value.hasMore)
        assertEquals(listOf(null, "cursor-2"), repository.cursors)
    }

    @Test
    fun loadConnectionsExposesFriendsRequestsAndPhoneDiscovery() {
        val friend = user(3L, "friend", "Friend")
        val incoming = request("in-1", sender = friend)
        val outgoing = request("out-1", sender = user(1L, "me", "Me"), recipient = friend)
        val repository = FakeContactsRepository().apply {
            friends = listOf(friend)
            requests[FriendRequestDirection.INCOMING] = listOf(incoming)
            requests[FriendRequestDirection.OUTGOING] = listOf(outgoing)
            blockedUsers = listOf(user(4L, "blocked", "Blocked"))
            phoneDiscoverySettings = PhoneDiscoverySettings(phoneVerified = true, discoverable = false)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.loadConnections()

        assertTrue(viewModel.uiState.value.hasLoadedConnections)
        assertEquals(listOf(friend), viewModel.uiState.value.friends)
        assertEquals(listOf(incoming), viewModel.uiState.value.incomingRequests)
        assertEquals(listOf(outgoing), viewModel.uiState.value.outgoingRequests)
        assertEquals(repository.blockedUsers, viewModel.uiState.value.blockedUsers)
        assertEquals(repository.phoneDiscoverySettings, viewModel.uiState.value.phoneDiscovery)
        assertFalse(viewModel.uiState.value.isLoadingConnections)
    }

    @Test
    fun loadConnectionsExposesLoadingUntilRepositoryResponds() {
        val deferredFriends = CompletableDeferred<List<User>>()
        val friend = user(3L, "friend", "Friend")
        val repository = FakeContactsRepository().apply { friendsDeferred = deferredFriends }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.loadConnections()

        assertTrue(viewModel.uiState.value.isLoadingConnections)
        assertFalse(viewModel.uiState.value.hasLoadedConnections)

        deferredFriends.complete(listOf(friend))

        assertFalse(viewModel.uiState.value.isLoadingConnections)
        assertTrue(viewModel.uiState.value.hasLoadedConnections)
        assertEquals(listOf(friend), viewModel.uiState.value.friends)
    }

    @Test
    fun loadConnectionsFailureCanBeRetriedWithoutReadingContacts() {
        val repository = FakeContactsRepository().apply {
            friendsFailure = AuthHttpException(503)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.loadConnections()

        assertFalse(viewModel.uiState.value.hasLoadedConnections)
        assertFalse(viewModel.uiState.value.isLoadingConnections)
        assertEquals("service_unavailable", viewModel.uiState.value.connectionsLoadErrorMessage)
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.phoneLookupCalls.isEmpty())

        viewModel.loadConnections()

        assertTrue(viewModel.uiState.value.hasLoadedConnections)
        assertNull(viewModel.uiState.value.connectionsLoadErrorMessage)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun blockingUserRemovesConnectionsAndSearchMatchesThenUnblockRemovesPrivacyEntry() {
        val target = user(5L, "target", "Target")
        val me = user(1L, "me", "Me")
        val repository = FakeContactsRepository().apply {
            friends = listOf(target)
            requests[FriendRequestDirection.INCOMING] = listOf(request("in-target", sender = target, recipient = me))
            requests[FriendRequestDirection.OUTGOING] = listOf(
                request("out-target", sender = me, recipient = target),
                request("rejected-history", sender = me, recipient = target, status = FriendRequestStatus.REJECTED)
            )
            searchResult = UserSearchPage(listOf(target), null, false)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.loadConnections()
        viewModel.onQueryChanged("tar")
        viewModel.searchUsers()
        assertEquals(listOf(target), viewModel.uiState.value.users)

        viewModel.blockUser(target)

        assertEquals(listOf(target.id), repository.blockedUserIds)
        assertEquals(listOf(target), viewModel.uiState.value.blockedUsers)
        assertTrue(viewModel.uiState.value.friends.isEmpty())
        assertTrue(viewModel.uiState.value.incomingRequests.isEmpty())
        assertTrue(viewModel.uiState.value.outgoingRequests.isEmpty())
        assertTrue(repository.requests.values.flatten().any { it.id == "out-target" && it.status == FriendRequestStatus.CANCELED })
        assertTrue(repository.requests.values.flatten().any { it.id == "rejected-history" && it.status == FriendRequestStatus.REJECTED })
        assertTrue(viewModel.uiState.value.users.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.unblockUser(target.id)

        assertEquals(listOf(target.id), repository.unblockedUserIds)
        assertTrue(viewModel.uiState.value.blockedUsers.isEmpty())
        assertEquals(listOf(target), viewModel.uiState.value.friends)
        assertTrue(viewModel.uiState.value.incomingRequests.isEmpty())
        assertTrue(viewModel.uiState.value.outgoingRequests.isEmpty())
        assertTrue(viewModel.uiState.value.incomingRequests.all { it.status == FriendRequestStatus.PENDING })
        assertTrue(viewModel.uiState.value.outgoingRequests.all { it.status == FriendRequestStatus.PENDING })
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun friendRequestActionsUpdateTheConnectionState() {
        val me = user(1L, "me", "Me")
        val sender = user(2L, "sender", "Sender")
        val recipient = user(3L, "recipient", "Recipient")
        val incoming = request("incoming-1", sender = sender, recipient = me)
        val outgoing = request("outgoing-1", sender = me, recipient = recipient)
        val repository = FakeContactsRepository().apply {
            requests[FriendRequestDirection.INCOMING] = listOf(incoming)
            requests[FriendRequestDirection.OUTGOING] = listOf(outgoing)
            sendRequestResult = request("new-1", sender = me, recipient = recipient)
            updatedRequest = incoming.copy(status = FriendRequestStatus.ACCEPTED, respondedAt = "2026-11-14T00:00:00Z")
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.loadConnections()

        viewModel.sendFriendRequest(recipient.id)
        assertTrue(viewModel.uiState.value.outgoingRequests.any { it.id == "new-1" })

        viewModel.acceptRequest("incoming-1")
        assertTrue(viewModel.uiState.value.incomingRequests.isEmpty())
        assertTrue(viewModel.uiState.value.friends.any { it.id == sender.id })

        val secondIncoming = request("incoming-2", sender = recipient, recipient = me)
        repository.requests[FriendRequestDirection.INCOMING] = listOf(secondIncoming)
        viewModel.loadConnections()
        repository.updatedRequest = secondIncoming.copy(status = FriendRequestStatus.REJECTED)
        viewModel.rejectRequest("incoming-2")
        assertTrue(viewModel.uiState.value.incomingRequests.isEmpty())

        viewModel.cancelRequest("outgoing-1")
        assertFalse(viewModel.uiState.value.outgoingRequests.any { it.id == "outgoing-1" })
        assertEquals(listOf(recipient.id), repository.sentTargetIds)
        assertEquals(listOf("incoming-1"), repository.acceptedIds)
        assertEquals(listOf("incoming-2"), repository.rejectedIds)
        assertEquals(listOf("outgoing-1"), repository.canceledIds)
    }

    @Test
    fun friendRequestRejectsInvalidTargetWithoutCallingRepository() {
        val repository = FakeContactsRepository()
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.sendFriendRequest(0L)

        assertTrue(repository.sentTargetIds.isEmpty())
        assertEquals("invalid_friend_request_target", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun unverifiedPhoneCannotBeMadeDiscoverable() {
        val repository = FakeContactsRepository().apply {
            phoneDiscoverySettings = PhoneDiscoverySettings(phoneVerified = false, discoverable = false)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.loadConnections()

        viewModel.setPhoneDiscoverable(true)

        assertEquals(0, repository.phoneDiscoveryUpdateCalls)
        assertEquals("phone_not_verified", viewModel.uiState.value.errorMessage)
        viewModel.setPhoneDiscoverable(false)
        assertEquals(1, repository.phoneDiscoveryUpdateCalls)
        assertFalse(viewModel.uiState.value.phoneDiscovery!!.discoverable)
    }

    @Test
    fun phoneContactSyncRequiresOneShotConsentNormalizesAndDeduplicatesBeforeRequests() {
        val matched = user(7L, "found", "Found")
        val repository = FakeContactsRepository().apply {
            phoneLookupResults["+84912345678"] = matched
            phoneLookupResults["+84901234567"] = AuthHttpException(404)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        val contacts = listOf("0912 345 678", "+84912345678", "0901-234-567", "not a number")

        viewModel.syncPhoneContacts(contacts)
        assertTrue(repository.phoneLookupCalls.isEmpty())
        assertEquals("contacts_consent_required", viewModel.uiState.value.errorMessage)

        viewModel.grantPhoneContactsSyncConsent(inAppConsentGranted = true, contactsPermissionGranted = false)
        assertEquals("contacts_permission_required", viewModel.uiState.value.errorMessage)
        viewModel.syncPhoneContacts(contacts)
        assertTrue(repository.phoneLookupCalls.isEmpty())
        assertEquals("contacts_consent_required", viewModel.uiState.value.errorMessage)

        viewModel.grantPhoneContactsSyncConsent(inAppConsentGranted = true, contactsPermissionGranted = true)
        viewModel.syncPhoneContacts(contacts)

        assertEquals(listOf("+84912345678", "+84901234567"), repository.phoneLookupCalls)
        assertEquals(listOf(matched), viewModel.uiState.value.phoneContactMatches)
        assertEquals(2, viewModel.uiState.value.phoneContactsProcessedCount)
        assertEquals(2, viewModel.uiState.value.phoneContactsTotalCount)
        assertEquals(1, viewModel.uiState.value.phoneContactsInvalidCount)
        assertTrue(viewModel.uiState.value.hasSyncedPhoneContacts)
        assertFalse(viewModel.uiState.value.phoneContactsSyncConsentGranted)
        assertFalse(viewModel.uiState.value.isSyncingPhoneContacts)

        viewModel.syncPhoneContacts(contacts)
        assertEquals(2, repository.phoneLookupCalls.size)
        assertEquals("contacts_consent_required", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun phoneContactSyncFailureKeepsPartialResultsRetryableAndConsumesConsent() {
        val repository = FakeContactsRepository().apply {
            phoneLookupResults["+84912345678"] = AuthHttpException(500)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        val numbers = listOf("0912 345 678")

        viewModel.grantPhoneContactsSyncConsent(inAppConsentGranted = true, contactsPermissionGranted = true)
        viewModel.syncPhoneContacts(numbers)

        assertEquals(listOf("+84912345678"), repository.phoneLookupCalls)
        assertFalse(viewModel.uiState.value.phoneContactsSyncConsentGranted)
        assertFalse(viewModel.uiState.value.isSyncingPhoneContacts)
        assertFalse(viewModel.uiState.value.hasSyncedPhoneContacts)
        assertEquals("contacts_sync_failed", viewModel.uiState.value.errorMessage)

        repository.phoneLookupResults["+84912345678"] = user(7L, "found", "Found")
        viewModel.grantPhoneContactsSyncConsent(inAppConsentGranted = true, contactsPermissionGranted = true)
        viewModel.syncPhoneContacts(numbers)

        assertEquals(listOf("+84912345678", "+84912345678"), repository.phoneLookupCalls)
        assertEquals(listOf(user(7L, "found", "Found")), viewModel.uiState.value.phoneContactMatches)
        assertTrue(viewModel.uiState.value.hasSyncedPhoneContacts)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun emptyPhoneContactsCompleteWithoutNetworkOrPermissionLeak() {
        val repository = FakeContactsRepository()
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.grantPhoneContactsSyncConsent(inAppConsentGranted = true, contactsPermissionGranted = true)
        viewModel.syncPhoneContacts(emptyList())

        assertTrue(viewModel.uiState.value.hasSyncedPhoneContacts)
        assertEquals(0, viewModel.uiState.value.phoneContactsTotalCount)
        assertEquals(0, viewModel.uiState.value.phoneContactsProcessedCount)
        assertFalse(viewModel.uiState.value.phoneContactsSyncConsentGranted)
        assertTrue(repository.phoneLookupCalls.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun invalidPhoneSearchDoesNotReachRepository() {
        val repository = FakeContactsRepository()
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.onPhoneChanged("1234")

        viewModel.findByPhone()

        assertTrue(repository.phoneLookupCalls.isEmpty())
        assertEquals("invalid_phone", viewModel.uiState.value.errorMessage)
    }
}

private class FakeContactsRepository : ContactsRepository {
    var searchCalls = 0
    var searchResult = UserSearchPage(emptyList(), null, false)
    var friends: List<User> = emptyList()
    var friendsFailure: Exception? = null
    var friendsDeferred: CompletableDeferred<List<User>>? = null
    var blockedUsers: List<User> = emptyList()
    val blockedUserIds = mutableListOf<Long>()
    val unblockedUserIds = mutableListOf<Long>()
    val requests = mutableMapOf<FriendRequestDirection, List<FriendRequest>>()
    var phoneDiscoverySettings = PhoneDiscoverySettings(phoneVerified = true, discoverable = false)
    var phoneDiscoveryUpdateCalls = 0
    var sendRequestResult = request("sent", user(1L, "me", "Me"), user(2L, "target", "Target"))
    var updatedRequest = request("updated", user(2L, "sender", "Sender"))
    val sentTargetIds = mutableListOf<Long>()
    val acceptedIds = mutableListOf<String>()
    val rejectedIds = mutableListOf<String>()
    val canceledIds = mutableListOf<String>()
    val phoneLookupCalls = mutableListOf<String>()
    val phoneLookupResults = mutableMapOf<String, Any>()
    val pages = ArrayDeque<UserSearchPage>()
    val cursors = mutableListOf<String?>()

    override suspend fun searchUsers(query: String, cursor: String?, limit: Int): UserSearchPage {
        searchCalls += 1
        cursors += cursor
        return if (pages.isEmpty()) searchResult else pages.removeFirst()
    }

    override suspend fun findUserByPhone(phone: String): User {
        phoneLookupCalls += phone
        return when (val result = phoneLookupResults[phone]) {
            is User -> result
            is Exception -> throw result
            else -> user(4L, "found_user", "Found User")
        }
    }

    override suspend fun getPhoneDiscoverySettings() = phoneDiscoverySettings

    override suspend fun setPhoneDiscoverable(discoverable: Boolean): PhoneDiscoverySettings {
        phoneDiscoveryUpdateCalls += 1
        return phoneDiscoverySettings.copy(discoverable = discoverable).also { phoneDiscoverySettings = it }
    }

    override suspend fun sendFriendRequest(targetUserId: Long): FriendRequest {
        sentTargetIds += targetUserId
        return sendRequestResult
    }

    override suspend fun loadFriendRequests(direction: FriendRequestDirection) = requests[direction].orEmpty()

    override suspend fun acceptRequest(requestId: String): FriendRequest {
        acceptedIds += requestId
        return updatedRequest
    }

    override suspend fun rejectRequest(requestId: String): FriendRequest {
        rejectedIds += requestId
        return updatedRequest
    }

    override suspend fun cancelRequest(requestId: String) {
        canceledIds += requestId
    }

    override suspend fun loadFriends(): List<User> {
        friendsFailure?.let { failure ->
            friendsFailure = null
            throw failure
        }
        friendsDeferred?.let { deferred -> return deferred.await() }
        return friends.filterNot { friend -> blockedUsers.any { it.id == friend.id } }
    }

    override suspend fun loadBlockedUsers() = blockedUsers

    override suspend fun blockUser(userId: Long) {
        blockedUserIds += userId
        val blockedProfile = listOfNotNull(
            friends.firstOrNull { it.id == userId },
            sendRequestResult.sender,
            sendRequestResult.recipient
        ).firstOrNull { it.id == userId }
            ?: user(userId, "blocked_$userId", "Blocked $userId")
        blockedUsers = blockedUsers.filterNot { it.id == userId } + blockedProfile
        requests.keys.toList().forEach { direction ->
            requests[direction] = requests[direction].orEmpty().map { request ->
                if (
                    request.status == FriendRequestStatus.PENDING &&
                    (request.sender.id == userId || request.recipient.id == userId)
                ) {
                    request.copy(
                        status = FriendRequestStatus.CANCELED,
                        respondedAt = "2026-11-14T00:10:00Z"
                    )
                } else {
                    request
                }
            }
        }
    }

    override suspend fun unblockUser(userId: Long) {
        unblockedUserIds += userId
        blockedUsers = blockedUsers.filterNot { it.id == userId }
    }
}

private fun user(id: Long, username: String, displayName: String) =
    User(id = id, email = null, username = username, displayName = displayName)

private fun request(
    id: String,
    sender: User = user(2L, "sender", "Sender"),
    recipient: User = user(1L, "recipient", "Recipient"),
    status: FriendRequestStatus = FriendRequestStatus.PENDING
) = FriendRequest(
    id = id,
    sender = sender,
    recipient = recipient,
    status = status,
    createdAt = "2026-11-14T00:00:00Z",
    respondedAt = null
)
