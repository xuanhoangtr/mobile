package vn.edu.hcmute.androidchat.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import vn.edu.hcmute.androidchat.backend.chat.InMemoryChatRepository
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D31-D35 acceptance & regression tests for CHAT module (§4 items 3, 4, 8).
 */
class ChatRegressionD31D35Test {
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

    private fun issueToken(userId: Long): String =
        JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject(userId.toString())
            .sign(Algorithm.HMAC256(testConfig.jwtSecret))

    @Test
    fun removedGroupMemberImmediatelyLosesRestAndWebSocketAccess() = testApplication {
        val repo = InMemoryChatRepository()
        application { module(testConfig, chatRepositoryOverride = repo) }

        val tokenOwner = issueToken(1L)
        val tokenRemoved = issueToken(3L)

        // Create 3-member group (1, 2, 3)
        val created = client.post("/conversations/group") {
            header(HttpHeaders.Authorization, "Bearer $tokenOwner")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Regression Group","memberIds":[2,3]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val groupId = AppJson.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        // Owner removes member 3
        val removeResp = client.delete("/conversations/$groupId/members/3") {
            header(HttpHeaders.Authorization, "Bearer $tokenOwner")
        }
        assertEquals(HttpStatusCode.NoContent, removeResp.status)

        // Removed member 3 cannot read history or search messages
        val historyResp = client.get("/conversations/$groupId/messages") {
            header(HttpHeaders.Authorization, "Bearer $tokenRemoved")
        }
        assertEquals(HttpStatusCode.Forbidden, historyResp.status)

        val searchResp = client.get("/messages/search?conversationId=$groupId&q=test") {
            header(HttpHeaders.Authorization, "Bearer $tokenRemoved")
        }
        assertEquals(HttpStatusCode.Forbidden, searchResp.status)

        // Removed member 3 cannot send message over WebSocket
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $tokenRemoved") }) {
            incoming.receive() // self presence
            send(
                Frame.Text(
                    """{"type":"message.send","requestId":"r-rem","timestamp":"2026-11-13T12:00:00Z","payload":{"clientMessageId":"rem-1","conversationId":$groupId,"type":"TEXT","content":"Should fail"}}"""
                )
            )
            val errFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("error", errFrame["type"]?.jsonPrimitive?.content)
            assertEquals("FORBIDDEN", errFrame["payload"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun duplicateClientMessageIdAcrossReconnectSessionsDoesNotDuplicateMessage() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 505L, memberIds = setOf(10L, 20L))
        }
        application { module(testConfig, chatRepositoryOverride = repo) }
        val token10 = issueToken(10L)
        val wsClient = createClient { install(ClientWebSockets) }

        val frameText = """{"type":"message.send","requestId":"r-1","timestamp":"2026-11-13T12:01:00Z","payload":{"clientMessageId":"same-uuid-across-reconnect","conversationId":505,"type":"TEXT","content":"Idempotent hello"}}"""
        var firstMessageId = 0L

        // Session 1: send message
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token10") }) {
            incoming.receive() // presence
            send(Frame.Text(frameText))
            val ack = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ACCEPTED", ack["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            firstMessageId = ack["payload"]!!.jsonObject["messageId"]!!.jsonPrimitive.long
            incoming.receive() // message.created
        }

        // Session 2 (after reconnect): resend identical clientMessageId
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token10") }) {
            incoming.receive() // presence
            send(Frame.Text(frameText))
            val ack2 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("DUPLICATE", ack2["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            assertEquals(firstMessageId, ack2["payload"]!!.jsonObject["messageId"]!!.jsonPrimitive.long)
        }

        // History must contain exactly 1 message
        val history = client.get("/conversations/505/messages") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, history.status)
        val items = AppJson.parseToJsonElement(history.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
    }

    @Test
    fun unauthenticatedRequestsToChatEndpointsReturn401() = testApplication {
        application { module(testConfig) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/conversations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/conversations/direct").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/conversations/group").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/messages/search?q=hi").status)
    }
}
