package vn.edu.hcmute.androidchat.feature.auth.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.auth.data.AuthSessionStore
import vn.edu.hcmute.androidchat.feature.auth.data.AuthSessionApi
import vn.edu.hcmute.androidchat.feature.auth.model.AuthCredentials
import vn.edu.hcmute.androidchat.feature.auth.model.Session
import vn.edu.hcmute.androidchat.feature.auth.model.User
import java.time.Instant

class AuthSessionManagerTest {
    @Test
    fun expiredAccessTokenRotatesAndPersistsRefreshCredentials() = runTest {
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        val store = MemorySessionStore(credentials(now, accessExpiry = now - 1L))
        val api = FakeAuthSessionApi(now)
        val manager = AuthSessionManager(store, api) { now }

        val token = manager.accessToken()

        assertEquals("new-access-token", token)
        assertEquals("new-refresh-token", store.credentials?.refreshToken)
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun logoutRevokesServerSessionAndClearsLocalCredentials() = runTest {
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        val store = MemorySessionStore(credentials(now, accessExpiry = now + 600_000L))
        val api = FakeAuthSessionApi(now)
        val manager = AuthSessionManager(store, api) { now }

        assertTrue(manager.logout())
        assertNull(store.credentials)
        assertEquals(1, api.logoutCalls)
    }

    @Test
    fun expiredRefreshSessionIsClearedWithoutCallingServer() = runTest {
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        val store = MemorySessionStore(credentials(now, accessExpiry = now - 1_000L, refreshExpiry = now - 1L))
        val api = FakeAuthSessionApi(now)
        val manager = AuthSessionManager(store, api) { now }

        assertNull(manager.accessToken())
        assertNull(store.credentials)
        assertFalse(api.refreshCalls > 0)
    }
}

private class MemorySessionStore(var credentials: AuthCredentials?) : AuthSessionStore {
    override fun read(): AuthCredentials? = credentials
    override fun write(credentials: AuthCredentials) { this.credentials = credentials }
    override fun clear() { credentials = null }
}

private class FakeAuthSessionApi(private val now: Long) : AuthSessionApi {
    var refreshCalls = 0
    var logoutCalls = 0

    override suspend fun refresh(refreshToken: String): AuthCredentials {
        refreshCalls += 1
        return credentials(
            now = now,
            accessExpiry = now + 900_000L,
            refreshExpiry = now + 2_592_000_000L
        ).copy(accessToken = "new-access-token", refreshToken = "new-refresh-token")
    }

    override suspend fun logout(credentials: AuthCredentials): Boolean {
        logoutCalls += 1
        return true
    }
}

private fun credentials(now: Long, accessExpiry: Long, refreshExpiry: Long = now + 2_592_000_000L) =
    AuthCredentials(
        user = User(1L, "huy@example.com", "huydc7", "Huy"),
        session = Session("session-1", accessExpiry, refreshExpiry),
        accessToken = "old-access-token",
        refreshToken = "old-refresh-token"
    )
