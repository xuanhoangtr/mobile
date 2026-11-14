package vn.edu.hcmute.androidchat.backend.auth

import vn.edu.hcmute.androidchat.backend.common.DatabaseManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal interface AuthRepository {
    fun createUser(email: String, username: String, displayName: String, passwordHash: String, now: Instant): AuthUser
    fun findUserByEmail(email: String): AuthUser?
    fun findUserById(userId: Long): AuthUser?
    fun updateProfile(userId: Long, displayName: String?, bio: String?, now: Instant): AuthUser?
    fun searchUsers(query: String, afterId: Long?, excludeUserId: Long, limit: Int): List<AuthUser>
    fun findDiscoverableUserByPhone(phone: String, requesterUserId: Long): AuthUser?
    fun updatePhoneDiscovery(userId: Long, discoverable: Boolean, now: Instant): AuthUser?
    fun isBlockedBetween(firstUserId: Long, secondUserId: Long): Boolean
    fun blockUser(blockerUserId: Long, blockedUserId: Long, now: Instant)
    fun unblockUser(blockerUserId: Long, blockedUserId: Long)
    fun listBlockedUsers(blockerUserId: Long): List<AuthUser>
    fun areFriends(firstUserId: Long, secondUserId: Long): Boolean
    fun createFriendRequest(senderUserId: Long, recipientUserId: Long, now: Instant): FriendRequestRecord
    fun listFriendRequests(userId: Long, incoming: Boolean): List<FriendRequestRecord>
    fun transitionFriendRequest(
        requestId: Long,
        actorUserId: Long,
        action: FriendRequestAction,
        now: Instant
    ): FriendRequestRecord?
    fun listFriends(userId: Long): List<AuthUser>
    fun createSession(session: AuthSession)
    fun rotateSession(oldHash: String, newHash: String, accessExpiresAt: Instant, refreshExpiresAt: Instant, now: Instant): AuthSession?
    fun revokeSession(sessionId: UUID, refreshTokenHash: String, userId: Long, now: Instant): Boolean
    fun findActiveSession(sessionId: UUID, userId: Long, now: Instant): AuthSession?
}

internal class PostgresAuthRepository(private val database: DatabaseManager) : AuthRepository {
    override fun createUser(email: String, username: String, displayName: String, passwordHash: String, now: Instant) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (email, username, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                          phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, email)
                statement.setString(2, username)
                statement.setString(3, displayName)
                statement.setString(4, passwordHash)
                statement.setObject(5, now)
                statement.setObject(6, now)
                statement.executeQuery().use { result ->
                    check(result.next()) { "User insert did not return a row" }
                    result.toAuthUser()
                }
            }
        }

    override fun findUserByEmail(email: String) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                   phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
            FROM users WHERE email = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { result -> if (result.next()) result.toAuthUser() else null }
        }
    }

    override fun findUserById(userId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                   phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
            FROM users WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> if (result.next()) result.toAuthUser() else null }
        }
    }

    override fun updateProfile(userId: Long, displayName: String?, bio: String?, now: Instant) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE users
                SET display_name = COALESCE(?, display_name),
                    bio = CASE WHEN ? THEN NULLIF(?, '') ELSE bio END,
                    updated_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                RETURNING id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                          phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, displayName)
                statement.setBoolean(2, bio != null)
                statement.setString(3, bio)
                statement.setObject(4, now)
                statement.setLong(5, userId)
                statement.executeQuery().use { result -> if (result.next()) result.toAuthUser() else null }
            }
        }

    override fun searchUsers(query: String, afterId: Long?, excludeUserId: Long, limit: Int) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                       phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
                FROM users
                WHERE status = 'ACTIVE' AND id <> ? AND id > ?
                  AND (display_name ILIKE ? OR username ILIKE ?)
                  AND NOT EXISTS (
                    SELECT 1 FROM user_blocks b
                    WHERE (b.blocker_user_id = ? AND b.blocked_user_id = users.id)
                       OR (b.blocked_user_id = ? AND b.blocker_user_id = users.id)
                  )
                ORDER BY id ASC LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, excludeUserId)
                statement.setLong(2, afterId ?: 0L)
                val safeQuery = query.replace("%", " ").replace("_", " ")
                val pattern = "%$safeQuery%"
                statement.setString(3, pattern)
                statement.setString(4, pattern)
                statement.setLong(5, excludeUserId)
                statement.setLong(6, excludeUserId)
                statement.setInt(7, limit + 1)
                statement.executeQuery().use { result -> result.toAuthUsers() }
            }
        }

    override fun findDiscoverableUserByPhone(phone: String, requesterUserId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                   phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
            FROM users WHERE normalized_phone = ? AND phone_discoverable = TRUE
              AND phone_verified_at IS NOT NULL AND status = 'ACTIVE'
              AND id <> ? AND NOT EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_user_id = ? AND b.blocked_user_id = users.id)
                   OR (b.blocked_user_id = ? AND b.blocker_user_id = users.id)
              )
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.setLong(2, requesterUserId)
            statement.setLong(3, requesterUserId)
            statement.setLong(4, requesterUserId)
            statement.executeQuery().use { result -> if (result.next()) result.toAuthUser() else null }
        }
    }

    override fun updatePhoneDiscovery(userId: Long, discoverable: Boolean, now: Instant) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE users
                SET phone_discoverable = ?, updated_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                  AND (? = FALSE OR (normalized_phone IS NOT NULL AND phone_verified_at IS NOT NULL))
                RETURNING id, email, username, display_name, password_hash, normalized_phone, phone_verified_at,
                          phone_discoverable, avatar_url, bio, privacy_settings::text, created_at, updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setBoolean(1, discoverable)
                statement.setObject(2, now)
                statement.setLong(3, userId)
                statement.setBoolean(4, discoverable)
                statement.executeQuery().use { result -> if (result.next()) result.toAuthUser() else null }
            }
        }

    override fun isBlockedBetween(firstUserId: Long, secondUserId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT 1 FROM user_blocks
            WHERE (blocker_user_id = ? AND blocked_user_id = ?)
               OR (blocker_user_id = ? AND blocked_user_id = ?)
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, firstUserId)
            statement.setLong(2, secondUserId)
            statement.setLong(3, secondUserId)
            statement.setLong(4, firstUserId)
            statement.executeQuery().use(ResultSet::next)
        }
    }

    override fun blockUser(blockerUserId: Long, blockedUserId: Long, now: Instant) =
        database.withConnection { connection ->
            connection.inTransaction {
                connection.lockFriendPair(blockerUserId, blockedUserId)
                connection.prepareStatement(
                    """
                    INSERT INTO user_blocks(blocker_user_id, blocked_user_id, created_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (blocker_user_id, blocked_user_id) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, blockerUserId)
                    statement.setLong(2, blockedUserId)
                    statement.setObject(3, now)
                    statement.executeUpdate()
                }
                // A block also closes pending invitations between the pair so old requests cannot
                // be accepted after one party has withdrawn consent to contact.
                connection.prepareStatement(
                    """
                    UPDATE friend_requests
                    SET status = 'CANCELED', responded_at = ?
                    WHERE status = 'PENDING'
                      AND LEAST(sender_user_id, recipient_user_id) = LEAST(?, ?)
                      AND GREATEST(sender_user_id, recipient_user_id) = GREATEST(?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, now)
                    statement.setLong(2, blockerUserId)
                    statement.setLong(3, blockedUserId)
                    statement.setLong(4, blockerUserId)
                    statement.setLong(5, blockedUserId)
                    statement.executeUpdate()
                }
                Unit
            }
        }

    override fun unblockUser(blockerUserId: Long, blockedUserId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            "DELETE FROM user_blocks WHERE blocker_user_id = ? AND blocked_user_id = ?"
        ).use { statement ->
            statement.setLong(1, blockerUserId)
            statement.setLong(2, blockedUserId)
            statement.executeUpdate()
        }
        Unit
    }

    override fun listBlockedUsers(blockerUserId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT u.id, u.email, u.username, u.display_name, u.password_hash, u.normalized_phone,
                   u.phone_verified_at, u.phone_discoverable, u.avatar_url, u.bio,
                   u.privacy_settings::text, u.created_at, u.updated_at
            FROM user_blocks b
            JOIN users u ON u.id = b.blocked_user_id
            WHERE b.blocker_user_id = ? AND u.status = 'ACTIVE'
            ORDER BY u.display_name, u.id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, blockerUserId)
            statement.executeQuery().use { result -> result.toAuthUsers() }
        }
    }

    override fun areFriends(firstUserId: Long, secondUserId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT 1 FROM friend_relationships
            WHERE user_low_id = LEAST(?, ?) AND user_high_id = GREATEST(?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, firstUserId)
            statement.setLong(2, secondUserId)
            statement.setLong(3, firstUserId)
            statement.setLong(4, secondUserId)
            statement.executeQuery().use(ResultSet::next)
        }
    }

    override fun createFriendRequest(senderUserId: Long, recipientUserId: Long, now: Instant) =
        database.withConnection { connection ->
            connection.inTransaction {
                connection.lockFriendPair(senderUserId, recipientUserId)
                if (connection.isBlockedBetween(senderUserId, recipientUserId)) {
                    throw UserBlockedException()
                }
                if (connection.areFriends(senderUserId, recipientUserId)) {
                    throw AlreadyFriendsException()
                }
                if (connection.hasPendingFriendRequest(senderUserId, recipientUserId)) {
                    throw PendingFriendRequestExistsException()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO friend_requests(sender_user_id, recipient_user_id, status, created_at)
                    VALUES (?, ?, 'PENDING', ?)
                    RETURNING id, sender_user_id, recipient_user_id, status, created_at, responded_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, senderUserId)
                    statement.setLong(2, recipientUserId)
                    statement.setObject(3, now)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "Friend request insert did not return a row" }
                        result.toFriendRequestRecord()
                    }
                }
            }
        }

    override fun listFriendRequests(userId: Long, incoming: Boolean) = database.withConnection { connection ->
        val ownerColumn = if (incoming) "recipient_user_id" else "sender_user_id"
        connection.prepareStatement(
            """
            SELECT id, sender_user_id, recipient_user_id, status, created_at, responded_at
            FROM friend_requests
            WHERE $ownerColumn = ?
            ORDER BY created_at DESC, id DESC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> result.toFriendRequestRecords() }
        }
    }

    override fun transitionFriendRequest(
        requestId: Long,
        actorUserId: Long,
        action: FriendRequestAction,
        now: Instant
    ) = database.withConnection { connection ->
        connection.inTransaction {
            val current = connection.findPendingFriendRequest(requestId, actorUserId, action) ?: return@inTransaction null
            connection.lockFriendPair(current.senderUserId, current.recipientUserId)

            val newStatus = when (action) {
                FriendRequestAction.ACCEPT -> FriendRequestStatus.ACCEPTED
                FriendRequestAction.REJECT -> FriendRequestStatus.REJECTED
                FriendRequestAction.CANCEL -> FriendRequestStatus.CANCELED
            }
            val updated = connection.prepareStatement(
                """
                UPDATE friend_requests
                SET status = ?, responded_at = ?
                WHERE id = ? AND status = 'PENDING'
                  AND ${action.ownerColumn} = ?
                RETURNING id, sender_user_id, recipient_user_id, status, created_at, responded_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, newStatus.name)
                statement.setObject(2, now)
                statement.setLong(3, requestId)
                statement.setLong(4, actorUserId)
                statement.executeQuery().use { result -> if (result.next()) result.toFriendRequestRecord() else null }
            } ?: return@inTransaction null

            if (action == FriendRequestAction.ACCEPT) {
                connection.prepareStatement(
                    """
                    INSERT INTO friend_relationships(user_low_id, user_high_id, created_at)
                    VALUES (LEAST(?, ?), GREATEST(?, ?), ?)
                    ON CONFLICT (user_low_id, user_high_id) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, updated.senderUserId)
                    statement.setLong(2, updated.recipientUserId)
                    statement.setLong(3, updated.senderUserId)
                    statement.setLong(4, updated.recipientUserId)
                    statement.setObject(5, now)
                    statement.executeUpdate()
                }
            }
            updated
        }
    }

    override fun listFriends(userId: Long) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT u.id, u.email, u.username, u.display_name, u.password_hash, u.normalized_phone,
                   u.phone_verified_at, u.phone_discoverable, u.avatar_url, u.bio,
                   u.privacy_settings::text, u.created_at, u.updated_at
            FROM friend_relationships f
            JOIN users u ON u.id = CASE WHEN f.user_low_id = ? THEN f.user_high_id ELSE f.user_low_id END
            WHERE (f.user_low_id = ? OR f.user_high_id = ?) AND u.status = 'ACTIVE'
              AND NOT EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_user_id = ? AND b.blocked_user_id = u.id)
                   OR (b.blocked_user_id = ? AND b.blocker_user_id = u.id)
              )
            ORDER BY u.display_name, u.id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, userId)
            statement.setLong(3, userId)
            statement.setLong(4, userId)
            statement.setLong(5, userId)
            statement.executeQuery().use { result -> result.toAuthUsers() }
        }
    }

    override fun createSession(session: AuthSession) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO sessions (
                    id, user_id, refresh_token_hash, created_at, access_token_expires_at,
                    refresh_token_expires_at, revoked_at, device_name, client_platform, last_used_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, session.id)
                statement.setLong(2, session.userId)
                statement.setString(3, session.refreshTokenHash)
                statement.setObject(4, session.createdAt)
                statement.setObject(5, session.accessTokenExpiresAt)
                statement.setObject(6, session.refreshTokenExpiresAt)
                statement.setObject(7, session.revokedAt)
                statement.setString(8, session.deviceName)
                statement.setString(9, session.clientPlatform)
                statement.setObject(10, session.lastUsedAt)
                statement.executeUpdate()
            }
        }
    }

    override fun rotateSession(
        oldHash: String,
        newHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant
    ) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            UPDATE sessions
            SET refresh_token_hash = ?, access_token_expires_at = ?, refresh_token_expires_at = ?,
                last_used_at = ?, rotated_at = ?
            WHERE refresh_token_hash = ? AND revoked_at IS NULL AND refresh_token_expires_at > ?
            RETURNING id, user_id, refresh_token_hash, created_at, access_token_expires_at,
                      refresh_token_expires_at, revoked_at, device_name, client_platform, last_used_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, newHash)
            statement.setObject(2, accessExpiresAt)
            statement.setObject(3, refreshExpiresAt)
            statement.setObject(4, now)
            statement.setObject(5, now)
            statement.setString(6, oldHash)
            statement.setObject(7, now)
            statement.executeQuery().use { result -> if (result.next()) result.toAuthSession() else null }
        }
    }

    override fun revokeSession(sessionId: UUID, refreshTokenHash: String, userId: Long, now: Instant) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE sessions SET revoked_at = ?, refresh_token_hash = NULL
                WHERE id = ? AND refresh_token_hash = ? AND user_id = ? AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, now)
                statement.setObject(2, sessionId)
                statement.setString(3, refreshTokenHash)
                statement.setLong(4, userId)
                statement.executeUpdate() == 1
            }
        }

    override fun findActiveSession(sessionId: UUID, userId: Long, now: Instant) = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, user_id, refresh_token_hash, created_at, access_token_expires_at,
                   refresh_token_expires_at, revoked_at, device_name, client_platform, last_used_at
            FROM sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL
              AND access_token_expires_at > ? AND refresh_token_expires_at > ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setLong(2, userId)
            statement.setObject(3, now)
            statement.setObject(4, now)
            statement.executeQuery().use { result -> if (result.next()) result.toAuthSession() else null }
        }
    }

    private fun ResultSet.toAuthUser() = AuthUser(
        id = getLong("id"), email = getString("email"), username = getString("username"),
        displayName = getString("display_name"), passwordHash = getString("password_hash"),
        normalizedPhone = getString("normalized_phone"),
        phoneVerifiedAt = getTimestamp("phone_verified_at")?.toInstant(),
        phoneDiscoverable = getBoolean("phone_discoverable"),
        avatarUrl = getString("avatar_url"), bio = getString("bio"),
        privacySettings = getString("privacy_settings"), createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant()
    )

    private fun ResultSet.toAuthSession() = AuthSession(
        id = getObject("id", UUID::class.java), userId = getLong("user_id"),
        refreshTokenHash = getString("refresh_token_hash"), createdAt = getTimestamp("created_at").toInstant(),
        accessTokenExpiresAt = getTimestamp("access_token_expires_at").toInstant(),
        refreshTokenExpiresAt = getTimestamp("refresh_token_expires_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(), deviceName = getString("device_name"),
        clientPlatform = getString("client_platform"), lastUsedAt = getTimestamp("last_used_at")?.toInstant()
    )

    private fun ResultSet.toAuthUsers(): List<AuthUser> = buildList {
        while (next()) add(toAuthUser())
    }

}

private fun ResultSet.toFriendRequestRecord() = FriendRequestRecord(
    id = getLong("id"),
    senderUserId = getLong("sender_user_id"),
    recipientUserId = getLong("recipient_user_id"),
    status = FriendRequestStatus.valueOf(getString("status")),
    createdAt = getTimestamp("created_at").toInstant(),
    respondedAt = getTimestamp("responded_at")?.toInstant()
)

private fun ResultSet.toFriendRequestRecords(): List<FriendRequestRecord> = buildList {
    while (next()) add(toFriendRequestRecord())
}

private fun java.sql.Connection.lockFriendPair(firstUserId: Long, secondUserId: Long) {
    prepareStatement(
        "SELECT pg_advisory_xact_lock(hashtextextended(LEAST(?, ?)::text || ':' || GREATEST(?, ?)::text, 0))"
    ).use { statement ->
        statement.setLong(1, firstUserId)
        statement.setLong(2, secondUserId)
        statement.setLong(3, firstUserId)
        statement.setLong(4, secondUserId)
        statement.executeQuery().use { it.next() }
    }
}

private fun java.sql.Connection.areFriends(firstUserId: Long, secondUserId: Long): Boolean =
    prepareStatement(
        "SELECT 1 FROM friend_relationships WHERE user_low_id = LEAST(?, ?) AND user_high_id = GREATEST(?, ?)"
    ).use { statement ->
        statement.setLong(1, firstUserId)
        statement.setLong(2, secondUserId)
        statement.setLong(3, firstUserId)
        statement.setLong(4, secondUserId)
        statement.executeQuery().use(ResultSet::next)
    }

private fun java.sql.Connection.isBlockedBetween(firstUserId: Long, secondUserId: Long): Boolean =
    prepareStatement(
        """
        SELECT 1 FROM user_blocks
        WHERE (blocker_user_id = ? AND blocked_user_id = ?)
           OR (blocker_user_id = ? AND blocked_user_id = ?)
        LIMIT 1
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, firstUserId)
        statement.setLong(2, secondUserId)
        statement.setLong(3, secondUserId)
        statement.setLong(4, firstUserId)
        statement.executeQuery().use(ResultSet::next)
    }

private fun java.sql.Connection.hasPendingFriendRequest(firstUserId: Long, secondUserId: Long): Boolean =
    prepareStatement(
        """
        SELECT 1 FROM friend_requests
        WHERE status = 'PENDING' AND LEAST(sender_user_id, recipient_user_id) = LEAST(?, ?)
          AND GREATEST(sender_user_id, recipient_user_id) = GREATEST(?, ?)
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, firstUserId)
        statement.setLong(2, secondUserId)
        statement.setLong(3, firstUserId)
        statement.setLong(4, secondUserId)
        statement.executeQuery().use(ResultSet::next)
    }

private fun java.sql.Connection.findPendingFriendRequest(
    requestId: Long,
    actorUserId: Long,
    action: FriendRequestAction
): FriendRequestRecord? = prepareStatement(
    """
    SELECT id, sender_user_id, recipient_user_id, status, created_at, responded_at
    FROM friend_requests
    WHERE id = ? AND status = 'PENDING' AND ${action.ownerColumn} = ?
    """.trimIndent()
).use { statement ->
    statement.setLong(1, requestId)
    statement.setLong(2, actorUserId)
    statement.executeQuery().use { result -> if (result.next()) result.toFriendRequestRecord() else null }
}

private inline fun <T> java.sql.Connection.inTransaction(block: () -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (exception: Throwable) {
        runCatching(::rollback)
        throw exception
    } finally {
        autoCommit = previousAutoCommit
    }
}

internal class AuthStorageUnavailableException : IllegalStateException("Authentication storage is unavailable")
internal class DuplicateUserException : IllegalStateException("User already exists")
internal class AlreadyFriendsException : IllegalStateException("Users are already friends")
internal class PendingFriendRequestExistsException : IllegalStateException("A friend request already exists for these users")
internal class FriendRequestNotFoundException : NoSuchElementException("Friend request was not found")
internal class UserBlockedException : IllegalStateException("Interaction is blocked between these users")

internal enum class FriendRequestAction(val ownerColumn: String) {
    ACCEPT("recipient_user_id"),
    REJECT("recipient_user_id"),
    CANCEL("sender_user_id")
}
