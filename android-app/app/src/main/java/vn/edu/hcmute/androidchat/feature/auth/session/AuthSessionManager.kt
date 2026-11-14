package vn.edu.hcmute.androidchat.feature.auth.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthSessionApi
import vn.edu.hcmute.androidchat.feature.auth.data.AuthSessionStore
import vn.edu.hcmute.androidchat.feature.auth.model.AuthCredentials

class AuthSessionManager internal constructor(
    private val store: AuthSessionStore,
    private val api: AuthSessionApi,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : AuthTokenProvider {
    private val refreshMutex = Mutex()

    override suspend fun accessToken(): String? = refreshMutex.withLock {
        val credentials = readStore() ?: return@withLock null
        if (credentials.session.isRevoked) {
            clearStore()
            return@withLock null
        }
        if (credentials.session.refreshTokenExpiresAtEpochMillis <= currentTimeMillis()) {
            clearStore()
            return@withLock null
        }
        if (credentials.session.accessTokenExpiresAtEpochMillis > currentTimeMillis() + TOKEN_REFRESH_SKEW_MS) {
            return@withLock credentials.accessToken
        }
        refreshLocked(credentials)
    }

    override suspend fun refreshAfterUnauthorized(): String? = refreshMutex.withLock {
        val credentials = readStore() ?: return@withLock null
        if (
            credentials.session.isRevoked ||
            credentials.session.refreshTokenExpiresAtEpochMillis <= currentTimeMillis()
        ) {
            clearStore()
            return@withLock null
        }
        refreshLocked(credentials)
    }

    suspend fun save(credentials: AuthCredentials) = refreshMutex.withLock { writeStore(credentials) }

    suspend fun clearSession() = refreshMutex.withLock { clearStore() }

    suspend fun sessionId(): String? = refreshMutex.withLock { readStore()?.session?.id }

    suspend fun logout(): Boolean = refreshMutex.withLock {
        var credentials = readStore() ?: return@withLock true
        if (credentials.session.refreshTokenExpiresAtEpochMillis <= currentTimeMillis()) {
            clearStore()
            return@withLock true
        }
        if (credentials.session.accessTokenExpiresAtEpochMillis <= currentTimeMillis() + TOKEN_REFRESH_SKEW_MS) {
            val accessToken = refreshLocked(credentials) ?: return@withLock true
            credentials = readStore()?.takeIf { it.accessToken == accessToken } ?: return@withLock false
        }
        try {
            val revoked = api.logout(credentials)
            if (revoked) clearStore()
            revoked
        } catch (exception: AuthHttpException) {
            if (exception.statusCode != 401) throw exception
            clearStore()
            true
        }
    }

    private suspend fun refreshLocked(credentials: AuthCredentials): String? = try {
        val refreshed = api.refresh(credentials.refreshToken)
        writeStore(refreshed)
        refreshed.accessToken
    } catch (exception: AuthHttpException) {
        if (exception.statusCode == 401) {
            clearStore()
            null
        } else {
            throw exception
        }
    }

    private suspend fun readStore(): AuthCredentials? = withContext(Dispatchers.IO) { store.read() }

    private suspend fun writeStore(credentials: AuthCredentials) = withContext(Dispatchers.IO) {
        store.write(credentials)
    }

    private suspend fun clearStore() = withContext(Dispatchers.IO) { store.clear() }

    private companion object {
        const val TOKEN_REFRESH_SKEW_MS = 30_000L
    }
}
