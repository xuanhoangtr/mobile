package vn.edu.hcmute.androidchat.feature.contacts.data

import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpResponse
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpTransport
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.auth.session.AuthTokenProvider
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequest
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestDirection
import vn.edu.hcmute.androidchat.feature.contacts.model.FriendRequestStatus
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneDiscoverySettings
import vn.edu.hcmute.androidchat.feature.contacts.model.PhoneNumberNormalizer
import vn.edu.hcmute.androidchat.feature.contacts.model.UserSearchPage
import java.net.URLEncoder

interface ContactsRepository {
    suspend fun searchUsers(query: String, cursor: String? = null, limit: Int = 30): UserSearchPage
    suspend fun findUserByPhone(phone: String): User
    suspend fun getPhoneDiscoverySettings(): PhoneDiscoverySettings
    suspend fun setPhoneDiscoverable(discoverable: Boolean): PhoneDiscoverySettings
    suspend fun sendFriendRequest(targetUserId: Long): FriendRequest
    suspend fun loadFriendRequests(direction: FriendRequestDirection): List<FriendRequest>
    suspend fun acceptRequest(requestId: String): FriendRequest
    suspend fun rejectRequest(requestId: String): FriendRequest
    suspend fun cancelRequest(requestId: String)
    suspend fun loadFriends(): List<User>
    suspend fun loadBlockedUsers(): List<User>
    suspend fun blockUser(userId: Long)
    suspend fun unblockUser(userId: Long)
}

class RemoteContactsRepository(
    private val transport: AuthHttpTransport,
    private val tokenProvider: AuthTokenProvider
) : ContactsRepository {
    override suspend fun searchUsers(query: String, cursor: String?, limit: Int): UserSearchPage {
        require(query.trim().length in 2..80) { "Search query must contain 2–80 characters" }
        require(limit in 1..100) { "Limit must be between 1 and 100" }
        val path = buildString {
            append("/users/search?q=").append(encode(query.trim()))
            append("&limit=").append(limit)
            cursor?.let { append("&cursor=").append(encode(it)) }
        }
        val json = JSONObject(authorizedRequest("GET", path, retryAfterUnauthorized = true).body)
        val items = json.getJSONArray("items").let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).toUser() }
        }
        return UserSearchPage(
            items = items,
            nextCursor = json.optString("nextCursor").takeIf(String::isNotBlank),
            hasMore = json.getBoolean("hasMore")
        )
    }

    override suspend fun findUserByPhone(phone: String): User {
        val normalized = PhoneNumberNormalizer.toE164(phone)
            ?: throw IllegalArgumentException("Phone number must be a valid E.164 number")
        val response = authorizedRequest(
            method = "GET",
            path = "/users/by-phone?phone=${encode(normalized)}",
            retryAfterUnauthorized = true
        )
        return JSONObject(response.body).toUser()
    }

    override suspend fun getPhoneDiscoverySettings(): PhoneDiscoverySettings =
        JSONObject(
            authorizedRequest(
                method = "GET",
                path = "/users/me/phone-discovery",
                retryAfterUnauthorized = true
            ).body
        ).toPhoneDiscoverySettings()

    override suspend fun setPhoneDiscoverable(discoverable: Boolean): PhoneDiscoverySettings =
        JSONObject(
            authorizedRequest(
                method = "PATCH",
                path = "/users/me/phone-discovery",
                body = JSONObject().put("discoverable", discoverable).toString(),
                retryAfterUnauthorized = true
            ).body
        ).toPhoneDiscoverySettings()

    override suspend fun sendFriendRequest(targetUserId: Long): FriendRequest {
        require(targetUserId > 0) { "Target user ID must be positive" }
        return JSONObject(
            authorizedRequest(
                method = "POST",
                path = "/friend-requests",
                body = JSONObject().put("targetUserId", targetUserId).toString()
            ).body
        ).toFriendRequest()
    }

    override suspend fun loadFriendRequests(direction: FriendRequestDirection): List<FriendRequest> {
        val json = JSONObject(
            authorizedRequest(
                method = "GET",
                path = "/friend-requests?direction=${direction.queryValue}",
                retryAfterUnauthorized = true
            ).body
        )
        return json.getJSONArray("items").let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).toFriendRequest() }
        }
    }

    override suspend fun acceptRequest(requestId: String): FriendRequest =
        updateFriendRequest(requestId, "accept")

    override suspend fun rejectRequest(requestId: String): FriendRequest =
        updateFriendRequest(requestId, "reject")

    override suspend fun cancelRequest(requestId: String) {
        authorizedRequest(
            method = "DELETE",
            path = "/friend-requests/${encodeRequestId(requestId)}"
        )
    }

    override suspend fun loadFriends(): List<User> {
        val json = JSONObject(
            authorizedRequest(
                method = "GET",
                path = "/friends",
                retryAfterUnauthorized = true
            ).body
        )
        return json.getJSONArray("items").let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).toUser() }
        }
    }

    override suspend fun loadBlockedUsers(): List<User> {
        val json = JSONObject(
            authorizedRequest(
                method = "GET",
                path = "/users/blocked",
                retryAfterUnauthorized = true
            ).body
        )
        return json.getJSONArray("items").let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).toUser() }
        }
    }

    override suspend fun blockUser(userId: Long) {
        require(userId > 0) { "Target user ID must be positive" }
        authorizedRequest(
            method = "POST",
            path = "/users/$userId/block",
            retryAfterUnauthorized = true
        )
    }

    override suspend fun unblockUser(userId: Long) {
        require(userId > 0) { "Target user ID must be positive" }
        authorizedRequest(
            method = "DELETE",
            path = "/users/$userId/block",
            retryAfterUnauthorized = true
        )
    }

    private suspend fun updateFriendRequest(requestId: String, action: String): FriendRequest {
        return JSONObject(
            authorizedRequest(
                method = "POST",
                path = "/friend-requests/${encodeRequestId(requestId)}/$action"
            ).body
        ).toFriendRequest()
    }

    private suspend fun authorizedRequest(
        method: String,
        path: String,
        body: String? = null,
        retryAfterUnauthorized: Boolean = false
    ): AuthHttpResponse {
        val token = tokenProvider.accessToken() ?: throw AuthHttpException(401)
        val response = transport.execute(method, path, body, token)
        if (response.statusCode == 401 && retryAfterUnauthorized) {
            val refreshed = tokenProvider.refreshAfterUnauthorized() ?: throw AuthHttpException(401)
            return executeExpectingSuccess(method, path, body, refreshed)
        }
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return response
    }

    private suspend fun executeExpectingSuccess(
        method: String,
        path: String,
        body: String?,
        token: String
    ): AuthHttpResponse {
        val retry = transport.execute(method, path, body, token)
        if (retry.statusCode !in 200..299) throw AuthHttpException(retry.statusCode)
        return retry
    }

    private fun JSONObject.toUser() = User(
        id = getLong("id"),
        email = optionalString("email"),
        username = getString("username"),
        displayName = getString("displayName"),
        normalizedPhone = optionalString("normalizedPhone"),
        avatarUrl = optionalString("avatarUrl"),
        bio = optionalString("bio")
    )

    private fun JSONObject.toPhoneDiscoverySettings() = PhoneDiscoverySettings(
        phoneVerified = getBoolean("phoneVerified"),
        discoverable = getBoolean("discoverable")
    )

    private fun JSONObject.toFriendRequest() = FriendRequest(
        id = getString("id"),
        sender = getJSONObject("sender").toUser(),
        recipient = getJSONObject("recipient").toUser(),
        status = FriendRequestStatus.valueOf(getString("status")),
        createdAt = getString("createdAt"),
        respondedAt = nullableRequiredString("respondedAt")
    )

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableRequiredString(key: String): String? {
        require(has(key)) { "Missing required field '$key'" }
        return if (isNull(key)) null else getString(key)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodeRequestId(requestId: String): String {
        require(requestId.isNotBlank()) { "Friend request ID is required" }
        return encode(requestId)
    }
}
