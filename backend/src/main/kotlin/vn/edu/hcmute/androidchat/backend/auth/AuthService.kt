package vn.edu.hcmute.androidchat.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

internal class AuthService(
    private val repository: AuthRepository,
    private val config: AppConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun register(request: RegisterRequest): AuthTokenResponse {
        val email = request.email.trim().lowercase(Locale.ROOT)
        val displayName = request.displayName.trim()
        val username = (request.username ?: email.substringBefore('@'))
            .trim()
            .lowercase(Locale.ROOT)

        validateRegistration(email, request.password, displayName, username)

        val now = clock.instant()
        val user = try {
            repository.createUser(
                email = email,
                username = username,
                displayName = displayName,
                passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt(BCRYPT_COST)),
                now = now
            )
        } catch (_: DuplicateUserException) {
            throw AuthConflictException("An account already exists for this email or username")
        } catch (exception: SQLException) {
            if (exception.sqlState == "23505") {
                throw AuthConflictException("An account already exists for this email or username")
            }
            throw exception
        }

        return createSession(user, request.deviceName, request.clientPlatform, now)
    }

    fun login(request: LoginRequest): AuthTokenResponse {
        val email = request.email.trim().lowercase(Locale.ROOT)
        if (!AuthValidation.isValidEmail(email) || request.password.isBlank()) {
            throw InvalidCredentialsException()
        }

        val user = repository.findUserByEmail(email) ?: throw InvalidCredentialsException()
        if (!runCatching { BCrypt.checkpw(request.password, user.passwordHash) }.getOrDefault(false)) {
            throw InvalidCredentialsException()
        }
        return createSession(user, request.deviceName, request.clientPlatform, clock.instant())
    }

    fun refresh(refreshToken: String): AuthTokenResponse {
        if (refreshToken.length !in 32..256) throw InvalidRefreshTokenException()
        val now = clock.instant()
        val newRefreshToken = newOpaqueToken()
        val accessExpiresAt = now.plus(ACCESS_TOKEN_LIFETIME)
        val refreshExpiresAt = now.plus(REFRESH_TOKEN_LIFETIME)
        val session = repository.rotateSession(
            oldHash = sha256(refreshToken),
            newHash = sha256(newRefreshToken),
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
            now = now
        ) ?: throw InvalidRefreshTokenException()

        val user = repository.findUserById(session.userId) ?: throw InvalidRefreshTokenException()
        return tokenResponse(user, session.id, newRefreshToken, accessExpiresAt, refreshExpiresAt, now)
    }

    fun logout(refreshToken: String, userId: Long, sessionId: UUID): Boolean {
        if (refreshToken.length !in 32..256) return false
        return repository.revokeSession(sessionId, sha256(refreshToken), userId, clock.instant())
    }

    fun currentUser(userId: Long): AuthUser =
        repository.findUserById(userId) ?: throw UserNotFoundException()

    fun updateProfile(userId: Long, request: UpdateProfileRequest): AuthUser {
        val displayName = request.displayName?.trim()
        val bio = request.bio?.trim()
        if (displayName != null && displayName.length !in 1..80) {
            throw AuthValidationException("Display name must contain between 1 and 80 characters")
        }
        if (bio != null && bio.length > 160) {
            throw AuthValidationException("Bio must not exceed 160 characters")
        }
        if (request.displayName == null && request.bio == null) {
            throw AuthValidationException("At least one profile field must be provided")
        }
        return repository.updateProfile(userId, displayName, bio, clock.instant())
            ?: throw UserNotFoundException()
    }

    fun searchUsers(query: String, cursor: String?, limit: Int, currentUserId: Long) = run {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length !in 2..80) {
            throw AuthValidationException("Search query must contain between 2 and 80 characters")
        }
        if (limit !in 1..100) throw AuthValidationException("Limit must be between 1 and 100")
        val afterId = cursor?.let(::decodeCursor)
        val users = repository.searchUsers(normalizedQuery, afterId, currentUserId, limit)
        users.toSearchPage(limit)
    }

    fun findByPhone(rawPhone: String): AuthUser {
        val normalizedPhone = AuthValidation.normalizeE164(rawPhone)
            ?: throw AuthValidationException("Phone number must use E.164 format")
        return repository.findDiscoverableUserByPhone(normalizedPhone) ?: throw UserNotFoundException()
    }

    fun phoneDiscoverySettings(userId: Long): PhoneDiscoverySettings {
        val user = repository.findUserById(userId) ?: throw UserNotFoundException()
        return PhoneDiscoverySettings(
            phoneVerified = user.phoneVerifiedAt != null,
            discoverable = user.phoneDiscoverable
        )
    }

    fun updatePhoneDiscovery(userId: Long, request: UpdatePhoneDiscoveryRequest): PhoneDiscoverySettings {
        val discoverable = request.discoverable
            ?: throw AuthValidationException("discoverable must be provided")
        val current = repository.findUserById(userId) ?: throw UserNotFoundException()
        if (discoverable && (current.normalizedPhone == null || current.phoneVerifiedAt == null)) {
            throw AuthConflictException("A verified phone number is required to enable discovery")
        }
        val updated = repository.updatePhoneDiscovery(userId, discoverable, clock.instant())
            ?: throw AuthConflictException("A verified phone number is required to enable discovery")
        return PhoneDiscoverySettings(
            phoneVerified = updated.phoneVerifiedAt != null,
            discoverable = updated.phoneDiscoverable
        )
    }

    fun createFriendRequest(senderUserId: Long, request: CreateFriendRequestRequest): FriendRequestView {
        val recipientUserId = request.targetUserId
            ?: throw AuthValidationException("targetUserId must be provided")
        if (recipientUserId <= 0L) throw AuthValidationException("targetUserId must be positive")
        if (recipientUserId == senderUserId) throw AuthValidationException("Users cannot send friend requests to themselves")
        val recipient = repository.findUserById(recipientUserId) ?: throw UserNotFoundException()

        val created = try {
            repository.createFriendRequest(senderUserId, recipient.id, clock.instant())
        } catch (_: AlreadyFriendsException) {
            throw AuthConflictException("Users are already friends")
        } catch (_: PendingFriendRequestExistsException) {
            throw AuthConflictException("A friend request is already pending between these users")
        } catch (exception: SQLException) {
            if (exception.sqlState == "23505") {
                throw AuthConflictException("A friend request is already pending between these users")
            }
            throw exception
        }
        return friendRequestView(created)
    }

    fun listFriendRequests(userId: Long, direction: String): FriendRequestsResponse {
        val incoming = when (direction) {
            "incoming" -> true
            "outgoing" -> false
            else -> throw AuthValidationException("direction must be incoming or outgoing")
        }
        return FriendRequestsResponse(
            items = repository.listFriendRequests(userId, incoming).map(::friendRequestView)
        )
    }

    fun acceptFriendRequest(requestId: Long, recipientUserId: Long): FriendRequestView =
        transitionFriendRequest(requestId, recipientUserId, FriendRequestAction.ACCEPT)

    fun rejectFriendRequest(requestId: Long, recipientUserId: Long): FriendRequestView =
        transitionFriendRequest(requestId, recipientUserId, FriendRequestAction.REJECT)

    fun cancelFriendRequest(requestId: Long, senderUserId: Long) {
        transitionFriendRequest(requestId, senderUserId, FriendRequestAction.CANCEL)
    }

    fun listFriends(userId: Long): FriendsResponse = FriendsResponse(
        items = repository.listFriends(userId).map { it.toPublicUser() }
    )

    private fun transitionFriendRequest(
        requestId: Long,
        actorUserId: Long,
        action: FriendRequestAction
    ): FriendRequestView {
        if (requestId <= 0L) throw AuthValidationException("Friend request id must be positive")
        val updated = repository.transitionFriendRequest(requestId, actorUserId, action, clock.instant())
            ?: throw FriendRequestNotFoundException()
        return friendRequestView(updated)
    }

    private fun friendRequestView(request: FriendRequestRecord): FriendRequestView {
        val sender = repository.findUserById(request.senderUserId) ?: throw FriendRequestNotFoundException()
        val recipient = repository.findUserById(request.recipientUserId) ?: throw FriendRequestNotFoundException()
        return FriendRequestView(
            id = request.id.toString(),
            sender = sender.toPublicUser(),
            recipient = recipient.toPublicUser(),
            status = request.status,
            createdAt = request.createdAt.toString(),
            respondedAt = request.respondedAt?.toString()
        )
    }

    suspend fun isSessionActive(sessionId: UUID, userId: Long): Boolean = withContext(Dispatchers.IO) {
        repository.findActiveSession(sessionId, userId, clock.instant()) != null
    }

    private fun createSession(
        user: AuthUser,
        deviceName: String?,
        clientPlatform: String?,
        now: Instant
    ): AuthTokenResponse {
        val sessionId = UUID.randomUUID()
        val refreshToken = newOpaqueToken()
        val accessExpiresAt = now.plus(ACCESS_TOKEN_LIFETIME)
        val refreshExpiresAt = now.plus(REFRESH_TOKEN_LIFETIME)
        repository.createSession(
            AuthSession(
                id = sessionId,
                userId = user.id,
                refreshTokenHash = sha256(refreshToken),
                createdAt = now,
                accessTokenExpiresAt = accessExpiresAt,
                refreshTokenExpiresAt = refreshExpiresAt,
                deviceName = deviceName?.trim()?.takeIf(String::isNotEmpty)?.take(120),
                clientPlatform = clientPlatform?.trim()?.takeIf(String::isNotEmpty)?.take(32),
                lastUsedAt = now
            )
        )
        return tokenResponse(user, sessionId, refreshToken, accessExpiresAt, refreshExpiresAt, now)
    }

    private fun tokenResponse(
        user: AuthUser,
        sessionId: UUID,
        refreshToken: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant
    ) = AuthTokenResponse(
        user = user.toPublicUser(includeEmail = true, includePhone = true),
        sessionId = sessionId.toString(),
        accessToken = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(user.id.toString())
            .withClaim(SESSION_ID_CLAIM, sessionId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(accessExpiresAt))
            .sign(Algorithm.HMAC256(config.jwtSecret)),
        accessTokenExpiresAt = accessExpiresAt.toString(),
        refreshToken = refreshToken,
        refreshTokenExpiresAt = refreshExpiresAt.toString()
    )

    private fun newOpaqueToken(): String = ByteArray(REFRESH_TOKEN_BYTES).also(secureRandom::nextBytes)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun decodeCursor(cursor: String): Long {
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8).toLong()
        }.getOrNull()
        if (decoded == null || decoded <= 0L) throw AuthValidationException("Cursor is invalid")
        return decoded
    }

    private fun validateRegistration(email: String, password: String, displayName: String, username: String) {
        if (!AuthValidation.isValidEmail(email)) throw AuthValidationException("Email address is invalid")
        if (!AuthValidation.isValidPassword(password)) {
            throw AuthValidationException("Password must be 10–72 UTF-8 bytes")
        }
        if (displayName.length !in 1..80) {
            throw AuthValidationException("Display name must contain between 1 and 80 characters")
        }
        if (!AuthValidation.isValidUsername(username)) {
            throw AuthValidationException("Username must be 3–24 letters, numbers, or underscores")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private companion object {
        const val BCRYPT_COST = 12
        const val REFRESH_TOKEN_BYTES = 48
        const val SESSION_ID_CLAIM = "sid"
        val ACCESS_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)
        val REFRESH_TOKEN_LIFETIME: Duration = Duration.ofDays(30)
    }
}

internal object AuthValidation {
    private val emailPattern = Regex("^[^\\s@]{1,64}@[^\\s@.]+(?:\\.[^\\s@.]+)+$")
    private val usernamePattern = Regex("^[a-z0-9_]{3,24}$")
    private val e164Pattern = Regex("^\\+[1-9][0-9]{7,14}$")

    fun isValidEmail(email: String): Boolean = email.length <= 254 && emailPattern.matches(email)
    fun isValidPassword(password: String): Boolean =
        password.toByteArray(Charsets.UTF_8).size in 10..72
    fun isValidUsername(username: String): Boolean = usernamePattern.matches(username)

    fun normalizeE164(phone: String): String? {
        val normalized = phone.trim().replace(Regex("[\\s().-]"), "")
        return normalized.takeIf(e164Pattern::matches)
    }
}

internal open class AuthServiceException(message: String) : IllegalArgumentException(message)
internal class AuthValidationException(message: String) : AuthServiceException(message)
internal class AuthConflictException(message: String) : AuthServiceException(message)
internal class InvalidCredentialsException : AuthServiceException("Email or password is incorrect")
internal class InvalidRefreshTokenException : AuthServiceException("Refresh token is invalid, expired, or revoked")
internal class UserNotFoundException : AuthServiceException("User was not found")
