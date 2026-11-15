package vn.edu.hcmute.androidchat.feature.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

interface PushTokenClient {
    suspend fun register(deviceId: String, token: String, enabled: Boolean = true)
    suspend fun unregister(deviceId: String)
}

class HttpPushTokenClient(
    private val endpoint: URL,
    private val accessToken: () -> String
) : PushTokenClient {
    override suspend fun register(deviceId: String, token: String, enabled: Boolean) =
        request(
            method = "POST",
            path = "",
            body = """{"deviceId":"${escape(deviceId)}","token":"${escape(token)}","enabled":$enabled}"""
        )

    override suspend fun unregister(deviceId: String) =
        request("DELETE", "/${java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())}", null)

    private suspend fun request(method: String, path: String, body: String?) = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint.toString().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = body != null
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${accessToken()}")
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        try {
            body?.let { connection.outputStream.use { output -> output.write(it.toByteArray(Charsets.UTF_8)) } }
            if (connection.responseCode !in 200..299) error("Push token request failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
