package vn.edu.hcmute.androidchat.feature.auth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.auth.model.AuthCredentials
import vn.edu.hcmute.androidchat.feature.auth.model.Session
import vn.edu.hcmute.androidchat.feature.auth.model.User
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

data class AuthHttpResponse(val statusCode: Int, val body: String)

interface AuthHttpTransport {
    suspend fun execute(
        method: String,
        path: String,
        body: String? = null,
        bearerToken: String? = null
    ): AuthHttpResponse

    suspend fun executeBytes(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        bearerToken: String? = null
    ): AuthHttpResponse = throw UnsupportedOperationException("Binary transport is not configured")
}

/** Small AUTH-only transport; the host supplies its environment-specific backend URL. */
class UrlConnectionAuthTransport(
    baseUrl: String,
    allowLoopbackHttp: Boolean = false
) : AuthHttpTransport {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        val url = URL(normalizedBaseUrl)
        val loopbackHosts = setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]")
        val secure = url.protocol.equals("https", ignoreCase = true)
        val allowedDebugHttp = allowLoopbackHttp &&
            url.protocol.equals("http", ignoreCase = true) &&
            url.host.lowercase() in loopbackHosts
        require(url.userInfo == null && url.query == null && url.ref == null && (secure || allowedDebugHttp)) {
            "Backend URL must use HTTPS; debug HTTP is limited to loopback/emulator hosts"
        }
    }

    override suspend fun execute(
        method: String,
        path: String,
        body: String?,
        bearerToken: String?
    ): AuthHttpResponse = executeRaw(
        method = method,
        path = path,
        body = body?.toByteArray(Charsets.UTF_8),
        contentType = if (body != null) "application/json; charset=utf-8" else null,
        bearerToken = bearerToken
    )

    override suspend fun executeBytes(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        bearerToken: String?
    ): AuthHttpResponse = executeRaw(
        method = method,
        path = path,
        body = body,
        contentType = contentType,
        bearerToken = bearerToken
    )

    private suspend fun executeRaw(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        bearerToken: String?
    ): AuthHttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(normalizedBaseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (body != null && contentType != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
            bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

        try {
            body?.let { requestBody ->
                connection.outputStream.use { output ->
                    output.write(requestBody)
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            AuthHttpResponse(status, responseBody)
        } catch (exception: IOException) {
            throw AuthNetworkException("Could not reach the authentication server", exception)
        } finally {
            connection.disconnect()
        }
    }
}

interface AuthSessionApi {
    suspend fun refresh(refreshToken: String): AuthCredentials
    suspend fun logout(credentials: AuthCredentials): Boolean
}

/** REST DTO adapter. It never logs request bodies, credentials, response tokens, or headers. */
class AuthHttpApi internal constructor(private val transport: AuthHttpTransport) : AuthSessionApi {
    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        username: String
    ): AuthCredentials = executeCredentials(
        "/auth/register",
        JSONObject()
            .put("email", email.trim().lowercase())
            .put("password", password)
            .put("displayName", displayName.trim())
            .put("username", username.trim().lowercase())
            .put("clientPlatform", "android")
    )

    suspend fun login(email: String, password: String): AuthCredentials = executeCredentials(
        "/auth/login",
        JSONObject()
            .put("email", email.trim().lowercase())
            .put("password", password)
            .put("clientPlatform", "android")
    )

    override suspend fun refresh(refreshToken: String): AuthCredentials = executeCredentials(
        "/auth/refresh",
        JSONObject().put("refreshToken", refreshToken)
    )

    override suspend fun logout(credentials: AuthCredentials): Boolean {
        val response = transport.execute(
            method = "POST",
            path = "/auth/logout",
            body = JSONObject().put("refreshToken", credentials.refreshToken).toString(),
            bearerToken = credentials.accessToken
        )
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).optBoolean("revoked")
    }

    suspend fun currentUser(accessToken: String): User {
        val response = transport.execute("GET", "/users/me", bearerToken = accessToken)
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).toUser()
    }

    suspend fun updateProfile(accessToken: String, displayName: String?, bio: String?): User {
        require(displayName != null || bio != null) { "At least one profile field is required" }
        val request = JSONObject().apply {
            displayName?.let { put("displayName", it) }
            bio?.let { put("bio", it) }
        }
        val response = transport.execute("PATCH", "/users/me", request.toString(), accessToken)
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).toUser()
    }

    suspend fun uploadAvatar(accessToken: String, imageBytes: ByteArray, contentType: String): User {
        val response = transport.executeBytes("POST", "/users/me/avatar", imageBytes, contentType, accessToken)
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).toUser()
    }

    suspend fun searchUsers(
        query: String,
        accessToken: String,
        cursor: String? = null,
        limit: Int = 30
    ): String {
        val params = buildString {
            append("q=").append(encodeQuery(query))
            append("&limit=").append(limit.coerceIn(1, 100))
            cursor?.let { append("&cursor=").append(encodeQuery(it)) }
        }
        val response = transport.execute("GET", "/users/search?$params", bearerToken = accessToken)
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return response.body
    }

    suspend fun findUserByPhone(phone: String, accessToken: String): User {
        val response = transport.execute(
            "GET",
            "/users/by-phone?phone=${encodeQuery(phone)}",
            bearerToken = accessToken
        )
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).toUser()
    }

    private suspend fun executeCredentials(path: String, request: JSONObject): AuthCredentials {
        val response = transport.execute("POST", path, request.toString())
        if (response.statusCode !in 200..299) throw AuthHttpException(response.statusCode)
        return JSONObject(response.body).toCredentials()
    }

    private fun JSONObject.toCredentials(): AuthCredentials {
        val accessExpiry = Instant.parse(getString("accessTokenExpiresAt")).toEpochMilli()
        val refreshExpiry = Instant.parse(getString("refreshTokenExpiresAt")).toEpochMilli()
        val sessionId = getString("sessionId")
        return AuthCredentials(
            user = getJSONObject("user").toUser(),
            session = Session(
                id = sessionId,
                accessTokenExpiresAtEpochMillis = accessExpiry,
                refreshTokenExpiresAtEpochMillis = refreshExpiry
            ),
            accessToken = getString("accessToken"),
            refreshToken = getString("refreshToken")
        )
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

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun encodeQuery(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}

class AuthHttpException(val statusCode: Int) : IOException(
    when (statusCode) {
        400 -> "Some account information is invalid"
        401 -> "Email or password is incorrect, or the session has expired"
        409 -> "An account already exists for this email or username"
        404 -> "User was not found or is not discoverable"
        503 -> "Authentication service is temporarily unavailable"
        else -> "Authentication request failed (HTTP $statusCode)"
    }
)

class AuthNetworkException(message: String, cause: Throwable) : IOException(message, cause)
