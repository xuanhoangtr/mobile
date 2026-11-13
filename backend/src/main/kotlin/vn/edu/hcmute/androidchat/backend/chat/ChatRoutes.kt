package vn.edu.hcmute.androidchat.backend.chat

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.CursorPage
import vn.edu.hcmute.androidchat.backend.common.authenticatedUserId

/**
 * REST routes for Conversation & Message history (module CHAT).
 */
fun Route.registerChatRoutes(repository: ChatRepository) {
    authenticate(AUTH_JWT_NAME) {
        get("/conversations") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")

            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val items = repository.listConversations(userId = userId, limit = limit)

            call.respond(
                HttpStatusCode.OK,
                CursorPage(
                    items = items,
                    nextCursor = null,
                    hasMore = false
                )
            )
        }

        post("/conversations/direct") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")

            val body = runCatching { call.receiveNullable<CreateDirectConversationRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            if (body.targetUserId <= 0L || body.targetUserId == userId) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "VALIDATION_ERROR",
                    "targetUserId must be positive and different from current user"
                )
            }

            val conversation = repository.getOrCreateDirectConversation(
                userId = userId,
                targetUserId = body.targetUserId,
                nowIso = Instant.now().toString()
            )
            call.respond(HttpStatusCode.OK, conversation)
        }

        get("/conversations/{id}/messages") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")

            val conversationId = call.parameters["id"]?.toLongOrNull()
            if (conversationId == null || conversationId <= 0L) {
                return@get call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            }

            val rawCursor = call.request.queryParameters["cursor"]
            val cursor = if (rawCursor.isNullOrBlank()) {
                null
            } else {
                rawCursor.toLongOrNull()?.takeIf { it > 0L }
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid cursor")
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val page = repository.listMessages(
                userId = userId,
                conversationId = conversationId,
                cursor = cursor,
                limit = limit
            ) ?: return@get call.respondError(
                HttpStatusCode.Forbidden,
                "FORBIDDEN",
                "User is not an active member of this conversation"
            )

            call.respond(HttpStatusCode.OK, page)
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(code = code, message = message)))
}
