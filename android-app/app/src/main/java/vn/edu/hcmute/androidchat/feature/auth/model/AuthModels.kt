package vn.edu.hcmute.androidchat.feature.auth.model

/** Profile fields returned by AUTH. Password hashes and tokens are never part of this model. */
data class User(
    val id: Long,
    val email: String?,
    val username: String,
    val displayName: String,
    val normalizedPhone: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null
)

/** Server-managed session metadata used to determine when credentials need refreshing. */
data class Session(
    val id: String,
    val accessTokenExpiresAtEpochMillis: Long,
    val refreshTokenExpiresAtEpochMillis: Long,
    val isRevoked: Boolean = false
)

/** Credentials are held only by AUTH's encrypted session store and token provider. */
data class AuthCredentials(
    val user: User,
    val session: Session,
    val accessToken: String,
    val refreshToken: String
)

data class AuthResult(
    val user: User,
    val sessionId: String
)
