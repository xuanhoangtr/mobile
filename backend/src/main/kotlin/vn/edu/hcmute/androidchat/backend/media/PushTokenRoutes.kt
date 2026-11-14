package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.authenticatedUserId

@Serializable
data class PushTokenRequest(
    val deviceId: String,
    val token: String,
    val enabled: Boolean = true
)

@Serializable
data class PushTokenResponse(
    val deviceId: String,
    val registered: Boolean
)

class PushTokenRegistry {
    private val tokens = ConcurrentHashMap<Long, MutableMap<String, String>>()

    fun register(userId: Long, request: PushTokenRequest) {
        validate(request)
        tokens.computeIfAbsent(userId) { ConcurrentHashMap() }[request.deviceId] =
            if (request.enabled) request.token else ""
    }

    fun unregister(userId: Long, deviceId: String) {
        require(deviceId.isNotBlank()) { "deviceId is required" }
        tokens[userId]?.remove(deviceId)
    }

    private fun validate(request: PushTokenRequest) {
        require(request.deviceId.length in 1..128 && request.deviceId.all { it.isLetterOrDigit() || it in "-_." }) {
            "Invalid deviceId"
        }
        require(request.token.length in 1..4096) { "Invalid push token" }
    }
}

fun Route.registerPushTokenRoutes(registry: PushTokenRegistry) {
    authenticate(AUTH_JWT_NAME) {
        post("/devices/push-token") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, unauthorizedPushResponse())
            try {
                val request = call.receive<PushTokenRequest>()
                registry.register(userId, request)
                call.respond(HttpStatusCode.OK, PushTokenResponse(request.deviceId, request.enabled))
            } catch (_: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(ApiErrorDetail("VALIDATION_ERROR", "Invalid push token registration"))
                )
            }
        }
        delete("/devices/push-token/{deviceId}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, unauthorizedPushResponse())
            val deviceId = call.parameters["deviceId"]
            if (deviceId.isNullOrBlank()) {
                return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(ApiErrorDetail("VALIDATION_ERROR", "Invalid device ID"))
                )
            }
            registry.unregister(userId, deviceId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun unauthorizedPushResponse() =
    ApiErrorResponse(ApiErrorDetail("UNAUTHORIZED", "Invalid authenticated principal"))
