package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.ApiErrorDetail
import vn.edu.hcmute.androidchat.backend.common.ApiErrorResponse
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.common.authenticatedUserId

@Serializable
data class CallInviteRequest(
    val calleeId: Long,
    val media: String = "audio",
    val conversationId: Long? = null
)

@Serializable
data class GroupCallInviteRequest(
    val conversationId: Long,
    val participantIds: List<Long>,
    val media: String = "audio"
)

@Serializable
data class CallActionRequest(val callId: String)

@Serializable
data class CallSessionResponse(
    val callId: String,
    val callerId: Long,
    val calleeId: Long? = null,
    val conversationId: Long? = null,
    val media: String,
    val mode: String = "DIRECT",
    val state: String,
    val participantIds: List<Long> = emptyList(),
    val activeParticipantIds: List<Long> = emptyList()
)

private class CallForbiddenException(message: String) : RuntimeException(message)

/**
 * Manages 1-1 and group call sessions with optional PostgreSQL persistence and WebSocket notifications.
 */
class CallSessionRegistry(
    private val dataSource: DataSource? = null,
    private val isConversationMember: (suspend (conversationId: Long, userId: Long) -> Boolean)? = null,
    private val onCallEvent: suspend (targetUserIds: Set<Long>, eventType: String, payload: JsonElement) -> Unit = { _, _, _ -> }
) {
    private val sessions = ConcurrentHashMap<String, CallSessionResponse>()
    private val mutex = Mutex()

    suspend fun invite(callerId: Long, request: CallInviteRequest): CallSessionResponse {
        require(request.calleeId > 0L && request.calleeId != callerId) { "Invalid callee" }
        require(request.media == "audio" || request.media == "video") { "Invalid media" }
        val convId = request.conversationId
        if (convId != null) {
            require(convId > 0L) { "Invalid conversationId" }
            val checker = isConversationMember
            if (checker != null && (!checker(convId, callerId) || !checker(convId, request.calleeId))) {
                throw CallForbiddenException("Users must be active conversation members")
            }
        }
        val session = CallSessionResponse(
            callId = UUID.randomUUID().toString(),
            callerId = callerId,
            calleeId = request.calleeId,
            conversationId = convId,
            media = request.media,
            mode = "DIRECT",
            state = "RINGING",
            participantIds = listOf(callerId, request.calleeId),
            activeParticipantIds = listOf(callerId)
        )
        mutex.withLock {
            persistSession(session)
            sessions[session.callId] = session
        }
        emitEvent(setOf(request.calleeId), "call.invite", session)
        return session
    }

    suspend fun answer(userId: Long, callId: String, accepted: Boolean): CallSessionResponse {
        require(callId.isNotBlank()) { "callId is required" }
        val updated = mutex.withLock {
            val session = loadSession(callId) ?: throw NoSuchElementException()
            if (session.mode != "DIRECT" || session.calleeId != userId) {
                throw CallForbiddenException("Only the callee can answer")
            }
            if (session.state == "ENDED") {
                throw IllegalStateException("Call has already ended")
            }
            val next = session.copy(
                state = if (accepted) "ACCEPTED" else "DECLINED",
                activeParticipantIds = if (accepted) listOf(session.callerId, userId) else listOf(session.callerId)
            )
            persistSession(next)
            sessions[callId] = next
            next
        }
        emitEvent(setOf(updated.callerId), if (accepted) "call.accepted" else "call.declined", updated)
        return updated
    }

    suspend fun end(userId: Long, callId: String): CallSessionResponse {
        require(callId.isNotBlank()) { "callId is required" }
        val updated = mutex.withLock {
            val session = loadSession(callId) ?: throw NoSuchElementException()
            if (!isParticipant(session, userId)) {
                throw CallForbiddenException("Not a call participant")
            }
            val next = session.copy(state = "ENDED", activeParticipantIds = emptyList())
            persistSession(next)
            sessions[callId] = next
            next
        }
        val targets = allTargetIds(updated) - userId
        emitEvent(targets, "call.ended", updated)
        return updated
    }

    suspend fun groupInvite(callerId: Long, request: GroupCallInviteRequest): CallSessionResponse {
        require(request.conversationId > 0L) { "Invalid conversationId" }
        require(request.media == "audio" || request.media == "video") { "Invalid media" }
        require(request.participantIds.all { it > 0L }) { "Invalid participantIds" }
        val invitees = request.participantIds.filter { it != callerId }.distinct()
        require(invitees.isNotEmpty()) { "At least one other participant is required" }

        val checker = isConversationMember
        if (checker != null) {
            if (!checker(request.conversationId, callerId) || invitees.any { !checker(request.conversationId, it) }) {
                throw CallForbiddenException("All call participants must be active conversation members")
            }
        }

        val session = CallSessionResponse(
            callId = UUID.randomUUID().toString(),
            callerId = callerId,
            calleeId = null,
            conversationId = request.conversationId,
            media = request.media,
            mode = "GROUP",
            state = "RINGING",
            participantIds = (listOf(callerId) + invitees).distinct(),
            activeParticipantIds = listOf(callerId)
        )
        mutex.withLock {
            persistSession(session)
            sessions[session.callId] = session
        }
        emitEvent(invitees.toSet(), "call.group.invite", session)
        return session
    }

    suspend fun groupJoin(userId: Long, callId: String): CallSessionResponse {
        require(callId.isNotBlank()) { "callId is required" }
        val updated = mutex.withLock {
            val session = loadSession(callId) ?: throw NoSuchElementException()
            require(session.mode == "GROUP") { "Not a group call" }
            if (session.state == "ENDED" || session.state == "DECLINED") {
                throw IllegalStateException("Call is no longer active")
            }
            val convId = session.conversationId
            val checker = isConversationMember
            val allowed = if (checker != null && convId != null) {
                checker(convId, userId)
            } else {
                userId in session.participantIds
            }
            if (!allowed) {
                throw CallForbiddenException("User cannot join this group call")
            }
            val next = session.copy(
                state = "ACTIVE",
                participantIds = (session.participantIds + userId).distinct(),
                activeParticipantIds = (session.activeParticipantIds + userId).distinct()
            )
            persistSession(next)
            sessions[callId] = next
            next
        }
        emitEvent(allTargetIds(updated) - userId, "call.group.joined", updated)
        return updated
    }

    suspend fun groupLeave(userId: Long, callId: String): CallSessionResponse {
        require(callId.isNotBlank()) { "callId is required" }
        val updated = mutex.withLock {
            val session = loadSession(callId) ?: throw NoSuchElementException()
            require(session.mode == "GROUP") { "Not a group call" }
            if (userId !in session.participantIds && userId !in session.activeParticipantIds) {
                throw CallForbiddenException("Not a group call participant")
            }
            val remaining = session.activeParticipantIds.filter { it != userId }
            val nextState = if (remaining.isEmpty()) "ENDED" else session.state
            val next = session.copy(
                state = nextState,
                activeParticipantIds = remaining
            )
            persistSession(next)
            sessions[callId] = next
            next
        }
        emitEvent(allTargetIds(updated) - userId, "call.group.left", updated)
        return updated
    }

    suspend fun getSession(userId: Long, callId: String): CallSessionResponse {
        require(callId.isNotBlank()) { "callId is required" }
        val session = mutex.withLock { loadSession(callId) } ?: throw NoSuchElementException()
        val convId = session.conversationId
        val isMember = isConversationMember != null && convId != null && isConversationMember.invoke(convId, userId)
        if (!isParticipant(session, userId) && !isMember) {
            throw CallForbiddenException("Not a call participant")
        }
        return session
    }

    private fun isParticipant(session: CallSessionResponse, userId: Long): Boolean =
        userId == session.callerId ||
            userId == session.calleeId ||
            userId in session.participantIds ||
            userId in session.activeParticipantIds

    private fun allTargetIds(session: CallSessionResponse): Set<Long> =
        (session.participantIds + session.activeParticipantIds + listOfNotNull(session.callerId, session.calleeId)).toSet()

    private suspend fun emitEvent(targetUserIds: Set<Long>, eventType: String, session: CallSessionResponse) {
        if (targetUserIds.isEmpty()) return
        try {
            onCallEvent(targetUserIds, eventType, AppJson.encodeToJsonElement(session))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // WebSocket delivery is best-effort and must not abort persisted call state.
        }
    }

    private suspend fun persistSession(session: CallSessionResponse) {
        val ds = dataSource ?: return
        val participantsJson = AppJson.encodeToString(session.participantIds)
        val activeJson = AppJson.encodeToString(session.activeParticipantIds)
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO call_sessions (
                        call_id, conversation_id, caller_id, callee_id, media, mode, state, participant_ids, active_participant_ids, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, NOW())
                    ON CONFLICT (call_id) DO UPDATE SET
                        state = EXCLUDED.state,
                        participant_ids = EXCLUDED.participant_ids,
                        active_participant_ids = EXCLUDED.active_participant_ids,
                        updated_at = NOW()
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, session.callId)
                    if (session.conversationId != null) stmt.setLong(2, session.conversationId) else stmt.setNull(2, Types.BIGINT)
                    stmt.setLong(3, session.callerId)
                    if (session.calleeId != null) stmt.setLong(4, session.calleeId) else stmt.setNull(4, Types.BIGINT)
                    stmt.setString(5, session.media)
                    stmt.setString(6, session.mode)
                    stmt.setString(7, session.state)
                    stmt.setString(8, participantsJson)
                    stmt.setString(9, activeJson)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private suspend fun loadSession(callId: String): CallSessionResponse? {
        sessions[callId]?.let { return it }
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT call_id, conversation_id, caller_id, callee_id, media, mode, state, participant_ids::text AS participant_ids, active_participant_ids::text AS active_participant_ids
                    FROM call_sessions
                    WHERE call_id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, callId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        val convId = rs.getLong("conversation_id").let { if (rs.wasNull()) null else it }
                        val calleeId = rs.getLong("callee_id").let { if (rs.wasNull()) null else it }
                        val participants = AppJson.decodeFromString<List<Long>>(rs.getString("participant_ids") ?: "[]")
                        val active = AppJson.decodeFromString<List<Long>>(rs.getString("active_participant_ids") ?: "[]")
                        CallSessionResponse(
                            callId = rs.getString("call_id"),
                            callerId = rs.getLong("caller_id"),
                            calleeId = calleeId,
                            conversationId = convId,
                            media = rs.getString("media"),
                            mode = rs.getString("mode"),
                            state = rs.getString("state"),
                            participantIds = participants,
                            activeParticipantIds = active
                        ).also { sessions[callId] = it }
                    }
                }
            }
        }
    }
}

fun Route.registerCallRoutes(registry: CallSessionRegistry) {
    authenticate(AUTH_JWT_NAME) {
        post("/calls/invite") {
            val userId = call.authenticatedUser() ?: return@post
            call.handleCallAction {
                call.respond(HttpStatusCode.Created, registry.invite(userId, call.receive()))
            }
        }
        post("/calls/answer") {
            val userId = call.authenticatedUser() ?: return@post
            call.handleCallAction {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.answer(userId, request.callId, accepted = true))
            }
        }
        post("/calls/decline") {
            val userId = call.authenticatedUser() ?: return@post
            call.handleCallAction {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.answer(userId, request.callId, accepted = false))
            }
        }
        post("/calls/end") {
            val userId = call.authenticatedUser() ?: return@post
            call.handleCallAction {
                val request = call.receive<CallActionRequest>()
                call.respond(HttpStatusCode.OK, registry.end(userId, request.callId))
            }
        }
        post("/calls/group/invite") {
            val userId = call.authenticatedUser() ?: return@post
            call.handleCallAction {
                call.respond(HttpStatusCode.Created, registry.groupInvite(userId, call.receive()))
            }
        }
        post("/calls/group/{callId}/join") {
            val userId = call.authenticatedUser() ?: return@post
            val callId = call.parameters["callId"].orEmpty()
            call.handleCallAction {
                call.respond(HttpStatusCode.OK, registry.groupJoin(userId, callId))
            }
        }
        post("/calls/group/{callId}/leave") {
            val userId = call.authenticatedUser() ?: return@post
            val callId = call.parameters["callId"].orEmpty()
            call.handleCallAction {
                call.respond(HttpStatusCode.OK, registry.groupLeave(userId, callId))
            }
        }
        get("/calls/{callId}") {
            val userId = call.authenticatedUser() ?: return@get
            val callId = call.parameters["callId"].orEmpty()
            call.handleCallAction {
                call.respond(HttpStatusCode.OK, registry.getSession(userId, callId))
            }
        }
    }
}

private suspend fun ApplicationCall.authenticatedUser(): Long? {
    val userId = principal<JWTPrincipal>()?.authenticatedUserId()
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized, callError("UNAUTHORIZED"))
    }
    return userId
}

private suspend fun ApplicationCall.handleCallAction(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, callError("NOT_FOUND"))
    } catch (_: CallForbiddenException) {
        respond(HttpStatusCode.Forbidden, callError("CALL_NOT_ALLOWED"))
    } catch (_: IllegalStateException) {
        respond(HttpStatusCode.Conflict, callError("INVALID_CALL_STATE"))
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, callError("VALIDATION_ERROR"))
    }
}

private fun callError(code: String) =
    ApiErrorResponse(ApiErrorDetail(code, "Call operation failed"))
