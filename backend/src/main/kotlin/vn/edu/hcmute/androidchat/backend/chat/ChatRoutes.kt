package vn.edu.hcmute.androidchat.backend.chat

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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

        get("/conversations/{id}/messages") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")

            val conversationId = call.parameters["id"]?.toLongOrNull()
            if (conversationId == null || conversationId <= 0L) {
                return@get call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val items = repository.listMessages(userId = userId, conversationId = conversationId, limit = limit)
                ?: return@get call.respondError(
                    HttpStatusCode.Forbidden,
                    "FORBIDDEN",
                    "User is not an active member of this conversation"
                )

            call.respond(
                HttpStatusCode.OK,
                CursorPage(
                    items = items,
                    nextCursor = null,
                    hasMore = false
                )
            )
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(code = code, message = message)))
}
