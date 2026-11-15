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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
                assertEquals(42L, senderPresence["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)

                val initialPeerPresence = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("presence", initialPeerPresence["type"]?.jsonPrimitive?.content)
                assertEquals(99L, initialPeerPresence["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)
                assertEquals("ONLINE", initialPeerPresence["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

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
            saveMessage(
                senderId = 99L,
                payload = MessageSendPayload("msg-monotonic-1", 101L, "TEXT", "Hello"),
                nowIso = "2026-11-13T11:29:00Z"
            )
        }
        application { module(testConfig, chatRepositoryOverride = repo) }
        val token42 = issueTestToken(42L)

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/chat", request = {
            header(HttpHeaders.Authorization, "Bearer $token42")
        }) {
            // Presence ONLINE
            incoming.receive()

            send(Frame.Text("""{"type":"receipt","requestId":"r1","timestamp":"2026-11-13T11:30:00Z","payload":{"conversationId":101,"messageId":1,"status":"READ"}}"""))
            val first = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("READ", first["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            val firstAt = first["payload"]?.jsonObject?.get("at")?.jsonPrimitive?.content

            // Sending DELIVERED after READ must NOT downgrade READ -> DELIVERED or overwrite readAt
            send(Frame.Text("""{"type":"receipt","requestId":"r2","timestamp":"2026-11-13T11:30:01Z","payload":{"conversationId":101,"messageId":1,"status":"DELIVERED"}}"""))
            val second = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("READ", second["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            assertEquals(firstAt, second["payload"]?.jsonObject?.get("at")?.jsonPrimitive?.content)
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

    @Test
    fun directConversationsAndListExposePeerPresenceAndEmitWsPresence() = testApplication {
        val repo = InMemoryChatRepository()
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token42 = issueTestToken(42L)
        val token99 = issueTestToken(99L)
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token42") }) {
            incoming.receive() // self 42 ONLINE

            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token99") }) {
                incoming.receive() // self 99 ONLINE

                // Both users are connected via WS before creating direct conversation -> both receive presence
                val createResp = client.post("/conversations/direct") {
                    header(HttpHeaders.Authorization, "Bearer $token42")
                    contentType(ContentType.Application.Json)
                    setBody("""{"targetUserId":99}""")
                }
                assertEquals(HttpStatusCode.OK, createResp.status)
                val createdJson = AppJson.parseToJsonElement(createResp.bodyAsText()).jsonObject
                assertEquals(99L, createdJson["peerUserId"]?.jsonPrimitive?.long)
                assertEquals(true, createdJson["isOnline"]?.jsonPrimitive?.boolean)
                assertTrue(createdJson["peerLastSeenAt"]?.jsonPrimitive?.content?.isNotBlank() == true)

                val peer99Frame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("presence", peer99Frame["type"]?.jsonPrimitive?.content)
                assertEquals(42L, peer99Frame["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)
                assertEquals("ONLINE", peer99Frame["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

                val listWhileOnline = client.get("/conversations") {
                    header(HttpHeaders.Authorization, "Bearer $token42")
                }
                assertEquals(HttpStatusCode.OK, listWhileOnline.status)
                val onlineConv = AppJson.parseToJsonElement(listWhileOnline.bodyAsText()).jsonObject["items"]!!.jsonArray[0].jsonObject
                assertEquals(99L, onlineConv["peerUserId"]?.jsonPrimitive?.long)
                assertEquals(true, onlineConv["isOnline"]?.jsonPrimitive?.boolean)
                assertTrue(onlineConv["peerLastSeenAt"]?.jsonPrimitive?.content?.isNotBlank() == true)
            }

            // Inside 42's WS: receives 99's presence from POST /conversations/direct, then 99's OFFLINE disconnect
            val frameFor42FromPost = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("presence", frameFor42FromPost["type"]?.jsonPrimitive?.content)
            assertEquals(99L, frameFor42FromPost["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)
            assertEquals("ONLINE", frameFor42FromPost["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            val offlineFor42 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("presence", offlineFor42["type"]?.jsonPrimitive?.content)
            assertEquals(99L, offlineFor42["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)
            assertEquals("OFFLINE", offlineFor42["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

            // Now 99 is offline -> GET /conversations reflects isOnline = false and peerLastSeenAt
            val listAfterDisconnect = client.get("/conversations") {
                header(HttpHeaders.Authorization, "Bearer $token42")
            }
            assertEquals(HttpStatusCode.OK, listAfterDisconnect.status)
            val offlineConv = AppJson.parseToJsonElement(listAfterDisconnect.bodyAsText()).jsonObject["items"]!!.jsonArray[0].jsonObject
            assertEquals(99L, offlineConv["peerUserId"]?.jsonPrimitive?.long)
            assertEquals(false, offlineConv["isOnline"]?.jsonPrimitive?.boolean)
            assertEquals(
                offlineFor42["payload"]?.jsonObject?.get("lastSeenAt")?.jsonPrimitive?.content,
                offlineConv["peerLastSeenAt"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun webSocketConnectPushesInitialOnlineAndOfflinePeerPresenceAndDisconnectUpdatesLastSeen() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L))
            seedConversation(conversationId = 102L, memberIds = setOf(10L, 30L))
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val wsClient = createClient { install(ClientWebSockets) }
        val token10 = issueTestToken(10L)
        val token20 = issueTestToken(20L)
        val token30 = issueTestToken(30L)

        // Peer 20 connects and disconnects so it has a stored lastSeenAt while offline
        var connected20LastSeen: String? = null
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token20") }) {
            incoming.receive() // self 20 ONLINE
            connected20LastSeen = repo.getLastSeenMap(listOf(20L))[20L]
            kotlinx.coroutines.delay(15)
        }
        var stored20LastSeen: String? = null
        repeat(50) {
            val current = repo.getLastSeenMap(listOf(20L))[20L]
            if (current != null && current != connected20LastSeen) {
                stored20LastSeen = current
                return@repeat
            }
            kotlinx.coroutines.delay(10)
        }
        assertTrue(!stored20LastSeen.isNullOrBlank())

        // Peer 30 stays online
        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token30") }) {
            incoming.receive() // self 30 ONLINE

            // User 10 connects -> receives self ONLINE, peer 20 OFFLINE (with stored lastSeenAt), and peer 30 ONLINE
            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token10") }) {
                val received = (1..3).map {
                    AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                }
                val byUser = received.associateBy { it["payload"]!!.jsonObject["userId"]!!.jsonPrimitive.long }
                assertEquals("ONLINE", byUser[10L]?.get("payload")?.jsonObject?.get("status")?.jsonPrimitive?.content)
                assertEquals("OFFLINE", byUser[20L]?.get("payload")?.jsonObject?.get("status")?.jsonPrimitive?.content)
                assertEquals(stored20LastSeen, byUser[20L]?.get("payload")?.jsonObject?.get("lastSeenAt")?.jsonPrimitive?.content)
                assertEquals("ONLINE", byUser[30L]?.get("payload")?.jsonObject?.get("status")?.jsonPrimitive?.content)
            }

            // Peer 30 sees 10 ONLINE then 10 OFFLINE on disconnect, and repo.lastSeenAt for 10 is updated
            val online10 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ONLINE", online10["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            val offline10 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("OFFLINE", offline10["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
            assertEquals(
                offline10["payload"]?.jsonObject?.get("lastSeenAt")?.jsonPrimitive?.content,
                repo.getLastSeenMap(listOf(10L))[10L]
            )
        }
    }

    @Test
    fun messagesEndpointAggregatesDeliveryStatusAndHandlesMissingReceiptMessageId() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(42L, 99L))
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token42 = issueTestToken(42L)
        val token99 = issueTestToken(99L)
        val wsClient = createClient { install(ClientWebSockets) }

        suspend fun fetchSingleStatus(): String? {
            val resp = client.get("/conversations/101/messages") {
                header(HttpHeaders.Authorization, "Bearer $token42")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val items = AppJson.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
            return items.single().jsonObject["deliveryStatus"]?.jsonPrimitive?.content
        }

        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token42") }) {
            incoming.receive() // self 42 ONLINE

            send(
                Frame.Text(
                    """{"type":"message.send","requestId":"m1","timestamp":"2026-11-14T10:00:00Z","payload":{"clientMessageId":"uuid-agg-1","conversationId":101,"type":"TEXT","content":"Track receipts"}}"""
                )
            )
            val ack = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            val msgId = ack["payload"]!!.jsonObject["messageId"]!!.jsonPrimitive.long
            val created = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ACCEPTED", created["payload"]?.jsonObject?.get("deliveryStatus")?.jsonPrimitive?.content)
            assertEquals("ACCEPTED", fetchSingleStatus())

            // Sender's own receipt must not advance recipient deliveryStatus
            send(Frame.Text("""{"type":"receipt","requestId":"r-self","timestamp":"2026-11-14T10:00:01Z","payload":{"conversationId":101,"messageId":$msgId,"status":"READ"}}"""))
            incoming.receive() // self receipt echo
            assertEquals("ACCEPTED", fetchSingleStatus())

            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token99") }) {
                incoming.receive() // self 99 ONLINE
                incoming.receive() // peer 42 ONLINE

                // Missing messageId returns NOT_FOUND error frame cleanly
                send(Frame.Text("""{"type":"receipt","requestId":"r-missing","timestamp":"2026-11-14T10:00:02Z","payload":{"conversationId":101,"messageId":99999,"status":"DELIVERED"}}"""))
                val errFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("error", errFrame["type"]?.jsonPrimitive?.content)
                assertEquals("NOT_FOUND", errFrame["payload"]?.jsonObject?.get("code")?.jsonPrimitive?.content)

                // Peer sends DELIVERED -> aggregated deliveryStatus becomes DELIVERED
                send(Frame.Text("""{"type":"receipt","requestId":"r-deliv","timestamp":"2026-11-14T10:00:03Z","payload":{"conversationId":101,"messageId":$msgId,"status":"DELIVERED"}}"""))
                val delivFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("DELIVERED", delivFrame["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
                assertEquals("DELIVERED", fetchSingleStatus())

                // Peer sends READ -> aggregated deliveryStatus becomes READ
                send(Frame.Text("""{"type":"receipt","requestId":"r-read","timestamp":"2026-11-14T10:00:04Z","payload":{"conversationId":101,"messageId":$msgId,"status":"READ"}}"""))
                val readFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("READ", readFrame["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
                assertEquals("READ", fetchSingleStatus())
            }
        }
    }

    @Test
    fun deleteMessageHidesMessageOnlyForRequesterAndBroadcastsWsMessageDeleted() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L), title = "Delete Test")
            saveMessage(10L, MessageSendPayload("del-1", 101L, "TEXT", "Keep hello"), "2026-11-14T10:00:01Z")
            saveMessage(20L, MessageSendPayload("del-2", 101L, "TEXT", "Secret hello"), "2026-11-14T10:00:02Z")
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token10 = issueTestToken(10L)
        val token20 = issueTestToken(20L)
        val tokenOutsider = issueTestToken(999L)
        val wsClient = createClient { install(ClientWebSockets) }

        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/messages/2") { header(HttpHeaders.Authorization, "Bearer $tokenOutsider") }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/messages/9999") { header(HttpHeaders.Authorization, "Bearer $token10") }.status
        )

        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token10") }) {
            incoming.receive() // self 10 ONLINE

            val delResp = client.delete("/messages/2") {
                header(HttpHeaders.Authorization, "Bearer $token10")
            }
            assertEquals(HttpStatusCode.NoContent, delResp.status)

            val wsFrame = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.deleted", wsFrame["type"]?.jsonPrimitive?.content)
            assertEquals(101L, wsFrame["payload"]?.jsonObject?.get("conversationId")?.jsonPrimitive?.long)
            assertEquals(2L, wsFrame["payload"]?.jsonObject?.get("messageId")?.jsonPrimitive?.long)
            assertEquals(10L, wsFrame["payload"]?.jsonObject?.get("userId")?.jsonPrimitive?.long)

            // Idempotent second delete still returns 204
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/messages/2") { header(HttpHeaders.Authorization, "Bearer $token10") }.status
            )
        }

        // User 10 no longer sees message 2 in listMessages, search, or conversation lastMessage
        val history10 = AppJson.parseToJsonElement(
            client.get("/conversations/101/messages") {
                header(HttpHeaders.Authorization, "Bearer $token10")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(listOf(1L), history10.map { it.jsonObject["id"]!!.jsonPrimitive.long })

        val search10 = AppJson.parseToJsonElement(
            client.get("/messages/search?q=hello") {
                header(HttpHeaders.Authorization, "Bearer $token10")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(listOf(1L), search10.map { it.jsonObject["id"]!!.jsonPrimitive.long })

        val convs10 = AppJson.parseToJsonElement(
            client.get("/conversations") {
                header(HttpHeaders.Authorization, "Bearer $token10")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(1L, convs10.single().jsonObject["lastMessage"]!!.jsonObject["id"]!!.jsonPrimitive.long)

        // User 20 still sees message 2 everywhere
        val history20 = AppJson.parseToJsonElement(
            client.get("/conversations/101/messages") {
                header(HttpHeaders.Authorization, "Bearer $token20")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(listOf(1L, 2L), history20.map { it.jsonObject["id"]!!.jsonPrimitive.long })

        val convs20 = AppJson.parseToJsonElement(
            client.get("/conversations") {
                header(HttpHeaders.Authorization, "Bearer $token20")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(2L, convs20.single().jsonObject["lastMessage"]!!.jsonObject["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun reactionsAndForwardedFromIdAreHydratedAndRecalledReactionsAreBlocked() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L))
            seedConversation(conversationId = 202L, memberIds = setOf(10L, 30L))
            saveMessage(
                senderId = 10L,
                payload = MessageSendPayload(
                    clientMessageId = "orig-1",
                    conversationId = 101L,
                    type = "TEXT",
                    content = "Hydrate me",
                    mediaRef = 77L,
                    metadata = buildJsonObject { put("source", "unit-test") }
                ),
                nowIso = "2026-11-14T10:10:00Z"
            )
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token10 = issueTestToken(10L)
        val token20 = issueTestToken(20L)

        // Reply validation: replyToId from conversation 101 cannot be used in conversation 202
        val crossReplyResult = repo.saveMessage(
            senderId = 10L,
            payload = MessageSendPayload(
                clientMessageId = "bad-reply-1",
                conversationId = 202L,
                type = "TEXT",
                content = "Cross reply",
                replyToId = 1L
            ),
            nowIso = "2026-11-14T10:10:01Z"
        )
        assertEquals("VALIDATION_ERROR", crossReplyResult.errorCode)

        // React on message 1 from both users
        assertEquals(
            HttpStatusCode.OK,
            client.post("/messages/1/reactions") {
                header(HttpHeaders.Authorization, "Bearer $token10")
                contentType(ContentType.Application.Json)
                setBody("""{"emoji":"👍"}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/messages/1/reactions") {
                header(HttpHeaders.Authorization, "Bearer $token20")
                contentType(ContentType.Application.Json)
                setBody("""{"emoji":"👍"}""")
            }.status
        )

        // Forward message 1 to conversation 202 idempotently
        val fwdResp1 = client.post("/messages/1/forward") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"targetConversationId":202,"clientMessageId":"fwd-hydrate-1"}""")
        }
        assertEquals(HttpStatusCode.Created, fwdResp1.status)
        val fwdJson1 = AppJson.parseToJsonElement(fwdResp1.bodyAsText()).jsonObject
        val fwdMsgId = fwdJson1["id"]!!.jsonPrimitive.long
        assertEquals(1L, fwdJson1["forwardedFromId"]!!.jsonPrimitive.long)
        assertEquals(77L, fwdJson1["mediaRef"]!!.jsonPrimitive.long)
        assertEquals("unit-test", fwdJson1["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)

        val fwdResp2 = client.post("/messages/1/forward") {
            header(HttpHeaders.Authorization, "Bearer $token10")
            contentType(ContentType.Application.Json)
            setBody("""{"targetConversationId":202,"clientMessageId":"fwd-hydrate-1"}""")
        }
        assertEquals(HttpStatusCode.Created, fwdResp2.status)
        assertEquals(fwdMsgId, AppJson.parseToJsonElement(fwdResp2.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long)

        // Hydrated reactions on listMessages, searchMessages, editMessage, and listConversations
        val list101 = AppJson.parseToJsonElement(
            client.get("/conversations/101/messages") { header(HttpHeaders.Authorization, "Bearer $token10") }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(listOf(10L, 20L), list101["reactions"]!!.jsonObject["👍"]!!.jsonArray.map { it.jsonPrimitive.long })

        val search101 = AppJson.parseToJsonElement(
            client.get("/messages/search?q=Hydrate") { header(HttpHeaders.Authorization, "Bearer $token10") }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        assertEquals(2, search101.size)
        val searchOrig = search101.first { it.jsonObject["id"]!!.jsonPrimitive.long == 1L }.jsonObject
        val searchFwd = search101.first { it.jsonObject["id"]!!.jsonPrimitive.long == fwdMsgId }.jsonObject
        assertEquals(listOf(10L, 20L), searchOrig["reactions"]!!.jsonObject["👍"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(1L, searchFwd["forwardedFromId"]!!.jsonPrimitive.long)

        val edited = AppJson.parseToJsonElement(
            client.patch("/messages/1") {
                header(HttpHeaders.Authorization, "Bearer $token10")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"Hydrate edited"}""")
            }.bodyAsText()
        ).jsonObject
        assertEquals(listOf(10L, 20L), edited["reactions"]!!.jsonObject["👍"]!!.jsonArray.map { it.jsonPrimitive.long })

        val convs = AppJson.parseToJsonElement(
            client.get("/conversations") { header(HttpHeaders.Authorization, "Bearer $token10") }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray
        val conv101Last = convs.first { it.jsonObject["id"]!!.jsonPrimitive.long == 101L }.jsonObject["lastMessage"]!!.jsonObject
        val conv202Last = convs.first { it.jsonObject["id"]!!.jsonPrimitive.long == 202L }.jsonObject["lastMessage"]!!.jsonObject
        assertEquals(listOf(10L, 20L), conv101Last["reactions"]!!.jsonObject["👍"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(1L, conv202Last["forwardedFromId"]!!.jsonPrimitive.long)

        // Recall message 1 -> hydrated reactions preserved, further toggleReaction blocked with 409 CONFLICT
        val recalled = AppJson.parseToJsonElement(
            client.post("/messages/1/recall") { header(HttpHeaders.Authorization, "Bearer $token10") }.bodyAsText()
        ).jsonObject
        assertEquals(listOf(10L, 20L), recalled["reactions"]!!.jsonObject["👍"]!!.jsonArray.map { it.jsonPrimitive.long })

        val reactOnRecalled = client.post("/messages/1/reactions") {
            header(HttpHeaders.Authorization, "Bearer $token20")
            contentType(ContentType.Application.Json)
            setBody("""{"emoji":"😂"}""")
        }
        assertEquals(HttpStatusCode.Conflict, reactOnRecalled.status)
    }

    @Test
    fun pinAndUnpinMessageValidateAndBroadcastWsAndSortPinnedConversations() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedConversation(conversationId = 101L, memberIds = setOf(10L, 20L))
            seedConversation(conversationId = 102L, memberIds = setOf(10L, 20L))
            saveMessage(10L, MessageSendPayload("p1", 101L, "TEXT", "Pin me"), "2026-11-14T10:20:00Z")
            saveMessage(10L, MessageSendPayload("p2", 101L, "TEXT", "Recalled msg"), "2026-11-14T10:20:01Z")
            saveMessage(10L, MessageSendPayload("p3", 102L, "TEXT", "Newer conv"), "2026-11-14T10:30:00Z")
            recallMessage(10L, 2L, "2026-11-14T10:20:02Z")
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token10 = issueTestToken(10L)
        val token20 = issueTestToken(20L)
        val wsClient = createClient { install(ClientWebSockets) }

        // Missing or recalled message cannot be pinned
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/conversations/101/pinned-messages/9999") { header(HttpHeaders.Authorization, "Bearer $token10") }.status
        )
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/conversations/101/pinned-messages/2") { header(HttpHeaders.Authorization, "Bearer $token10") }.status
        )

        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token20") }) {
            incoming.receive() // self 20 ONLINE

            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token10") }) {
                incoming.receive() // self 10 ONLINE
                incoming.receive() // peer 20 ONLINE

                assertEquals(
                    HttpStatusCode.OK,
                    client.post("/conversations/101/pinned-messages/1") {
                        header(HttpHeaders.Authorization, "Bearer $token10")
                    }.status
                )
                val pinFrame10 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("message.pinned", pinFrame10["type"]?.jsonPrimitive?.content)
                assertEquals(true, pinFrame10["payload"]?.jsonObject?.get("pinned")?.jsonPrimitive?.boolean)
                assertEquals(listOf(1L), pinFrame10["payload"]?.jsonObject?.get("pinnedMessageIds")?.jsonArray?.map { it.jsonPrimitive.long })

                assertEquals(
                    HttpStatusCode.NoContent,
                    client.delete("/conversations/101/pinned-messages/1") {
                        header(HttpHeaders.Authorization, "Bearer $token10")
                    }.status
                )
                val unpinFrame10 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("message.pinned", unpinFrame10["type"]?.jsonPrimitive?.content)
                assertEquals(false, unpinFrame10["payload"]?.jsonObject?.get("pinned")?.jsonPrimitive?.boolean)
                assertTrue(unpinFrame10["payload"]?.jsonObject?.get("pinnedMessageIds")?.jsonArray?.isEmpty() == true)
            }

            incoming.receive() // peer 10 ONLINE
            val pinFrame20 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.pinned", pinFrame20["type"]?.jsonPrimitive?.content)
            assertEquals(true, pinFrame20["payload"]?.jsonObject?.get("pinned")?.jsonPrimitive?.boolean)

            val unpinFrame20 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("message.pinned", unpinFrame20["type"]?.jsonPrimitive?.content)
            assertEquals(false, unpinFrame20["payload"]?.jsonObject?.get("pinned")?.jsonPrimitive?.boolean)
        }

        // Pin older conversation 101 for user 10 -> user 10 sees 101 before 102; user 20 still sees 102 before 101
        assertEquals(
            HttpStatusCode.OK,
            client.patch("/conversations/101/pin") {
                header(HttpHeaders.Authorization, "Bearer $token10")
                contentType(ContentType.Application.Json)
                setBody("""{"pinned":true}""")
            }.status
        )
        val order10 = AppJson.parseToJsonElement(
            client.get("/conversations") { header(HttpHeaders.Authorization, "Bearer $token10") }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
        assertEquals(listOf(101L, 102L), order10)

        val order20 = AppJson.parseToJsonElement(
            client.get("/conversations") { header(HttpHeaders.Authorization, "Bearer $token20") }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
        assertEquals(listOf(102L, 101L), order20)
    }

    @Test
    fun groupMembersExposeDisplayNameEnforceGroupTypeAndBroadcastWsChanges() = testApplication {
        val repo = InMemoryChatRepository().apply {
            seedUserProfile(1L, "Alice", "https://cdn.example/1.png")
            seedUserProfile(2L, "Bob")
            seedUserProfile(3L, "Charlie")
            seedUserProfile(4L, "Diana")
        }
        application { module(testConfig, chatRepositoryOverride = repo) }

        val token1 = issueTestToken(1L)
        val token4 = issueTestToken(4L)
        val wsClient = createClient { install(ClientWebSockets) }

        // Direct conversation rejects member management with 400
        val directId = AppJson.parseToJsonElement(
            client.post("/conversations/direct") {
                header(HttpHeaders.Authorization, "Bearer $token1")
                contentType(ContentType.Application.Json)
                setBody("""{"targetUserId":2}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.long
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/conversations/$directId/members") {
                header(HttpHeaders.Authorization, "Bearer $token1")
                contentType(ContentType.Application.Json)
                setBody("""{"userIds":[4]}""")
            }.status
        )

        // Create group conversation and verify displayName + avatarUrl in listMembers
        val groupId = AppJson.parseToJsonElement(
            client.post("/conversations/group") {
                header(HttpHeaders.Authorization, "Bearer $token1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Study Group","memberIds":[2,3]}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.long

        val membersJson = AppJson.parseToJsonElement(
            client.get("/conversations/$groupId/members") {
                header(HttpHeaders.Authorization, "Bearer $token1")
            }.bodyAsText()
        ).jsonArray
        val byUserId = membersJson.associateBy { it.jsonObject["userId"]!!.jsonPrimitive.long }
        assertEquals("Alice", byUserId[1L]?.jsonObject?.get("displayName")?.jsonPrimitive?.content)
        assertEquals("https://cdn.example/1.png", byUserId[1L]?.jsonObject?.get("avatarUrl")?.jsonPrimitive?.content)
        assertEquals("Bob", byUserId[2L]?.jsonObject?.get("displayName")?.jsonPrimitive?.content)
        assertEquals("Charlie", byUserId[3L]?.jsonObject?.get("displayName")?.jsonPrimitive?.content)

        wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token4") }) {
            incoming.receive() // self 4 ONLINE

            wsClient.webSocket("/ws/chat", request = { header(HttpHeaders.Authorization, "Bearer $token1") }) {
                incoming.receive() // self 1 ONLINE

                // Add member 4 -> ADDED broadcast to members + user 4
                val addResp = client.post("/conversations/$groupId/members") {
                    header(HttpHeaders.Authorization, "Bearer $token1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userIds":[4]}""")
                }
                assertEquals(HttpStatusCode.OK, addResp.status)
                val addedMembers = AppJson.parseToJsonElement(addResp.bodyAsText()).jsonArray
                assertEquals("Diana", addedMembers.first { it.jsonObject["userId"]!!.jsonPrimitive.long == 4L }.jsonObject["displayName"]?.jsonPrimitive?.content)

                val addFrame1 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("group.members_changed", addFrame1["type"]?.jsonPrimitive?.content)
                assertEquals("ADDED", addFrame1["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
                assertEquals(listOf(4L), addFrame1["payload"]?.jsonObject?.get("targetUserIds")?.jsonArray?.map { it.jsonPrimitive.long })

                // Promote user 4 -> ROLE_UPDATED broadcast
                val promoteResp = client.patch("/conversations/$groupId/members/4") {
                    header(HttpHeaders.Authorization, "Bearer $token1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"ADMIN"}""")
                }
                assertEquals(HttpStatusCode.OK, promoteResp.status)
                val roleFrame1 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("group.members_changed", roleFrame1["type"]?.jsonPrimitive?.content)
                assertEquals("ROLE_UPDATED", roleFrame1["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)

                // Remove user 4 -> REMOVED broadcast (including to removed user 4)
                val removeResp = client.delete("/conversations/$groupId/members/4") {
                    header(HttpHeaders.Authorization, "Bearer $token1")
                }
                assertEquals(HttpStatusCode.NoContent, removeResp.status)
                val removeFrame1 = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("group.members_changed", removeFrame1["type"]?.jsonPrimitive?.content)
                assertEquals("REMOVED", removeFrame1["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)

                // Re-adding removed ADMIN user 4 resets role to MEMBER
                val reAddResp = client.post("/conversations/$groupId/members") {
                    header(HttpHeaders.Authorization, "Bearer $token1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userIds":[4]}""")
                }
                assertEquals(HttpStatusCode.OK, reAddResp.status)
                val reAdded = AppJson.parseToJsonElement(reAddResp.bodyAsText()).jsonArray
                    .first { it.jsonObject["userId"]!!.jsonPrimitive.long == 4L }.jsonObject
                assertEquals("MEMBER", reAdded["role"]?.jsonPrimitive?.content)
            }

            // User 4 receives ADDED, ROLE_UPDATED, REMOVED, and re-ADDED frames
            val frameAdded = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ADDED", frameAdded["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
            val frameRole = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ROLE_UPDATED", frameRole["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
            val frameRemoved = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("REMOVED", frameRemoved["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
            val frameReAdded = AppJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("ADDED", frameReAdded["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        }
    }
}
