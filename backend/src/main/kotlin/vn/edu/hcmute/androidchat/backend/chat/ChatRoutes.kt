package vn.edu.hcmute.androidchat.backend.chat

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
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
fun Route.registerChatRoutes(
    repository: ChatRepository,
    sessions: ChatSessionRegistry? = null
) {
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

        post("/conversations/group") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")

            val body = runCatching { call.receiveNullable<CreateGroupConversationRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.createGroupConversation(
                creatorId = userId,
                name = body.name,
                memberIds = body.memberIds,
                nowIso = Instant.now().toString()
            )) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.Created, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        get("/conversations/{id}/members") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")

            when (val res = repository.listMembers(requesterId = userId, conversationId = conversationId)) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        post("/conversations/{id}/members") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val body = runCatching { call.receiveNullable<AddGroupMembersRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.addMembers(
                requesterId = userId,
                conversationId = conversationId,
                userIds = body.userIds,
                nowIso = Instant.now().toString()
            )) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        patch("/conversations/{id}/members/{userId}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@patch call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val targetUserId = call.parameters["userId"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid target user id")
            val body = runCatching { call.receiveNullable<UpdateMemberRoleRequest>() }.getOrNull()
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.updateMemberRole(
                requesterId = userId,
                conversationId = conversationId,
                targetUserId = targetUserId,
                newRole = body.role
            )) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        delete("/conversations/{id}/members/{userId}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@delete call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@delete call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val targetUserId = call.parameters["userId"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@delete call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid target user id")

            when (val res = repository.removeMember(
                requesterId = userId,
                conversationId = conversationId,
                targetUserId = targetUserId,
                nowIso = Instant.now().toString()
            )) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.NoContent)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
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

        patch("/messages/{id}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@patch call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val messageId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")
            val body = runCatching { call.receiveNullable<EditMessageRequest>() }.getOrNull()
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.editMessage(userId, messageId, body.content, Instant.now().toString())) {
                is GroupOpResult.Ok -> {
                    val updated = res.value
                    sessions?.broadcastToUsers(
                        userIds = repository.activeMemberIds(updated.conversationId),
                        type = "message.updated",
                        requestId = null,
                        payload = MessageUpdatedPayload(
                            conversationId = updated.conversationId,
                            messageId = updated.id,
                            content = updated.content,
                            editedAt = updated.editedAt
                        )
                    )
                    call.respond(HttpStatusCode.OK, updated)
                }
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        post("/messages/{id}/recall") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val messageId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")

            when (val res = repository.recallMessage(userId, messageId, Instant.now().toString())) {
                is GroupOpResult.Ok -> {
                    val recalled = res.value
                    sessions?.broadcastToUsers(
                        userIds = repository.activeMemberIds(recalled.conversationId),
                        type = "message.recalled",
                        requestId = null,
                        payload = MessageUpdatedPayload(
                            conversationId = recalled.conversationId,
                            messageId = recalled.id,
                            content = recalled.content,
                            deletedAt = recalled.deletedAt
                        )
                    )
                    call.respond(HttpStatusCode.OK, recalled)
                }
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        post("/messages/{id}/forward") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val messageId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")
            val body = runCatching { call.receiveNullable<ForwardMessageRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.forwardMessage(
                userId = userId,
                sourceMessageId = messageId,
                targetConversationId = body.targetConversationId,
                clientMessageId = body.clientMessageId,
                nowIso = Instant.now().toString()
            )) {
                is GroupOpResult.Ok -> {
                    val forwarded = res.value
                    sessions?.broadcastToUsers(
                        userIds = repository.activeMemberIds(forwarded.conversationId),
                        type = "message.created",
                        requestId = null,
                        payload = forwarded
                    )
                    call.respond(HttpStatusCode.Created, forwarded)
                }
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        post("/messages/{id}/reactions") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val messageId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")
            val body = runCatching { call.receiveNullable<ToggleReactionRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.toggleReaction(userId, messageId, body.emoji, Instant.now().toString())) {
                is GroupOpResult.Ok -> {
                    val payload = res.value
                    sessions?.broadcastToUsers(
                        userIds = repository.activeMemberIds(payload.conversationId),
                        type = "message.reaction",
                        requestId = null,
                        payload = payload
                    )
                    call.respond(HttpStatusCode.OK, payload)
                }
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        get("/messages/search") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val query = call.request.queryParameters["q"] ?: ""
            val conversationId = call.request.queryParameters["conversationId"]?.toLongOrNull()
            val cursor = call.request.queryParameters["cursor"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

            when (val res = repository.searchMessages(userId, conversationId, query, cursor, limit)) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        patch("/conversations/{id}/pin") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@patch call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val body = runCatching { call.receiveNullable<PinConversationRequest>() }.getOrNull()
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid request body")

            when (val res = repository.setConversationPinned(userId, conversationId, body.pinned, Instant.now().toString())) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        get("/conversations/{id}/pinned-messages") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@get call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")

            when (val res = repository.listPinnedMessages(userId, conversationId)) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        post("/conversations/{id}/pinned-messages/{messageId}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")

            when (val res = repository.pinMessage(userId, conversationId, messageId, Instant.now().toString())) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.OK, res.value)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }

        delete("/conversations/{id}/pinned-messages/{messageId}") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
                ?: return@delete call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid authenticated principal")
            val conversationId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@delete call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@delete call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Invalid message id")

            when (val res = repository.unpinMessage(userId, conversationId, messageId)) {
                is GroupOpResult.Ok -> call.respond(HttpStatusCode.NoContent)
                is GroupOpResult.Err -> call.respondError(HttpStatusCode.fromValue(res.statusCode), res.code, res.message)
            }
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(code = code, message = message)))
}
