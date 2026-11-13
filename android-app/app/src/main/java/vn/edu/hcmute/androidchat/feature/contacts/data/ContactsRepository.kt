package vn.edu.hcmute.androidchat.feature.contacts.data

import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpTransport
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.auth.session.AuthTokenProvider
import vn.edu.hcmute.androidchat.feature.contacts.model.UserSearchPage
import java.net.URLEncoder

interface ContactsRepository {
    suspend fun searchUsers(query: String, cursor: String? = null, limit: Int = 30): UserSearchPage
    suspend fun findUserByPhone(phone: String): User
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
        val response = authorizedRequest(path)
        val json = JSONObject(response.body)
        val items = json.getJSONArray("items").let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).toUser() }
        }
        return UserSearchPage(
            items = items,
            nextCursor = json.optString("nextCursor").takeIf(String::isNotBlank),
            hasMore = json.optBoolean("hasMore")
        )
    }

    override suspend fun findUserByPhone(phone: String): User {
        val response = authorizedRequest("/users/by-phone?phone=${encode(phone)}")
        return JSONObject(response.body).toUser()
    }

    private suspend fun authorizedRequest(path: String) = requestWithToken(path, tokenProvider.accessToken())

    private suspend fun requestWithToken(path: String, accessToken: String?): vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpResponse {
        val token = accessToken ?: throw AuthHttpException(401)
        val response = transport.execute("GET", path, bearerToken = token)
        if (response.statusCode != 401) {
            if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
            return response
        }
        val refreshed = tokenProvider.refreshAfterUnauthorized() ?: throw AuthHttpException(401)
        val retry = transport.execute("GET", path, bearerToken = refreshed)
        if (retry.statusCode !in 200..299) throw AuthHttpException(retry.statusCode)
        return retry
    }

    private fun JSONObject.toUser() = User(
        id = getLong("id"),
        email = if (isNull("email")) null else optString("email"),
        username = getString("username"),
        displayName = getString("displayName"),
        normalizedPhone = if (isNull("normalizedPhone")) null else optString("normalizedPhone"),
        avatarUrl = if (isNull("avatarUrl")) null else optString("avatarUrl"),
        bio = if (isNull("bio")) null else optString("bio")
    )

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
