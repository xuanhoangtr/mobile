package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.module
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushTokenRoutesTest {
    private val config = AppConfig("test", 8080, null, null, null, "push-test-key", "push-test", "push-client")

    @Test
    fun tokenRegistrationIsAuthenticatedAndDoesNotEchoToken() = testApplication {
        val root = Files.createTempDirectory("push-token-test-")
        try {
            application {
                module(config)
                configureMediaFoundation(config, MediaService(LocalMediaStorage(root)))
            }
            val token = JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
                .withSubject("7")
                .withClaim("sid", java.util.UUID.randomUUID().toString())
                .withExpiresAt(Instant.now().plusSeconds(300))
                .sign(Algorithm.HMAC256(config.jwtSecret))
            val body = """{"deviceId":"device-1","token":"provider-secret-token","enabled":true}"""
            assertEquals(HttpStatusCode.Unauthorized, client.post("/devices/push-token") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status)
            val response = client.post("/devices/push-token") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!response.bodyAsText().contains("provider-secret-token"))
            assertEquals(HttpStatusCode.NoContent, client.delete("/devices/push-token/device-1") {
                bearerAuth(token)
            }.status)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun invalidDeviceIdIsRejected() = testApplication {
        val root = Files.createTempDirectory("push-token-invalid-")
        try {
            application {
                module(config)
                configureMediaFoundation(config, MediaService(LocalMediaStorage(root)))
            }
            val token = JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
                .withSubject("7")
                .withClaim("sid", java.util.UUID.randomUUID().toString())
                .sign(Algorithm.HMAC256(config.jwtSecret))
            val response = client.post("/devices/push-token") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"bad/id","token":"token"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun registryLookupsAndOfflinePushDispatchSkipSenderAndCaller() = runBlocking {
        val registry = PushTokenRegistry(dataSource = null)
        registry.register(10L, PushTokenRequest("dev-10", "fcm-token-10", enabled = true))
        registry.register(20L, PushTokenRequest("dev-20", "fcm-token-20", enabled = true))
        registry.register(30L, PushTokenRequest("dev-30", "fcm-token-30", enabled = false))

        val active = registry.activeTokensForUsers(setOf(10L, 20L, 30L))
        assertEquals(setOf("fcm-token-10", "fcm-token-20"), active.map { it.token }.toSet())

        val sentEvents = mutableListOf<Triple<Set<Long>, String, Map<String, String>>>()
        val fakeSender = object : PushNotificationSender {
            override suspend fun sendToUsers(
                userIds: Set<Long>,
                title: String,
                body: String,
                data: Map<String, String>
            ) {
                sentEvents.add(Triple(userIds, body, data))
            }
        }

        dispatchOfflinePushEvent(
            sender = fakeSender,
            offlineUserIds = setOf(10L, 20L),
            eventType = "message.created",
            payload = buildJsonObject {
                put("conversationId", 5L)
                put("senderId", 10L)
                put("type", "TEXT")
                put("content", "Hello FCM")
            }
        )
        assertEquals(1, sentEvents.size)
        assertEquals(setOf(20L), sentEvents[0].first)
        assertEquals("Hello FCM", sentEvents[0].second)
        assertEquals("5", sentEvents[0].third["conversationId"])

        dispatchOfflinePushEvent(
            sender = fakeSender,
            offlineUserIds = setOf(10L, 20L),
            eventType = "call.invite",
            payload = buildJsonObject {
                put("callId", "call-abc")
                put("callerId", 10L)
                put("conversationId", 5L)
                put("media", "video")
            }
        )
        assertEquals(2, sentEvents.size)
        assertEquals(setOf(20L), sentEvents[1].first)
        assertEquals("call-abc", sentEvents[1].third["callId"])
        assertEquals("video", sentEvents[1].third["media"])

        registry.removeTokenByValue("fcm-token-20")
        assertEquals(listOf("fcm-token-10"), registry.activeTokensForUsers(setOf(10L, 20L)).map { it.token })
    }
}
