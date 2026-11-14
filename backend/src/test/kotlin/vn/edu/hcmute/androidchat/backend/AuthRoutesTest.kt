package vn.edu.hcmute.androidchat.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.edu.hcmute.androidchat.backend.auth.AuthRepository
import vn.edu.hcmute.androidchat.backend.auth.AuthSession
import vn.edu.hcmute.androidchat.backend.auth.AuthTokenResponse
import vn.edu.hcmute.androidchat.backend.auth.AuthUser
import vn.edu.hcmute.androidchat.backend.auth.BlockedUsersResponse
import vn.edu.hcmute.androidchat.backend.auth.CreateFriendRequestRequest
import vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction
import vn.edu.hcmute.androidchat.backend.auth.FriendRequestRecord
import vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus
import vn.edu.hcmute.androidchat.backend.auth.FriendRequestView
import vn.edu.hcmute.androidchat.backend.auth.FriendRequestsResponse
import vn.edu.hcmute.androidchat.backend.auth.FriendsResponse
import vn.edu.hcmute.androidchat.backend.auth.PhoneDiscoverySettings
import vn.edu.hcmute.androidchat.backend.auth.UpdatePhoneDiscoveryRequest
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthRoutesTest {
    private val testConfig = AppConfig(
        env = "test",
        port = 8080,
        dbUrl = null,
        dbUser = null,
        dbPassword = null,
        jwtSecret = "unit-test-auth-secret-not-for-production",
        jwtIssuer = "android-chat-backend",
        jwtAudience = "android-chat-clients"
    )

    @Test
    fun registrationLoginRefreshLogoutAndProfileUseServerManagedSessions() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }

        val registration = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"Huy@example.com","username":"huydc7","password":"strong-password-123","displayName":"Huy"}""")
        }
        assertEquals(HttpStatusCode.Created, registration.status)
        val registered = AppJson.decodeFromString<AuthTokenResponse>(registration.bodyAsText())
        assertEquals("huy@example.com", registered.user.email)
        assertTrue(registered.accessToken.isNotBlank())
        assertTrue(registered.refreshToken.isNotBlank())
        assertFalse(registration.bodyAsText().contains("strong-password-123"))

        val incorrectPassword = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"huy@example.com","password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, incorrectPassword.status)

        val me = client.get("/users/me") { bearerAuth(registered.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("huy@example.com"))
        assertFalse(me.bodyAsText().contains("password_hash"))

        val invalidUpdate = client.patch("/users/me") {
            bearerAuth(registered.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidUpdate.status)

        val updated = client.patch("/users/me") {
            bearerAuth(registered.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Đinh Công Huy","bio":"AUTH"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.bodyAsText().contains("Đinh Công Huy"))

        val refreshedResponse = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${registered.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshedResponse.status)
        val refreshed = AppJson.decodeFromString<AuthTokenResponse>(refreshedResponse.bodyAsText())
        assertEquals(registered.sessionId, refreshed.sessionId)
        assertNotEquals(registered.refreshToken, refreshed.refreshToken)

        val replayedRefresh = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${registered.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replayedRefresh.status)

        val logout = client.post("/auth/logout") {
            bearerAuth(refreshed.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${refreshed.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertTrue(logout.bodyAsText().contains("\"revoked\":true"))

        val revokedAccess = client.get("/users/me") { bearerAuth(refreshed.accessToken) }
        assertEquals(HttpStatusCode.Unauthorized, revokedAccess.status)
        val revokedRefresh = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${refreshed.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, revokedRefresh.status)
    }

    @Test
    fun userSearchDoesNotReturnEmailOrPhoneAndPhoneLookupRespectsDiscovery() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }

        val current = client.register("current@example.com", "current_user", "Current User")
        val match = client.register("match@example.com", "huy_profile", "Đinh Huy")
        repository.setPhone(match.user.id, "+84912345678", discoverable = true)
        repository.setPhone(current.user.id, "+84987654321", discoverable = false)

        val search = client.get("/users/search?q=Huy") { bearerAuth(current.accessToken) }
        assertEquals(HttpStatusCode.OK, search.status)
        val searchJson = AppJson.parseToJsonElement(search.bodyAsText()).jsonObject
        val items = searchJson["items"].toString()
        assertTrue(items.contains("Đinh Huy"))
        assertFalse(items.contains("match@example.com"))
        assertFalse(items.contains("+84912345678"))

        val phoneResult = client.get("/users/by-phone?phone=%2B84912345678") {
            bearerAuth(current.accessToken)
        }
        assertEquals(HttpStatusCode.OK, phoneResult.status)
        assertTrue(phoneResult.bodyAsText().contains("huy_profile"))

        val hiddenPhone = client.get("/users/by-phone?phone=%2B84987654321") {
            bearerAuth(current.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, hiddenPhone.status)

        val invalidPhone = client.get("/users/by-phone?phone=0912345678") {
            bearerAuth(current.accessToken)
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPhone.status)
    }

    @Test
    fun databaseFailureDuringSessionCheckReturnsServiceUnavailableInsteadOfUnauthorized() = testApplication {
        val repository = InMemoryAuthRepository().apply { failSessionChecks = true }
        application { module(testConfig, repository) }
        val sessionId = UUID.randomUUID()
        val token = JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject("7")
            .withClaim("sid", sessionId.toString())
            .sign(Algorithm.HMAC256(testConfig.jwtSecret))

        val response = client.get("/users/me") { bearerAuth(token) }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun expiredAccessTokenCannotReadPrivateProfile() = testApplication {
        application { module(testConfig, InMemoryAuthRepository()) }
        val now = Instant.now()
        val expiredToken = JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject("7")
            .withClaim("sid", UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now.minusSeconds(120)))
            .withExpiresAt(Date.from(now.minusSeconds(60)))
            .sign(Algorithm.HMAC256(testConfig.jwtSecret))

        val response = client.get("/users/me") { bearerAuth(expiredToken) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun userSearchReturnsCursorAndNextPage() = testApplication {
        application { module(testConfig, InMemoryAuthRepository()) }
        val searcher = client.register("searcher@example.com", "searcher", "Search User")
        client.register("match-one@example.com", "match_one", "Match One")
        client.register("match-two@example.com", "match_two", "Match Two")

        val firstResponse = client.get("/users/search?q=Match&limit=1") {
            bearerAuth(searcher.accessToken)
        }
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstPage = AppJson.parseToJsonElement(firstResponse.bodyAsText()).jsonObject
        assertTrue(firstPage.getValue("hasMore").jsonPrimitive.content.toBoolean())
        val cursor = firstPage.getValue("nextCursor").jsonPrimitive.content
        assertTrue(cursor.isNotBlank())

        val nextResponse = client.get(
            "/users/search?q=Match&limit=1&cursor=${URLEncoder.encode(cursor, Charsets.UTF_8.name())}"
        ) {
            bearerAuth(searcher.accessToken)
        }
        assertEquals(HttpStatusCode.OK, nextResponse.status)
        assertTrue(nextResponse.bodyAsText().contains("Match Two"))
    }

    @Test
    fun phoneDiscoveryRequiresProviderVerifiedPhoneAndKeepsPhonePrivate() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }
        val owner = client.register("owner@example.com", "phone_owner", "Phone Owner")
        val searcher = client.register("searcher@example.com", "phone_searcher", "Phone Searcher")

        val initial = client.get("/users/me/phone-discovery") { bearerAuth(owner.accessToken) }
        assertEquals(HttpStatusCode.OK, initial.status)
        assertEquals(
            PhoneDiscoverySettings(phoneVerified = false, discoverable = false),
            AppJson.decodeFromString(initial.bodyAsText())
        )

        val cannotEnableUnverified = client.patch("/users/me/phone-discovery") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"discoverable":true}""")
        }
        assertEquals(HttpStatusCode.Conflict, cannotEnableUnverified.status)

        // Simulate trusted provider metadata in the test fixture; there is no public verify endpoint.
        repository.setPhone(owner.user.id, "+84912345678", discoverable = false)
        val enabled = client.patch("/users/me/phone-discovery") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"discoverable":true}""")
        }
        assertEquals(HttpStatusCode.OK, enabled.status)
        assertEquals(
            PhoneDiscoverySettings(phoneVerified = true, discoverable = true),
            AppJson.decodeFromString(enabled.bodyAsText())
        )

        val discovered = client.get("/users/by-phone?phone=%2B84912345678") {
            bearerAuth(searcher.accessToken)
        }
        assertEquals(HttpStatusCode.OK, discovered.status)
        assertFalse(discovered.bodyAsText().contains("owner@example.com"))
        assertFalse(discovered.bodyAsText().contains("+84912345678"))

        val cannotDisableWithoutBody = client.patch("/users/me/phone-discovery") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, cannotDisableWithoutBody.status)

        val unauthenticated = client.get("/users/me/phone-discovery")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
    }

    @Test
    fun friendRequestsEnforceOwnershipAndPersistAcceptedRelationships() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }
        val sender = client.register("sender@example.com", "friend_sender", "Sender")
        val recipient = client.register("recipient@example.com", "friend_recipient", "Recipient")
        val third = client.register("third@example.com", "friend_third", "Third")
        val canceler = client.register("canceler@example.com", "friend_canceler", "Canceler")

        val invalidDirection = client.get("/friend-requests?direction=sideways") {
            bearerAuth(sender.accessToken)
        }
        assertEquals(HttpStatusCode.BadRequest, invalidDirection.status)

        val selfRequest = client.post("/friend-requests") {
            bearerAuth(sender.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${sender.user.id}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, selfRequest.status)

        val createdResponse = client.post("/friend-requests") {
            bearerAuth(sender.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${recipient.user.id}}""")
        }
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        val created = AppJson.decodeFromString<FriendRequestView>(createdResponse.bodyAsText())
        assertTrue(created.id.toLong() > 0)
        assertEquals(FriendRequestStatus.PENDING, created.status)
        assertEquals(sender.user.id, created.sender.id)
        assertEquals(recipient.user.id, created.recipient.id)
        assertFalse(createdResponse.bodyAsText().contains("email"))
        assertFalse(createdResponse.bodyAsText().contains("password"))

        val duplicateReverse = client.post("/friend-requests") {
            bearerAuth(recipient.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${sender.user.id}}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicateReverse.status)

        val outgoing = client.get("/friend-requests?direction=outgoing") {
            bearerAuth(sender.accessToken)
        }
        assertEquals(HttpStatusCode.OK, outgoing.status)
        assertEquals(
            listOf(FriendRequestStatus.PENDING),
            AppJson.decodeFromString<FriendRequestsResponse>(outgoing.bodyAsText()).items.map { it.status }
        )
        val incoming = client.get("/friend-requests?direction=incoming") {
            bearerAuth(recipient.accessToken)
        }
        assertEquals(HttpStatusCode.OK, incoming.status)
        assertTrue(incoming.bodyAsText().contains(created.id))

        val wrongOwner = client.post("/friend-requests/${created.id}/accept") {
            bearerAuth(sender.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, wrongOwner.status)

        val acceptedResponse = client.post("/friend-requests/${created.id}/accept") {
            bearerAuth(recipient.accessToken)
        }
        assertEquals(HttpStatusCode.OK, acceptedResponse.status)
        val accepted = AppJson.decodeFromString<FriendRequestView>(acceptedResponse.bodyAsText())
        assertEquals(FriendRequestStatus.ACCEPTED, accepted.status)
        assertTrue(accepted.respondedAt != null)

        for ((owner, expectedFriend) in listOf(sender to "Recipient", recipient to "Sender")) {
            val friendsResponse = client.get("/friends") { bearerAuth(owner.accessToken) }
            assertEquals(HttpStatusCode.OK, friendsResponse.status)
            val friends = AppJson.decodeFromString<FriendsResponse>(friendsResponse.bodyAsText())
            assertEquals(listOf(expectedFriend), friends.items.map { it.displayName })
            assertFalse(friendsResponse.bodyAsText().contains("email"))
        }

        val alreadyFriends = client.post("/friend-requests") {
            bearerAuth(sender.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${recipient.user.id}}""")
        }
        assertEquals(HttpStatusCode.Conflict, alreadyFriends.status)

        val rejectResponse = client.post("/friend-requests") {
            bearerAuth(sender.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${third.user.id}}""")
        }
        val rejectedRequest = AppJson.decodeFromString<FriendRequestView>(rejectResponse.bodyAsText())
        val rejected = client.post("/friend-requests/${rejectedRequest.id}/reject") {
            bearerAuth(third.accessToken)
        }
        assertEquals(HttpStatusCode.OK, rejected.status)
        assertEquals(FriendRequestStatus.REJECTED, AppJson.decodeFromString<FriendRequestView>(rejected.bodyAsText()).status)

        val cancelResponse = client.post("/friend-requests") {
            bearerAuth(canceler.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${sender.user.id}}""")
        }
        val cancelRequest = AppJson.decodeFromString<FriendRequestView>(cancelResponse.bodyAsText())
        val canceled = client.delete("/friend-requests/${cancelRequest.id}") {
            bearerAuth(canceler.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, canceled.status)
        assertTrue(canceled.bodyAsText().isEmpty())
        val finalIncoming = client.get("/friend-requests?direction=incoming") {
            bearerAuth(sender.accessToken)
        }
        val canceledHistory = AppJson.decodeFromString<FriendRequestsResponse>(finalIncoming.bodyAsText())
        assertEquals(FriendRequestStatus.CANCELED, canceledHistory.items.first().status)
    }

    @Test
    fun blockingHidesUsersCancelsPendingRequestsAndCanOnlyBeRemovedByBlocker() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }
        val owner = client.register("block-owner@example.com", "block_owner", "Block Owner")
        val target = client.register("block-target@example.com", "block_target", "Block Target")
        val pendingSender = client.register("pending-sender@example.com", "pending_sender", "Pending Sender")
        repository.setPhone(target.user.id, "+84912345678", discoverable = true)

        val accepted = client.post("/friend-requests") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${target.user.id}}""")
        }
        val acceptedRequest = AppJson.decodeFromString<FriendRequestView>(accepted.bodyAsText())
        assertEquals(HttpStatusCode.Created, accepted.status)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/friend-requests/${acceptedRequest.id}/accept") {
                bearerAuth(target.accessToken)
            }.status
        )

        val pending = client.post("/friend-requests") {
            bearerAuth(pendingSender.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${owner.user.id}}""")
        }
        val pendingRequest = AppJson.decodeFromString<FriendRequestView>(pending.bodyAsText())
        assertEquals(HttpStatusCode.Created, pending.status)

        val blockPendingSender = client.post("/users/${pendingSender.user.id}/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, blockPendingSender.status)

        val blocked = client.post("/users/${target.user.id}/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, blocked.status)
        assertTrue(blocked.bodyAsText().isEmpty())

        val blockedAgain = client.post("/users/${target.user.id}/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, blockedAgain.status)

        val blockedUsersResponse = client.get("/users/blocked") { bearerAuth(owner.accessToken) }
        assertEquals(HttpStatusCode.OK, blockedUsersResponse.status)
        val blockedUsers = AppJson.decodeFromString<BlockedUsersResponse>(blockedUsersResponse.bodyAsText())
        assertEquals(setOf(target.user.id, pendingSender.user.id), blockedUsers.items.map { it.id }.toSet())
        assertFalse(blockedUsersResponse.bodyAsText().contains("block-target@example.com"))
        assertFalse(blockedUsersResponse.bodyAsText().contains("+84912345678"))

        val hiddenSearch = client.get("/users/search?q=Block+Target") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.OK, hiddenSearch.status)
        assertFalse(hiddenSearch.bodyAsText().contains("block_target"))

        val hiddenPhone = client.get("/users/by-phone?phone=%2B84912345678") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, hiddenPhone.status)

        val blockedRequest = client.post("/friend-requests") {
            bearerAuth(target.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":${owner.user.id}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, blockedRequest.status)
        assertTrue(blockedRequest.bodyAsText().contains("BLOCKED"))

        val pendingHistory = client.get("/friend-requests?direction=incoming") {
            bearerAuth(owner.accessToken)
        }
        val canceledPending = AppJson.decodeFromString<FriendRequestsResponse>(pendingHistory.bodyAsText())
            .items.single { it.id == pendingRequest.id }
        assertEquals(FriendRequestStatus.CANCELED, canceledPending.status)
        assertTrue(canceledPending.respondedAt != null)

        // Accepted friendship is retained in storage, but remains hidden while the block exists.
        val hiddenFriends = client.get("/friends") { bearerAuth(owner.accessToken) }
        assertEquals(HttpStatusCode.OK, hiddenFriends.status)
        assertFalse(hiddenFriends.bodyAsText().contains("block_target"))

        // A target cannot remove a block created by the other user.
        val wrongOwnerUnblock = client.delete("/users/${owner.user.id}/block") {
            bearerAuth(target.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, wrongOwnerUnblock.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/users/by-phone?phone=%2B84912345678") {
                bearerAuth(owner.accessToken)
            }.status
        )

        val selfBlock = client.post("/users/${owner.user.id}/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.BadRequest, selfBlock.status)
        val missingTarget = client.post("/users/99999/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, missingTarget.status)
        val unauthenticated = client.get("/users/blocked")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

        val unblocked = client.delete("/users/${target.user.id}/block") {
            bearerAuth(owner.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, unblocked.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/users/by-phone?phone=%2B84912345678") {
                bearerAuth(owner.accessToken)
            }.status
        )
        val restoredFriends = AppJson.decodeFromString<FriendsResponse>(
            client.get("/friends") { bearerAuth(owner.accessToken) }.bodyAsText()
        )
        assertEquals(listOf("Block Target"), restoredFriends.items.map { it.displayName })
    }

    private suspend fun io.ktor.client.HttpClient.register(
        email: String,
        username: String,
        displayName: String
    ): AuthTokenResponse {
        val response = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","username":"$username","password":"strong-password-123","displayName":"$displayName"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return AppJson.decodeFromString(response.bodyAsText())
    }
}

internal class InMemoryAuthRepository : AuthRepository {
    private val users = linkedMapOf<Long, AuthUser>()
    private val sessions = linkedMapOf<UUID, AuthSession>()
    private val friendRequests = linkedMapOf<Long, FriendRequestRecord>()
    private val friendships = mutableSetOf<Pair<Long, Long>>()
    private var nextUserId = 1L
    private var nextFriendRequestId = 1L
    @Volatile
    var failSessionChecks = false

    @Synchronized
    fun seedActiveSession(userId: Long, sessionId: UUID, now: Instant = Instant.now()) {
        sessions[sessionId] = AuthSession(
            id = sessionId,
            userId = userId,
            refreshTokenHash = "a".repeat(64),
            createdAt = now,
            accessTokenExpiresAt = now.plusSeconds(3600),
            refreshTokenExpiresAt = now.plusSeconds(86_400)
        )
    }

    @Synchronized
    override fun createUser(email: String, username: String, displayName: String, passwordHash: String, now: Instant): AuthUser {
        if (users.values.any { it.email == email || it.username == username }) {
            throw vn.edu.hcmute.androidchat.backend.auth.DuplicateUserException()
        }
        val user = AuthUser(
            id = nextUserId++, email = email, username = username, displayName = displayName,
            passwordHash = passwordHash, createdAt = now, updatedAt = now
        )
        users[user.id] = user
        return user
    }

    @Synchronized
    override fun findUserByEmail(email: String) = users.values.firstOrNull { it.email == email }

    @Synchronized
    override fun findUserById(userId: Long) = users[userId]

    @Synchronized
    override fun updateProfile(userId: Long, displayName: String?, bio: String?, now: Instant): AuthUser? {
        val user = users[userId] ?: return null
        val updated = user.copy(
            displayName = displayName ?: user.displayName,
            bio = if (bio == null) user.bio else bio.ifEmpty { null },
            updatedAt = now
        )
        users[userId] = updated
        return updated
    }

    @Synchronized
    override fun searchUsers(query: String, afterId: Long?, excludeUserId: Long, limit: Int): List<AuthUser> =
        users.values.filter { it.id != excludeUserId && (afterId == null || it.id > afterId) }
            .filterNot { isBlockedBetween(excludeUserId, it.id) }
            .filter { it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }
            .sortedBy(AuthUser::id)
            .take(limit + 1)

    @Synchronized
    override fun findDiscoverableUserByPhone(phone: String, requesterUserId: Long) =
        users.values.firstOrNull {
            it.id != requesterUserId && it.normalizedPhone == phone && it.phoneVerifiedAt != null && it.phoneDiscoverable &&
                !isBlockedBetween(requesterUserId, it.id)
        }

    @Synchronized
    override fun updatePhoneDiscovery(userId: Long, discoverable: Boolean, now: Instant): AuthUser? {
        val user = users[userId] ?: return null
        if (discoverable && (user.normalizedPhone == null || user.phoneVerifiedAt == null)) return null
        return user.copy(phoneDiscoverable = discoverable, updatedAt = now).also { users[userId] = it }
    }

    @Synchronized
    override fun areFriends(firstUserId: Long, secondUserId: Long) = friendPair(firstUserId, secondUserId) in friendships

    private val blocks = linkedSetOf<Pair<Long, Long>>()

    @Synchronized
    override fun isBlockedBetween(firstUserId: Long, secondUserId: Long) =
        (firstUserId to secondUserId) in blocks || (secondUserId to firstUserId) in blocks

    @Synchronized
    override fun blockUser(blockerUserId: Long, blockedUserId: Long, now: Instant) {
        blocks += blockerUserId to blockedUserId
        friendRequests.entries.toList().forEach { (id, request) ->
            if (
                request.status == vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.PENDING &&
                friendPair(request.senderUserId, request.recipientUserId) == friendPair(blockerUserId, blockedUserId)
            ) {
                friendRequests[id] = request.copy(
                    status = vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.CANCELED,
                    respondedAt = now
                )
            }
        }
    }

    @Synchronized
    override fun unblockUser(blockerUserId: Long, blockedUserId: Long) {
        blocks.remove(blockerUserId to blockedUserId)
    }

    @Synchronized
    override fun listBlockedUsers(blockerUserId: Long) = blocks.asSequence()
        .filter { it.first == blockerUserId }
        .mapNotNull { (_, targetId) -> users[targetId] }
        .sortedWith(compareBy<AuthUser> { it.displayName }.thenBy { it.id })
        .toList()

    @Synchronized
    override fun createFriendRequest(senderUserId: Long, recipientUserId: Long, now: Instant): FriendRequestRecord {
        if (isBlockedBetween(senderUserId, recipientUserId)) {
            throw vn.edu.hcmute.androidchat.backend.auth.UserBlockedException()
        }
        if (areFriends(senderUserId, recipientUserId)) throw vn.edu.hcmute.androidchat.backend.auth.AlreadyFriendsException()
        if (friendRequests.values.any {
                it.status == vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.PENDING &&
                    friendPair(it.senderUserId, it.recipientUserId) == friendPair(senderUserId, recipientUserId)
            }
        ) {
            throw vn.edu.hcmute.androidchat.backend.auth.PendingFriendRequestExistsException()
        }
        return FriendRequestRecord(
            id = nextFriendRequestId++,
            senderUserId = senderUserId,
            recipientUserId = recipientUserId,
            status = vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.PENDING,
            createdAt = now,
            respondedAt = null
        ).also { friendRequests[it.id] = it }
    }

    @Synchronized
    override fun listFriendRequests(userId: Long, incoming: Boolean) = friendRequests.values
        .filter { if (incoming) it.recipientUserId == userId else it.senderUserId == userId }
        .sortedWith(compareByDescending<FriendRequestRecord> { it.createdAt }.thenByDescending { it.id })

    @Synchronized
    override fun transitionFriendRequest(
        requestId: Long,
        actorUserId: Long,
        action: vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction,
        now: Instant
    ): FriendRequestRecord? {
        val current = friendRequests[requestId]
            ?.takeIf { it.status == vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.PENDING }
            ?: return null
        val allowedActor = when (action) {
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.ACCEPT,
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.REJECT -> current.recipientUserId
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.CANCEL -> current.senderUserId
        }
        if (allowedActor != actorUserId) return null
        val status = when (action) {
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.ACCEPT ->
                vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.ACCEPTED
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.REJECT ->
                vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.REJECTED
            vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.CANCEL ->
                vn.edu.hcmute.androidchat.backend.auth.FriendRequestStatus.CANCELED
        }
        return current.copy(status = status, respondedAt = now).also {
            friendRequests[requestId] = it
            if (action == vn.edu.hcmute.androidchat.backend.auth.FriendRequestAction.ACCEPT) {
                friendships += friendPair(current.senderUserId, current.recipientUserId)
            }
        }
    }

    @Synchronized
    override fun listFriends(userId: Long) = friendships.asSequence()
        .filter { userId == it.first || userId == it.second }
        .filterNot { isBlockedBetween(userId, if (userId == it.first) it.second else it.first) }
        .mapNotNull { pair -> users[if (userId == pair.first) pair.second else pair.first] }
        .sortedWith(compareBy<AuthUser> { it.displayName }.thenBy { it.id })
        .toList()

    @Synchronized
    fun setPhone(
        userId: Long,
        phone: String,
        discoverable: Boolean,
        phoneVerifiedAt: Instant? = Instant.now()
    ) {
        users[userId] = requireNotNull(users[userId]).copy(
            normalizedPhone = phone,
            phoneVerifiedAt = phoneVerifiedAt,
            phoneDiscoverable = discoverable
        )
    }

    private fun friendPair(first: Long, second: Long) = minOf(first, second) to maxOf(first, second)

    @Synchronized
    override fun createSession(session: AuthSession) {
        sessions[session.id] = session
    }

    @Synchronized
    override fun rotateSession(
        oldHash: String,
        newHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant
    ): AuthSession? {
        val old = sessions.values.firstOrNull {
            it.refreshTokenHash == oldHash && it.revokedAt == null && it.refreshTokenExpiresAt > now
        } ?: return null
        return old.copy(
            refreshTokenHash = newHash,
            accessTokenExpiresAt = accessExpiresAt,
            refreshTokenExpiresAt = refreshExpiresAt,
            lastUsedAt = now
        ).also { sessions[it.id] = it }
    }

    @Synchronized
    override fun revokeSession(sessionId: UUID, refreshTokenHash: String, userId: Long, now: Instant): Boolean {
        val session = sessions[sessionId] ?: return false
        if (session.userId != userId || session.refreshTokenHash != refreshTokenHash || session.revokedAt != null) {
            return false
        }
        sessions[sessionId] = session.copy(revokedAt = now)
        return true
    }

    @Synchronized
    override fun findActiveSession(sessionId: UUID, userId: Long, now: Instant): AuthSession? =
        if (failSessionChecks) {
            throw vn.edu.hcmute.androidchat.backend.common.DatabaseUnavailableException()
        } else {
            sessions[sessionId]?.takeIf {
                it.userId == userId && it.revokedAt == null &&
                    it.accessTokenExpiresAt > now && it.refreshTokenExpiresAt > now
            }
        }
}
