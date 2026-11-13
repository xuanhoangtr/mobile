package vn.edu.hcmute.androidchat.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
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
    private var nextUserId = 1L
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
            .filter { it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }
            .sortedBy(AuthUser::id)
            .take(limit + 1)

    @Synchronized
    override fun findDiscoverableUserByPhone(phone: String) =
        users.values.firstOrNull { it.normalizedPhone == phone && it.phoneDiscoverable }

    @Synchronized
    fun setPhone(userId: Long, phone: String, discoverable: Boolean) {
        users[userId] = requireNotNull(users[userId]).copy(
            normalizedPhone = phone,
            phoneDiscoverable = discoverable
        )
    }

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
