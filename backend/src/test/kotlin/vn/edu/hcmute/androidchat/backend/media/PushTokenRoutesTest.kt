package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
}
