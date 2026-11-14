package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.authenticatedUserId

@Serializable
data class CallInviteRequest(val calleeId: Long, val media: String = "audio")

@Serializable
data class CallActionRequest(val callId: String)

@Serializable
data class CallSessionResponse(
    val callId: String,
    val callerId: Long,
    val calleeId: Long,
    val media: String,
    val state: String
)

class CallSessionRegistry {
    private val sessions = ConcurrentHashMap<String, CallSessionResponse>()

    fun invite(callerId: Long, request: CallInviteRequest): CallSessionResponse {
        require(request.calleeId > 0L && request.calleeId != callerId) { "Invalid callee" }
        require(request.media == "audio" || request.media == "video") { "Invalid media" }
        val session = CallSessionResponse(
            callId = UUID.randomUUID().toString(),
            callerId = callerId,
            calleeId = request.calleeId,
            media = request.media,
            state = "RINGING"
        )
        sessions[session.callId] = session
        return session
    }

    fun answer(userId: Long, callId: String, accepted: Boolean): CallSessionResponse {
        val session = sessions[callId] ?: throw NoSuchElementException()
        require(session.calleeId == userId) { "Only the callee can answer" }
        val updated = session.copy(state = if (accepted) "ACCEPTED" else "DECLINED")
        sessions[callId] = updated
        return updated
    }

    fun end(userId: Long, callId: String): CallSessionResponse {
        val session = sessions[callId] ?: throw NoSuchElementException()
        require(userId == session.callerId || userId == session.calleeId) { "Not a call participant" }
        val updated = session.copy(state = "ENDED")
        sessions[callId] = updated
        return updated
    }
}

fun Route.registerCallRoutes(registry: CallSessionRegistry) {
    authenticate(AUTH_JWT_NAME) {
        post("/calls/invite") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, callError("UNAUTHORIZED"))
            try {
                call.respond(HttpStatusCode.Created, registry.invite(userId, call.receive()))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, callError("VALIDATION_ERROR"))
            }
        }
        post("/calls/answer") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, callError("UNAUTHORIZED"))
            try {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.answer(userId, request.callId, accepted = true))
            } catch (_: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, callError("NOT_FOUND"))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, callError("CALL_NOT_ALLOWED"))
            }
        }
        post("/calls/decline") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, callError("UNAUTHORIZED"))
            try {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.answer(userId, request.callId, accepted = false))
            } catch (_: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, callError("NOT_FOUND"))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, callError("CALL_NOT_ALLOWED"))
            }
        }
        post("/calls/end") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, callError("UNAUTHORIZED"))
            try {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.end(userId, request.callId))
            } catch (_: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, callError("NOT_FOUND"))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, callError("CALL_NOT_ALLOWED"))
            }
        }
    }
}

private fun callError(code: String) =
    ApiErrorResponse(ApiErrorDetail(code, "Call operation failed"))
