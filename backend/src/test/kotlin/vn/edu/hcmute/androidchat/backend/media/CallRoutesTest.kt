package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.module
import io.ktor.server.routing.routing
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CallRoutesTest {
    private val config = AppConfig("test", 8080, null, null, null, "call-test-key", "call-test", "call-client")

    @Test
    fun calleeCanAcceptAndParticipantCanEndCall() = testApplication {
        val root = Files.createTempDirectory("call-test-")
        try {
            application {
                module(config)
                routing { registerCallRoutes(CallSessionRegistry()) }
            }
            val caller = token(7)
            val callee = token(8)
            val invite = client.post("/calls/invite") {
                bearerAuth(caller)
                contentType(ContentType.Application.Json)
                setBody("""{"calleeId":8,"media":"audio"}""")
            }
            assertEquals(HttpStatusCode.Created, invite.status)
            val callId = invite.bodyAsText().parseJsonObject()["callId"]!!.jsonPrimitive.content
            val answer = client.post("/calls/answer") {
                bearerAuth(callee)
                contentType(ContentType.Application.Json)
                setBody("""{"callId":"$callId"}""")
            }
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals("ACCEPTED", answer.bodyAsText().parseJsonObject()["state"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.post("/calls/end") {
                bearerAuth(caller)
                contentType(ContentType.Application.Json)
                setBody("""{"callId":"$callId"}""")
            }.status)
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

    private fun token(userId: Long): String =
        JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
            .withSubject(userId.toString()).withExpiresAt(Instant.now().plusSeconds(300))
            .sign(Algorithm.HMAC256(config.jwtSecret))

    private fun String.parseJsonObject(): JsonObject =
        AppJson.parseToJsonElement(this).jsonObject
}
