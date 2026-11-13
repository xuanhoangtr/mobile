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
import vn.edu.hcmute.androidchat.backend.chat.InMemoryChatRepository
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
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

    private fun issueTestToken(userId: Long): String =
        JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject(userId.toString())
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
    fun conversationsAndMessagesEnforceJwtAndMembership() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(7L, 8L), title = "Huy & Hoang")
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val unauth = client.get("/conversations")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val memberToken = issueTestToken(userId = 7L)
        val auth = client.get("/conversations") {
            header(HttpHeaders.Authorization, "Bearer $memberToken")
        }
        assertEquals(HttpStatusCode.OK, auth.status)
        assertTrue(auth.bodyAsText().contains("\"id\":101"))

        val nonMemberToken = issueTestToken(userId = 999L)
        val forbidden = client.get("/conversations/101/messages") {
            header(HttpHeaders.Authorization, "Bearer $nonMemberToken")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun webSocketChatRejectsUnauthenticatedAndBroadcastsToConversationMembers() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(42L, 99L), title = "Direct")
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val wsClient = createClient {
            install(ClientWebSockets)
        }

        val unauthFailed = runCatching {
            wsClient.webSocket("/ws/chat") { }
        }
        assertTrue(unauthFailed.isFailure)

        val senderToken = issueTestToken(userId = 42L)
        val peerToken = issueTestToken(userId = 99L)

        wsClient.webSocket("/ws/chat", request = {
            header(HttpHeaders.Authorization, "Bearer $peerToken")
        }) {
            val peerPresence = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("presence", peerPresence["type"]?.jsonPrimitive?.content)

            wsClient.webSocket("/ws/chat", request = {
                header(HttpHeaders.Authorization, "Bearer $senderToken")
            }) {
                val senderPresence = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("presence", senderPresence["type"]?.jsonPrimitive?.content)

                val sendJson = """
                    {
                      "type": "message.send",
                      "requestId": "req-1",
                      "timestamp": "2026-11-13T10:55:00Z",
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

                val senderCreated = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("message.created", senderCreated["type"]?.jsonPrimitive?.content)
                assertEquals("42", senderCreated["payload"]?.jsonObject?.get("senderId")?.jsonPrimitive?.content)

                // Resend with identical clientMessageId -> must deduplicate
                send(Frame.Text(sendJson))
                val ack2 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("message.ack", ack2["type"]?.jsonPrimitive?.content)
                assertEquals("DUPLICATE", ack2["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

                // Typing & receipt skeleton verification
                send(Frame.Text("""{"type":"typing","requestId":"req-2","timestamp":"2026-11-13T10:55:01Z","payload":{"conversationId":101,"isTyping":true}}"""))
                val typingResp = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("typing", typingResp["type"]?.jsonPrimitive?.content)

                send(Frame.Text("""{"type":"receipt","requestId":"req-3","timestamp":"2026-11-13T10:55:02Z","payload":{"conversationId":101,"messageId":1,"status":"READ"}}"""))
                val receiptResp = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("receipt", receiptResp["type"]?.jsonPrimitive?.content)
                assertEquals("READ", receiptResp["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            }

            // Peer 99 must have received message.created, typing, and receipt
            val peerCreated = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.created", peerCreated["type"]?.jsonPrimitive?.content)
            assertEquals("Hello Ktor WS", peerCreated["payload"]?.jsonObject?.get("content")?.jsonPrimitive?.content)

            val peerTyping = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("typing", peerTyping["type"]?.jsonPrimitive?.content)

            val peerReceipt = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("receipt", peerReceipt["type"]?.jsonPrimitive?.content)
        }
    }
}
