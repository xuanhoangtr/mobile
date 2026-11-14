package vn.edu.hcmute.androidchat.feature.auth.session

/** Shared contract for HTTP authorization and the chat WebSocket handshake. */
interface AuthTokenProvider {
    suspend fun accessToken(): String?

    /** Refresh after one 401 response. Returns null when the session is no longer valid. */
    suspend fun refreshAfterUnauthorized(): String?
}
