package vn.edu.hcmute.androidchat.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    private val testConfig = AppConfig(
        env = "test",
        port = 8080,
        dbUrl = null,
        dbUser = null,
        dbPassword = null,
        jwtSecret = "unit-test-secret-key-only-for-test-suite",
        jwtIssuer = "android-chat-backend",
        jwtAudience = "android-chat-clients"
    )

    private fun issueTestToken(userId: Long, sessionId: UUID): String =
        JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("sid", sessionId.toString())
            .sign(Algorithm.HMAC256(testConfig.jwtSecret))

    @Test
    fun healthEndpointReturnsUpWithoutDatabaseConfigured() = testApplication {
        application { module(testConfig) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UP", body["status"]?.jsonPrimitive?.content)
        assertEquals("NOT_CONFIGURED", body["database"]?.jsonPrimitive?.content)
    }

    @Test
    fun conversationsRequiresValidJwtAndReturnsEmptySkeletonWhenAuthenticated() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }

        val unauth = client.get("/conversations")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val legacySessionlessToken = JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject("7")
            .sign(Algorithm.HMAC256(testConfig.jwtSecret))
        val sessionless = client.get("/conversations") {
            header(HttpHeaders.Authorization, "Bearer $legacySessionlessToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, sessionless.status)

        val sessionId = UUID.randomUUID()
        repository.seedActiveSession(userId = 7L, sessionId = sessionId)
        val token = issueTestToken(userId = 7L, sessionId = sessionId)
        val auth = client.get("/conversations") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, auth.status)
        assertTrue(auth.bodyAsText().contains("\"items\":[]"))
    }

    @Test
    fun webSocketChatRejectsUnauthenticatedAndProcessesMessageSendAndDeduplication() = testApplication {
        val repository = InMemoryAuthRepository()
        application { module(testConfig, repository) }

        val wsClient = createClient {
            install(ClientWebSockets)
        }

        val unauthFailed = runCatching {
            wsClient.webSocket("/ws/chat") { }
        }
        assertTrue(unauthFailed.isFailure)

        val sessionId = UUID.randomUUID()
        repository.seedActiveSession(userId = 42L, sessionId = sessionId)
        val token = issueTestToken(userId = 42L, sessionId = sessionId)

        wsClient.webSocket("/ws/chat", request = {
            header(HttpHeaders.Authorization, "Bearer $token")
        }) {
            val presenceFrame = (incoming.receive() as Frame.Text).readText()
            val presenceJson = AppJson.parseToJsonElement(presenceFrame).jsonObject
            assertEquals("presence", presenceJson["type"]?.jsonPrimitive?.content)
            assertEquals("42", presenceJson["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.content)

            val sendJson = """
                {
                  "type": "message.send",
                  "requestId": "req-1",
                  "timestamp": "2026-11-12T13:00:00Z",
                  "payload": {
                    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
                    "conversationId": 101,
                    "type": "TEXT",
                    "content": "Hello Ktor WS"
                  }
                }
            """.trimIndent()
            send(Frame.Text(sendJson))

            val ack1 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.ack", ack1["type"]?.jsonPrimitive?.content)
            assertEquals("ACCEPTED", ack1["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            val created = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.created", created["type"]?.jsonPrimitive?.content)
            assertEquals("42", created["payload"]?.jsonObject?.get("senderId")?.jsonPrimitive?.content)

            // Resend with identical clientMessageId -> must deduplicate
            send(Frame.Text(sendJson))
            val ack2 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.ack", ack2["type"]?.jsonPrimitive?.content)
            assertEquals("DUPLICATE", ack2["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            // Typing & receipt skeleton verification
            send(Frame.Text("""{"type":"typing","requestId":"req-2","timestamp":"2026-11-12T13:00:01Z","payload":{"conversationId":101,"isTyping":true}}"""))
            val typingResp = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("typing", typingResp["type"]?.jsonPrimitive?.content)
            assertEquals("42", typingResp["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.content)

            send(Frame.Text("""{"type":"receipt","requestId":"req-3","timestamp":"2026-11-12T13:00:02Z","payload":{"conversationId":101,"messageId":1,"status":"READ"}}"""))
            val receiptResp = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("receipt", receiptResp["type"]?.jsonPrimitive?.content)
            assertEquals("READ", receiptResp["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
        }
    }
}
