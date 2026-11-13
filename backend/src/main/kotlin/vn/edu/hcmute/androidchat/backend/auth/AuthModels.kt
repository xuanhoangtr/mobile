package vn.edu.hcmute.androidchat.backend.auth

import kotlinx.serialization.Serializable
import vn.edu.hcmute.androidchat.backend.common.CursorPage
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Internal persistence model. Password and refresh-token hashes never leave the AUTH module. */
internal data class AuthUser(
    val id: Long,
    val email: String,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val normalizedPhone: String? = null,
    val phoneDiscoverable: Boolean = false,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val privacySettings: String = "{}",
    val createdAt: Instant,
    val updatedAt: Instant
)

internal data class AuthSession(
    val id: UUID,
    val userId: Long,
    val refreshTokenHash: String,
    val createdAt: Instant,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
    val revokedAt: Instant? = null,
    val deviceName: String? = null,
    val clientPlatform: String? = null,
    val lastUsedAt: Instant? = null
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val username: String? = null,
    val deviceName: String? = null,
    val clientPlatform: String? = null
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceName: String? = null,
    val clientPlatform: String? = null
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null
)

@Serializable
data class PublicUser(
    val id: Long,
    val email: String? = null,
    val username: String,
    val displayName: String,
    val normalizedPhone: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null
)

@Serializable
data class AuthTokenResponse(
    val user: PublicUser,
    val sessionId: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val tokenType: String = "Bearer"
)

internal fun AuthUser.toPublicUser(includeEmail: Boolean = false, includePhone: Boolean = false) = PublicUser(
    id = id,
    email = email.takeIf { includeEmail },
    username = username,
    displayName = displayName,
    normalizedPhone = normalizedPhone.takeIf { includePhone },
    avatarUrl = avatarUrl,
    bio = bio
)

internal fun List<AuthUser>.toSearchPage(limit: Int): CursorPage<PublicUser> =
    CursorPage(
        items = take(limit).map { it.toPublicUser() },
        nextCursor = getOrNull(limit - 1)?.id
            ?.takeIf { size > limit }
            ?.toString()
            ?.toByteArray(Charsets.UTF_8)
            ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) },
        hasMore = size > limit
    )
