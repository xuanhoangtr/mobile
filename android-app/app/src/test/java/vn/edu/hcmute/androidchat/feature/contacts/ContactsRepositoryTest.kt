package vn.edu.hcmute.androidchat.feature.contacts

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpResponse
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpTransport
import vn.edu.hcmute.androidchat.feature.auth.session.AuthTokenProvider
import vn.edu.hcmute.androidchat.feature.contacts.data.RemoteContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestDirection
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestStatus
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneDiscoverySettings
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneNumberNormalizer

class ContactsRepositoryTest {
    @Test
    fun normalizesVietnameseNationalPrefixesAndValidatesE164() {
        assertEquals("+84912345678", PhoneNumberNormalizer.toE164("0912 345 678"))
        assertEquals("+84912345678", PhoneNumberNormalizer.toE164("0084-912-345-678"))
        assertEquals("+12025550123", PhoneNumberNormalizer.toE164("+1 (202) 555-0123"))
        assertNull(PhoneNumberNormalizer.toE164("12345678"))
        assertNull(PhoneNumberNormalizer.toE164("+01234567890"))
        assertNull(PhoneNumberNormalizer.toE164("+84+912345678"))
    }

    @Test
    fun phoneLookupSendsOnlyNormalizedE164AndRefreshesOnceAfterGet401() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(401, ""),
            AuthHttpResponse(200, publicUserJson(id = 7L, username = "found", displayName = "Found"))
        )
        val tokens = FakeTokenProvider()
        val repository = RemoteContactsRepository(transport, tokens)

        val user = repository.findUserByPhone("0912 (345) 678")

        assertEquals("found", user.username)
        assertEquals("/users/by-phone?phone=%2B84912345678", transport.requests[0].path)
        assertEquals(listOf("access-1", "access-2"), transport.requests.map { it.bearerToken })
        assertEquals(1, tokens.refreshCalls)
    }

    @Test
    fun friendRequestListUsesDirectionAndParsesTypedFields() = runBlocking {
        val requestJson = friendRequestJson(
            id = "request-1",
            status = "PENDING",
            respondedAt = "null"
        )
        val transport = FakeTransport(AuthHttpResponse(200, """{"items":[$requestJson]}"""))
        val repository = RemoteContactsRepository(transport, FakeTokenProvider())

        val request = repository.loadFriendRequests(FriendRequestDirection.INCOMING).single()

        assertEquals("/friend-requests?direction=incoming", transport.requests.single().path)
        assertEquals("request-1", request.id)
        assertEquals("sender", request.sender.username)
        assertEquals("recipient", request.recipient.username)
        assertEquals(FriendRequestStatus.PENDING, request.status)
        assertEquals("2026-11-14T00:00:00Z", request.createdAt)
        assertNull(request.respondedAt)
    }

    @Test
    fun friendRequestMutationsMatchTheApiContract() = runBlocking {
        val pending = friendRequestJson("request-1", "PENDING", "null")
        val accepted = friendRequestJson("request-1", "ACCEPTED", "\"2026-11-14T00:10:00Z\"")
        val rejected = friendRequestJson("request-2", "REJECTED", "\"2026-11-14T00:11:00Z\"")
        val transport = FakeTransport(
            AuthHttpResponse(201, pending),
            AuthHttpResponse(200, accepted),
            AuthHttpResponse(200, rejected),
            AuthHttpResponse(204, "")
        )
        val repository = RemoteContactsRepository(transport, FakeTokenProvider())

        repository.sendFriendRequest(targetUserId = 42L)
        assertEquals(FriendRequestStatus.ACCEPTED, repository.acceptRequest("request-1").status)
        assertEquals(FriendRequestStatus.REJECTED, repository.rejectRequest("request-2").status)
        repository.cancelRequest("request-3")

        assertEquals(
            listOf("POST", "POST", "POST", "DELETE"),
            transport.requests.map { it.method }
        )
        assertEquals(
            listOf(
                "/friend-requests",
                "/friend-requests/request-1/accept",
                "/friend-requests/request-2/reject",
                "/friend-requests/request-3"
            ),
            transport.requests.map { it.path }
        )
        assertEquals(42L, JSONObject(transport.requests[0].body!!).getLong("targetUserId"))
        assertNull(transport.requests[1].body)
        assertNull(transport.requests[2].body)
        assertNull(transport.requests[3].body)
    }

    @Test
    fun phoneDiscoveryReadsAndUpdatesTheBooleanSetting() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(200, """{"phoneVerified":true,"discoverable":false}"""),
            AuthHttpResponse(200, """{"phoneVerified":true,"discoverable":true}""")
        )
        val repository = RemoteContactsRepository(transport, FakeTokenProvider())

        assertEquals(PhoneDiscoverySettings(true, false), repository.getPhoneDiscoverySettings())
        assertEquals(PhoneDiscoverySettings(true, true), repository.setPhoneDiscoverable(true))

        assertEquals("GET", transport.requests[0].method)
        assertEquals("/users/me/phone-discovery", transport.requests[0].path)
        assertEquals("PATCH", transport.requests[1].method)
        assertEquals("/users/me/phone-discovery", transport.requests[1].path)
        assertTrue(JSONObject(transport.requests[1].body!!).getBoolean("discoverable"))
    }

    @Test
    fun friendListReadsItems() = runBlocking {
        val userJson = publicUserJson(id = 9L, username = "friend", displayName = "Friend")
        val repository = RemoteContactsRepository(
            FakeTransport(AuthHttpResponse(200, """{"items":[$userJson]}""")),
            FakeTokenProvider()
        )

        assertEquals("friend", repository.loadFriends().single().username)
    }

    @Test
    fun blockedUserListAndMutationsMatchTheApiContract() = runBlocking {
        val blockedJson = publicUserJson(id = 9L, username = "blocked", displayName = "Blocked")
        val transport = FakeTransport(
            AuthHttpResponse(200, """{"items":[$blockedJson]}"""),
            AuthHttpResponse(204, ""),
            AuthHttpResponse(204, "")
        )
        val repository = RemoteContactsRepository(transport, FakeTokenProvider())

        assertEquals("blocked", repository.loadBlockedUsers().single().username)
        repository.blockUser(9L)
        repository.unblockUser(9L)

        assertEquals(
            listOf("GET", "POST", "DELETE"),
            transport.requests.map { it.method }
        )
        assertEquals(
            listOf("/users/blocked", "/users/9/block", "/users/9/block"),
            transport.requests.map { it.path }
        )
        assertTrue(transport.requests.all { it.body == null })
    }

    @Test
    fun blockMutationsRejectInvalidUserIdsBeforeSendingARequest() = runBlocking {
        val transport = FakeTransport()
        val repository = RemoteContactsRepository(transport, FakeTokenProvider())

        assertFailsWith<IllegalArgumentException> { repository.blockUser(0L) }
        assertFailsWith<IllegalArgumentException> { repository.unblockUser(-2L) }

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun idempotentBlockMutationsRefreshAndRetryOnceAfterUnauthorized() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(401, ""),
            AuthHttpResponse(204, ""),
            AuthHttpResponse(401, ""),
            AuthHttpResponse(204, "")
        )
        val tokens = FakeTokenProvider()
        val repository = RemoteContactsRepository(transport, tokens)

        repository.blockUser(9L)
        repository.unblockUser(9L)

        assertEquals(2, tokens.refreshCalls)
        assertEquals(listOf("POST", "POST", "DELETE", "DELETE"), transport.requests.map { it.method })
        assertEquals(listOf("access-1", "access-2", "access-1", "access-2"), transport.requests.map { it.bearerToken })
    }

    @Test
    fun nonIdempotentFriendRequestPostIsNotReplayedAfter401() = runBlocking {
        val transport = FakeTransport(AuthHttpResponse(401, ""))
        val tokens = FakeTokenProvider()
        val repository = RemoteContactsRepository(transport, tokens)

        try {
            repository.sendFriendRequest(targetUserId = 42L)
            throw AssertionError("Expected an unauthorized response")
        } catch (exception: AuthHttpException) {
            assertEquals(401, exception.statusCode)
        }

        assertEquals(1, transport.requests.size)
        assertEquals(0, tokens.refreshCalls)
    }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
    } catch (throwable: Throwable) {
        if (throwable is T) return
        throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
    }
    throw AssertionError("Expected ${T::class.java.name}")
}

private data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String?,
    val bearerToken: String?
)

private class FakeTransport(vararg responses: AuthHttpResponse) : AuthHttpTransport {
    private val responseQueue = ArrayDeque(responses.toList())
    val requests = mutableListOf<RecordedRequest>()

    override suspend fun execute(method: String, path: String, body: String?, bearerToken: String?): AuthHttpResponse {
        requests += RecordedRequest(method, path, body, bearerToken)
        check(responseQueue.isNotEmpty()) { "No fake response was queued" }
        return responseQueue.removeFirst()
    }
}

private class FakeTokenProvider : AuthTokenProvider {
    var refreshCalls = 0

    override suspend fun accessToken(): String = "access-1"

    override suspend fun refreshAfterUnauthorized(): String {
        refreshCalls += 1
        return "access-2"
    }
}

private fun publicUserJson(id: Long, username: String, displayName: String) =
    """{"id":$id,"email":null,"username":"$username","displayName":"$displayName","normalizedPhone":null,"avatarUrl":null,"bio":null}"""

private fun friendRequestJson(id: String, status: String, respondedAt: String): String =
    """{"id":"$id","sender":${publicUserJson(2L, "sender", "Sender")},"recipient":${publicUserJson(3L, "recipient", "Recipient")},"status":"$status","createdAt":"2026-11-14T00:00:00Z","respondedAt":$respondedAt}"""
