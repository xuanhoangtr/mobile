package vn.edu.hcmute.androidchat.backend.chat

import io.ktor.http.HttpStatusCode
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
 * Skeleton REST routes for Conversation & Message history (module CHAT).
 * Database-backed queries and membership enforcement will be wired in D04-D09.
 */
fun Route.registerChatRoutes() {
    authenticate(AUTH_JWT_NAME) {
        get("/conversations") {
            call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondUnauthorized()

            call.respond(
                HttpStatusCode.OK,
                CursorPage<ConversationSummaryDto>(
                    items = emptyList(),
                    nextCursor = null,
                    hasMore = false
                )
            )
        }

        get("/conversations/{id}/messages") {
            call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondUnauthorized()

            val conversationId = call.parameters["id"]?.toLongOrNull()
            if (conversationId == null || conversationId <= 0L) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(
                        ApiErrorDetail(
                            code = "VALIDATION_ERROR",
                            message = "Invalid conversation id"
                        )
                    )
                )
            }

            call.respond(
                HttpStatusCode.OK,
                CursorPage<MessageDto>(
                    items = emptyList(),
                    nextCursor = null,
                    hasMore = false
                )
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondUnauthorized() {
    respond(
        HttpStatusCode.Unauthorized,
        ApiErrorResponse(
            ApiErrorDetail(
                code = "UNAUTHORIZED",
                message = "Invalid authenticated principal"
            )
        )
    )
}
