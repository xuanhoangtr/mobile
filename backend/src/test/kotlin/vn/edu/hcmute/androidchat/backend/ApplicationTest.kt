package vn.edu.hcmute.androidchat.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import vn.edu.hcmute.androidchat.backend.chat.InMemoryChatRepository
import vn.edu.hcmute.androidchat.backend.chat.MessageSendPayload
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

    private fun issueTestToken(userId: Long, sessionId: UUID? = UUID.randomUUID()): String =
        JWT.create()
            .withIssuer(testConfig.jwtIssuer)
            .withAudience(testConfig.jwtAudience)
            .withSubject(userId.toString())
            .apply { if (sessionId != null) withClaim("sid", sessionId.toString()) }
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

            // Peer 99 receives presence ONLINE for user 42, then message.created, typing, and receipt
            val memberPresence = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("presence", memberPresence["type"]?.jsonPrimitive?.content)
            assertEquals("ONLINE", memberPresence["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            val peerCreated = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.created", peerCreated["type"]?.jsonPrimitive?.content)
            assertEquals("Hello Ktor WS", peerCreated["payload"]?.jsonObject?.get("content")?.jsonPrimitive?.content)

            val peerTyping = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("typing", peerTyping["type"]?.jsonPrimitive?.content)

            val peerReceipt = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("receipt", peerReceipt["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun directConversationIsIdempotentAndEnforcesTwoMemberAccess() = testApplication {
        val repo = InMemoryChatRepository()
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token42 = issueTestToken(42L)
        val token99 = issueTestToken(99L)
        val tokenOutsider = issueTestToken(777L)

        // Self-chat must be rejected
        val selfResp = client.post("/conversations/direct") {
            header(HttpHeaders.Authorization, "Bearer $token42")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":42}""")
        }
        assertEquals(HttpStatusCode.BadRequest, selfResp.status)

        // User 42 creates direct conversation with user 99
        val firstResp = client.post("/conversations/direct") {
            header(HttpHeaders.Authorization, "Bearer $token42")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":99}""")
        }
        assertEquals(HttpStatusCode.OK, firstResp.status)
        val firstJson = AppJson.parseToJsonElement(firstResp.bodyAsText()).jsonObject
        val convId = firstJson["id"]!!.jsonPrimitive.long
        assertEquals("DIRECT", firstJson["type"]?.jsonPrimitive?.content)

        // User 99 requesting direct conversation with 42 returns the exact same conversationId
        val secondResp = client.post("/conversations/direct") {
            header(HttpHeaders.Authorization, "Bearer $token99")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":42}""")
        }
        assertEquals(HttpStatusCode.OK, secondResp.status)
        val secondJson = AppJson.parseToJsonElement(secondResp.bodyAsText()).jsonObject
        assertEquals(convId, secondJson["id"]!!.jsonPrimitive.long)

        // Outsider 777 is forbidden from reading messages
        val outsiderResp = client.get("/conversations/$convId/messages") {
            header(HttpHeaders.Authorization, "Bearer $tokenOutsider")
        }
        assertEquals(HttpStatusCode.Forbidden, outsiderResp.status)
    }

    @Test
    fun messagesEndpointSupportsCursorPaginationWithoutDuplicates() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 202L, memberIds = setOf(10L, 20L), title = "Paginated")
            for (i in 1..5) {
                saveMessage(
                    senderId = if (i % 2 == 0) 20L else 10L,
                    payload = MessageSendPayload(
                        clientMessageId = "client-msg-$i",
                        conversationId = 202L,
                        type = "TEXT",
                        content = "msg-$i"
                    ),
                    nowIso = "2026-11-13T11:00:0${i}Z"
                )
            }
        }
        application { module(testConfig, chatRepositoryOverride = repo) }
        val token10 = issueTestToken(10L)

        // Page 1 (newest 2 messages: msg-4, msg-5)
        val page1Resp = client.get("/conversations/202/messages?limit=2") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, page1Resp.status)
        val page1 = AppJson.parseToJsonElement(page1Resp.bodyAsText()).jsonObject
        assertTrue(page1["hasMore"]!!.jsonPrimitive.boolean)
        val cursor1 = page1["nextCursor"]!!.jsonPrimitive.content
        val items1 = page1["items"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("msg-4", "msg-5"), items1)

        // Page 2 (older 2 messages: msg-2, msg-3)
        val page2Resp = client.get("/conversations/202/messages?cursor=$cursor1&limit=2") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, page2Resp.status)
        val page2 = AppJson.parseToJsonElement(page2Resp.bodyAsText()).jsonObject
        assertTrue(page2["hasMore"]!!.jsonPrimitive.boolean)
        val cursor2 = page2["nextCursor"]!!.jsonPrimitive.content
        val items2 = page2["items"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("msg-2", "msg-3"), items2)

        // Page 3 (oldest 1 message: msg-1)
        val page3Resp = client.get("/conversations/202/messages?cursor=$cursor2&limit=2") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, page3Resp.status)
        val page3 = AppJson.parseToJsonElement(page3Resp.bodyAsText()).jsonObject
        assertEquals(false, page3["hasMore"]!!.jsonPrimitive.boolean)
        val items3 = page3["items"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("msg-1"), items3)
    }

    @Test
    fun groupChatCreationRequiresThreeMembersAndEnforcesPermissions() = testApplication {
        val repo = InMemoryChatRepository()
        application { module(testConfig, chatRepositoryOverride = repo) }

        val tokenOwner = issueTestToken(1L)
        val tokenAdminCandidate = issueTestToken(2L)
        val tokenMember = issueTestToken(3L)
        val tokenNewbie = issueTestToken(4L)

        // Fewer than 3 members -> 400
        val tooSmall = client.post("/conversations/group") {
            header(HttpHeaders.Authorization, "Bearer $tokenOwner")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Tiny","memberIds":[2]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, tooSmall.status)

        // Create group with 3 members (1, 2, 3) -> 201 Created
        val created = client.post("/conversations/group") {
            header(HttpHeaders.Authorization, "Bearer $tokenOwner")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Android UTE","memberIds":[2,3]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val groupId = AppJson.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        // Regular MEMBER (3) cannot add member (4) -> 403
        val forbiddenAdd = client.post("/conversations/$groupId/members") {
            header(HttpHeaders.Authorization, "Bearer $tokenMember")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":[4]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenAdd.status)

        // OWNER (1) promotes user 2 to ADMIN -> 200
        val promoteResp = client.patch("/conversations/$groupId/members/2") {
            header(HttpHeaders.Authorization, "Bearer $tokenOwner")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"ADMIN"}""")
        }
        assertEquals(HttpStatusCode.OK, promoteResp.status)
        assertEquals("ADMIN", AppJson.parseToJsonElement(promoteResp.bodyAsText()).jsonObject["role"]?.jsonPrimitive?.content)

        // ADMIN (2) adds user 4 -> 200
        val addResp = client.post("/conversations/$groupId/members") {
            header(HttpHeaders.Authorization, "Bearer $tokenAdminCandidate")
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":[4]}""")
        }
        assertEquals(HttpStatusCode.OK, addResp.status)
        assertEquals(4, AppJson.parseToJsonElement(addResp.bodyAsText()).jsonArray.size)

        // ADMIN (2) cannot remove OWNER (1) -> 403
        val removeOwner = client.delete("/conversations/$groupId/members/1") {
            header(HttpHeaders.Authorization, "Bearer $tokenAdminCandidate")
        }
        assertEquals(HttpStatusCode.Forbidden, removeOwner.status)

        // ADMIN (2) removes MEMBER (3) -> 204
        val removeMember = client.delete("/conversations/$groupId/members/3") {
            header(HttpHeaders.Authorization, "Bearer $tokenAdminCandidate")
        }
        assertEquals(HttpStatusCode.NoContent, removeMember.status)

        // MEMBER (4) leaves self -> 204
        val selfLeave = client.delete("/conversations/$groupId/members/4") {
            header(HttpHeaders.Authorization, "Bearer $tokenNewbie")
        }
        assertEquals(HttpStatusCode.NoContent, selfLeave.status)
    }

    @Test
    fun webSocketEnforcesMonotonicReceiptsFromReadToDelivered() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(42L, 99L))
        }
        application { module(testConfig, chatRepositoryOverride = repo) }
        val token42 = issueTestToken(42L)

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/chat", request = {
            header(HttpHeaders.Authorization, "Bearer $token42")
        }) {
            // Presence ONLINE
            incoming.receive()

            send(Frame.Text("""{"type":"receipt","requestId":"r1","timestamp":"2026-11-13T11:30:00Z","payload":{"conversationId":101,"messageId":55,"status":"READ"}}"""))
            val first = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("READ", first["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            // Sending DELIVERED after READ must NOT downgrade READ -> DELIVERED
            send(Frame.Text("""{"type":"receipt","requestId":"r2","timestamp":"2026-11-13T11:30:01Z","payload":{"conversationId":101,"messageId":55,"status":"DELIVERED"}}"""))
            val second = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("READ", second["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun messageActionsEditRecallForwardAndReactionsEnforceOwnershipAndMembership() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L))
            seedConversation(conversationId = 202L, memberIds = setOf(10L, 30L))
            saveMessage(
                senderId = 10L,
                payload = MessageSendPayload("msg-edit-1", 101L, "TEXT", "Original text"),
                nowIso = "2026-11-13T11:40:00Z"
            )
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token10 = issueTestToken(10L)
        val token20 = issueTestToken(20L)

        // User 20 cannot edit User 10's message -> 403
        val forbiddenEdit = client.patch("/messages/1") {
            header(HttpHeaders.Authorization, "Bearer $token20")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Hacked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenEdit.status)

        // User 10 edits own message -> 200 with editedAt
        val okEdit = client.patch("/messages/1") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Updated text"}""")
        }
        assertEquals(HttpStatusCode.OK, okEdit.status)
        val editedJson = AppJson.parseToJsonElement(okEdit.bodyAsText()).jsonObject
        assertEquals("Updated text", editedJson["content"]?.jsonPrimitive?.content)
        assertTrue(editedJson["editedAt"] != null)

        // Toggle reaction add & remove
        val reactAdd = client.post("/messages/1/reactions") {
            header(HttpHeaders.Authorization, "Bearer $token20")
            contentType(ContentType.Application.Json)
            setBody("""{"emoji":"👍"}""")
        }
        assertEquals(HttpStatusCode.OK, reactAdd.status)
        val reactAddJson = AppJson.parseToJsonElement(reactAdd.bodyAsText()).jsonObject
        assertTrue(reactAddJson["added"]!!.jsonPrimitive.boolean)

        val reactRemove = client.post("/messages/1/reactions") {
            header(HttpHeaders.Authorization, "Bearer $token20")
            contentType(ContentType.Application.Json)
            setBody("""{"emoji":"👍"}""")
        }
        assertEquals(HttpStatusCode.OK, reactRemove.status)
        assertEquals(false, AppJson.parseToJsonElement(reactRemove.bodyAsText()).jsonObject["added"]!!.jsonPrimitive.boolean)

        // User 20 cannot forward to 202 (not a member of 202) -> 403
        val forbiddenFwd = client.post("/messages/1/forward") {
            header(HttpHeaders.Authorization, "Bearer $token20")
            contentType(ContentType.Application.Json)
            setBody("""{"targetConversationId":202,"clientMessageId":"fwd-1"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenFwd.status)

        // User 10 forwards to 202 -> 201 Created with forwardedFromId = 1
        val okFwd = client.post("/messages/1/forward") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"targetConversationId":202,"clientMessageId":"fwd-2"}""")
        }
        assertEquals(HttpStatusCode.Created, okFwd.status)
        assertEquals(1L, AppJson.parseToJsonElement(okFwd.bodyAsText()).jsonObject["forwardedFromId"]?.jsonPrimitive?.long)

        // User 10 recalls message 1 -> 200, then cannot edit recalled message -> 400
        val okRecall = client.post("/messages/1/recall") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, okRecall.status)
        val editAfterRecall = client.patch("/messages/1") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Too late"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, editAfterRecall.status)
    }

    @Test
    fun searchMessagesAndPinConversationAndPinnedMessagesEnforcePermissions() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L))
            seedConversation(conversationId = 303L, memberIds = setOf(30L, 40L))
            saveMessage(10L, MessageSendPayload("s1", 101L, "TEXT", "Android Kotlin chat"), "2026-11-13T11:50:01Z")
            saveMessage(20L, MessageSendPayload("s2", 101L, "TEXT", "Kotlin coroutines"), "2026-11-13T11:50:02Z")
            saveMessage(30L, MessageSendPayload("s3", 303L, "TEXT", "Kotlin secret other chat"), "2026-11-13T11:50:03Z")
            recallMessage(10L, 1L, "2026-11-13T11:50:04Z") // message 1 recalled -> must not appear in search
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token10 = issueTestToken(10L)

        // Searching all accessible conversations only returns message 2 (excludes recalled msg 1 and outsider msg 3)
        val searchAll = client.get("/messages/search?q=kotlin") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, searchAll.status)
        val items = AppJson.parseToJsonElement(searchAll.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(2L, items[0].jsonObject["id"]!!.jsonPrimitive.long)

        // Searching forbidden conversation 303 -> 403
        val forbiddenSearch = client.get("/messages/search?conversationId=303&q=kotlin") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenSearch.status)

        // Pin conversation 101 -> isPinned = true
        val pinConv = client.patch("/conversations/101/pin") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"pinned":true}""")
        }
        assertEquals(HttpStatusCode.OK, pinConv.status)
        assertTrue(AppJson.parseToJsonElement(pinConv.bodyAsText()).jsonObject["isPinned"]!!.jsonPrimitive.boolean)

        // Pin and unpin message 2 in conversation 101
        val pinMsg = client.post("/conversations/101/pinned-messages/2") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.OK, pinMsg.status)
        val pinnedList = client.get("/conversations/101/pinned-messages") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(1, AppJson.parseToJsonElement(pinnedList.bodyAsText()).jsonArray.size)

        val unpinMsg = client.delete("/conversations/101/pinned-messages/2") {
            header(HttpHeaders.Authorization, "Bearer $token10")
        }
        assertEquals(HttpStatusCode.NoContent, unpinMsg.status)
    }

    @Test
    fun webSocketRelaysCallSignalingBetweenConversationMembers() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(42L, 99L))
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer ${issueTestToken(99L)}") }) {
            incoming.receive() // self presence

            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer ${issueTestToken(42L)}") }) {
                incoming.receive() // self presence
                send(
                    Frame.Text(
                        """{"type":"call.signal","requestId":"call-1","timestamp":"2026-11-13T11:55:00Z","payload":{"conversationId":101,"callId":"c-101","signalType":"OFFER","targetUserId":99,"sdpOrCandidate":"v=0"}}"""
                    )
                )
            }

            incoming.receive() // peer 42 presence ONLINE
            val signalFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("call.signal", signalFrame["type"]?.jsonPrimitive?.content)
            assertEquals(42L, signalFrame["payload"]?.jsonObject?.get("fromUserId")?.jsonPrimitive?.long)
            assertEquals("OFFER", signalFrame["payload"]?.jsonObject?.get("signalType")?.jsonPrimitive?.content)
        }
    }
}
