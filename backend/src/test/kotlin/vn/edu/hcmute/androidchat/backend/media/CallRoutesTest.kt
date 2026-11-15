package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.module
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallRoutesTest {
    private val config = AppConfig("test", 8080, null, null, null, "call-test-key", "call-test", "call-client")

    @Test
    fun calleeCanAcceptAndParticipantCanEndCall() = testApplication {
        val root = Files.createTempDirectory("call-test-")
        val events = mutableListOf<Triple<Set<Long>, String, JsonElement>>()
        try {
            application {
                module(config)
                routing {
                    registerCallRoutes(
                        CallSessionRegistry(
                            onCallEvent = { targets, type, payload ->
                                events.add(Triple(targets, type, payload))
                            }
                        )
                    )
                }
            }
            val caller = token(7)
            val callee = token(8)
            val invite = client.post("/calls/invite") {
                bearerAuth(caller)
                contentType(ContentType.Application.Json)
                setBody("""{"calleeId":8,"media":"audio","conversationId":101}""")
            }
            assertEquals(HttpStatusCode.Created, invite.status)
            val inviteJson = invite.bodyAsText().parseJsonObject()
            val callId = inviteJson["callId"]!!.jsonPrimitive.content
            assertEquals(101L, inviteJson["conversationId"]!!.jsonPrimitive.long)

            val answer = client.post("/calls/answer") {
                bearerAuth(callee)
                contentType(ContentType.Application.Json)
                setBody("""{"callId":"$callId"}""")
            }
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals("ACCEPTED", answer.bodyAsText().parseJsonObject()["state"]!!.jsonPrimitive.content)

            val getCall = client.get("/calls/$callId") { bearerAuth(caller) }
            assertEquals(HttpStatusCode.OK, getCall.status)
            assertEquals("ACCEPTED", getCall.bodyAsText().parseJsonObject()["state"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.OK, client.post("/calls/end") {
                bearerAuth(caller)
                contentType(ContentType.Application.Json)
                setBody("""{"callId":"$callId"}""")
            }.status)

            assertEquals(listOf("call.invite", "call.accepted", "call.ended"), events.map { it.second })
            assertEquals(setOf(8L), events[0].first)
            assertEquals(setOf(7L), events[1].first)
            assertEquals(setOf(8L), events[2].first)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun nonCalleeCannotAnswerCall() = testApplication {
        val root = Files.createTempDirectory("call-forbidden-")
        try {
            application {
                module(config)
                routing { registerCallRoutes(CallSessionRegistry()) }
            }
            val invite = client.post("/calls/invite") {
                bearerAuth(token(7))
                contentType(ContentType.Application.Json)
                setBody("""{"calleeId":8,"media":"video"}""")
            }
            val callId = invite.bodyAsText().parseJsonObject()["callId"]!!.jsonPrimitive.content
            val response = client.post("/calls/answer") {
                bearerAuth(token(9))
                contentType(ContentType.Application.Json)
                setBody("""{"callId":"$callId"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun groupCallSupportsInviteJoinLeaveGetAndEnforcesMembership() = testApplication {
        val membersByConv = mapOf(202L to setOf(1L, 2L, 3L))
        val events = mutableListOf<Triple<Set<Long>, String, JsonElement>>()
        application {
            module(config)
            routing {
                registerCallRoutes(
                    CallSessionRegistry(
                        isConversationMember = { convId, uid -> uid in membersByConv[convId].orEmpty() },
                        onCallEvent = { targets, type, payload ->
                            events.add(Triple(targets, type, payload))
                        }
                    )
                )
            }
        }

        // Non-member cannot start a group call
        val outsiderInvite = client.post("/calls/group/invite") {
            bearerAuth(token(99))
            contentType(ContentType.Application.Json)
            setBody("""{"conversationId":202,"participantIds":[2,3],"media":"video"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, outsiderInvite.status)

        // Member 1 invites 2 and 3
        val inviteResp = client.post("/calls/group/invite") {
            bearerAuth(token(1))
            contentType(ContentType.Application.Json)
            setBody("""{"conversationId":202,"participantIds":[2,3],"media":"video"}""")
        }
        assertEquals(HttpStatusCode.Created, inviteResp.status)
        val inviteJson = inviteResp.bodyAsText().parseJsonObject()
        val callId = inviteJson["callId"]!!.jsonPrimitive.content
        assertEquals("GROUP", inviteJson["mode"]!!.jsonPrimitive.content)
        assertEquals("RINGING", inviteJson["state"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L, 2L, 3L), inviteJson["participantIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(listOf(1L), inviteJson["activeParticipantIds"]!!.jsonArray.map { it.jsonPrimitive.long })

        // Non-member cannot join or read call
        assertEquals(HttpStatusCode.Forbidden, client.post("/calls/group/$callId/join") { bearerAuth(token(99)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/calls/$callId") { bearerAuth(token(99)) }.status)

        // Member 2 joins
        val joinResp = client.post("/calls/group/$callId/join") { bearerAuth(token(2)) }
        assertEquals(HttpStatusCode.OK, joinResp.status)
        val joinJson = joinResp.bodyAsText().parseJsonObject()
        assertEquals("ACTIVE", joinJson["state"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L, 2L), joinJson["activeParticipantIds"]!!.jsonArray.map { it.jsonPrimitive.long })

        // Member 2 reads session
        val getResp = client.get("/calls/$callId") { bearerAuth(token(2)) }
        assertEquals(HttpStatusCode.OK, getResp.status)
        assertEquals("ACTIVE", getResp.bodyAsText().parseJsonObject()["state"]!!.jsonPrimitive.content)

        // Member 2 leaves -> still ACTIVE with user 1
        val leaveTwo = client.post("/calls/group/$callId/leave") { bearerAuth(token(2)) }
        assertEquals(HttpStatusCode.OK, leaveTwo.status)
        val leaveTwoJson = leaveTwo.bodyAsText().parseJsonObject()
        assertEquals("ACTIVE", leaveTwoJson["state"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L), leaveTwoJson["activeParticipantIds"]!!.jsonArray.map { it.jsonPrimitive.long })

        // Last active participant (1) leaves -> state becomes ENDED
        val leaveOne = client.post("/calls/group/$callId/leave") { bearerAuth(token(1)) }
        assertEquals(HttpStatusCode.OK, leaveOne.status)
        val leaveOneJson = leaveOne.bodyAsText().parseJsonObject()
        assertEquals("ENDED", leaveOneJson["state"]!!.jsonPrimitive.content)
        assertTrue(leaveOneJson["activeParticipantIds"]!!.jsonArray.isEmpty())

        // Joining an ended call returns 409 Conflict
        assertEquals(HttpStatusCode.Conflict, client.post("/calls/group/$callId/join") { bearerAuth(token(3)) }.status)

        // Missing call returns 404 NotFound
        assertEquals(HttpStatusCode.NotFound, client.get("/calls/does-not-exist") { bearerAuth(token(1)) }.status)

        assertEquals(
            listOf("call.group.invite", "call.group.joined", "call.group.left", "call.group.left"),
            events.map { it.second }
        )
        assertEquals(setOf(2L, 3L), events[0].first)
    }

    private fun token(userId: Long): String =
        JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("sid", java.util.UUID.randomUUID().toString())
            .withExpiresAt(Instant.now().plusSeconds(300))
            .sign(Algorithm.HMAC256(config.jwtSecret))

    private fun String.parseJsonObject(): JsonObject =
        AppJson.parseToJsonElement(this).jsonObject
}
