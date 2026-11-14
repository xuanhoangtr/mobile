package vn.edu.hcmute.androidchat.feature.auth.data

import vn.edu.hcmute.androidchat.feature.auth.model.AuthResult
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.auth.session.AuthSessionManager
import vn.edu.hcmute.androidchat.feature.auth.session.AuthTokenProvider

interface AuthRepository {
    suspend fun register(email: String, password: String, displayName: String, username: String): AuthResult
    suspend fun login(email: String, password: String): AuthResult
    suspend fun logout(): Boolean
    suspend fun currentUser(): User
    suspend fun restoreSession(): AuthResult? = null
    suspend fun updateProfile(displayName: String?, bio: String?): User
}

class RemoteAuthRepository(
    private val api: AuthHttpApi,
    private val sessionManager: AuthSessionManager
) : AuthRepository {
    override suspend fun register(email: String, password: String, displayName: String, username: String): AuthResult {
        val credentials = api.register(email, password, displayName, username)
        sessionManager.save(credentials)
        return AuthResult(credentials.user, credentials.session.id)
    }

    override suspend fun login(email: String, password: String): AuthResult {
        val credentials = api.login(email, password)
        sessionManager.save(credentials)
        return AuthResult(credentials.user, credentials.session.id)
    }

    override suspend fun logout(): Boolean = sessionManager.logout()

    override suspend fun restoreSession(): AuthResult? {
        val user = try {
            currentUser()
        } catch (exception: AuthHttpException) {
            if (exception.statusCode != 401) throw exception
            sessionManager.clearSession()
            return null
        }
        val sessionId = sessionManager.sessionId() ?: return null
        return AuthResult(user, sessionId)
    }

    override suspend fun currentUser(): User {
        val token = sessionManager.accessToken() ?: throw AuthHttpException(401)
        return try {
            api.currentUser(token)
        } catch (exception: AuthHttpException) {
            if (exception.statusCode != 401) throw exception
            val refreshedToken = sessionManager.refreshAfterUnauthorized() ?: throw AuthHttpException(401)
            api.currentUser(refreshedToken)
        }
    }

    override suspend fun updateProfile(displayName: String?, bio: String?): User {
        var token = sessionManager.accessToken() ?: throw AuthHttpException(401)
        return try {
            api.updateProfile(token, displayName, bio)
        } catch (exception: AuthHttpException) {
            if (exception.statusCode != 401) throw exception
            token = sessionManager.refreshAfterUnauthorized() ?: throw AuthHttpException(401)
            api.updateProfile(token, displayName, bio)
        }
    }
}
