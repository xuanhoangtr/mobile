package vn.edu.hcmute.androidchat.backend.chat

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import vn.edu.hcmute.androidchat.backend.common.AUTH_JWT_NAME
import vn.edu.hcmute.androidchat.backend.common.AppJson
import vn.edu.hcmute.androidchat.backend.common.WsEnvelope
import vn.edu.hcmute.androidchat.backend.common.WsErrorPayload
import vn.edu.hcmute.androidchat.backend.common.authenticatedUserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val wsLogger = LoggerFactory.getLogger("ChatWebSocket")

/**
 * Tracks active WebSocket sessions per authenticated user ID for realtime delivery.
 */
class ChatSessionRegistry {
    private val sessionsByUser = ConcurrentHashMap<Long, MutableSet<DefaultWebSocketServerSession>>()

    fun register(userId: Long, session: DefaultWebSocketServerSession): Boolean {
        val set = sessionsByUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        val wasEmpty = set.isEmpty()
        set.add(session)
        return wasEmpty
    }

    fun unregister(userId: Long, session: DefaultWebSocketServerSession): Boolean {
        val set = sessionsByUser[userId] ?: return false
        set.remove(session)
        if (set.isEmpty()) {
            sessionsByUser.remove(userId, set)
            return true
        }
        return false
    }

    suspend inline fun <reified T> broadcastToUsers(
        userIds: Set<Long>,
        type: String,
        requestId: String?,
        payload: T
    ) {
        val frameText = AppJson.encodeToString(
            WsEnvelope(
                type = type,
                requestId = requestId,
                timestamp = Instant.now().toString(),
                payload = AppJson.encodeToJsonElement(payload)
            )
        )
        sendFrameToUsers(userIds, frameText)
    }

    suspend fun sendFrameToUsers(userIds: Set<Long>, frameText: String) {
        for (targetUserId in userIds) {
            val sessions = sessionsByUser[targetUserId].orEmpty()
            for (session in sessions) {
                runCatching { session.send(Frame.Text(frameText)) }
            }
        }
    }
}

/**
 * Registers authenticated `/ws/chat` WebSocket routes supporting
 * `message.send`, `message.ack`, `message.created`, `typing`, `presence`, and `receipt`.
 */
fun Route.registerChatWebSocketRoutes(
    repository: ChatRepository,
    sessions: ChatSessionRegistry = ChatSessionRegistry()
) {
    authenticate(AUTH_JWT_NAME) {
        webSocket("/ws/chat") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid JWT subject"))
                return@webSocket
            }

            val isFirstSession = sessions.register(userId, this)
            wsLogger.info("WebSocket connected for userId={}", userId)

            try {
                val connectedAt = Instant.now().toString()
                val onlinePresence = PresencePayload(
                    userId = userId,
                    status = "ONLINE",
                    lastSeenAt = connectedAt
                )
                sendEvent(type = "presence", requestId = null, payload = onlinePresence)
                if (isFirstSession) {
                    val peers = repository.peerUserIdsFor(userId)
                    if (peers.isNotEmpty()) {
                        sessions.broadcastToUsers(
                            userIds = peers,
                            type = "presence",
                            requestId = null,
                            payload = onlinePresence
                        )
                    }
                }

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    handleIncomingTextFrame(
                        rawText = frame.readText(),
                        userId = userId,
                        repository = repository,
                        sessions = sessions
                    )
                }
            } finally {
                val isLastSession = sessions.unregister(userId, this)
                if (isLastSession) {
                    val peers = runCatching { repository.peerUserIdsFor(userId) }.getOrDefault(emptySet())
                    if (peers.isNotEmpty()) {
                        sessions.broadcastToUsers(
                            userIds = peers,
                            type = "presence",
                            requestId = null,
                            payload = PresencePayload(
                                userId = userId,
                                status = "OFFLINE",
                                lastSeenAt = Instant.now().toString()
                            )
                        )
                    }
                }
                wsLogger.info("WebSocket disconnected for userId={}", userId)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleIncomingTextFrame(
    rawText: String,
    userId: Long,
    repository: ChatRepository,
    sessions: ChatSessionRegistry
) {
    val envelope = try {
        AppJson.decodeFromString<WsEnvelope>(rawText)
    } catch (_: Exception) {
        sendError(requestId = null, code = "VALIDATION_ERROR", message = "Malformed WebSocket JSON envelope")
        return
    }

    val nowIso = Instant.now().toString()
    when (envelope.type) {
        "message.send" -> handleMessageSend(envelope, userId, nowIso, repository, sessions)
        "typing" -> handleTyping(envelope, userId, repository, sessions)
        "receipt" -> handleReceipt(envelope, userId, nowIso, repository, sessions)
        else -> sendError(
            requestId = envelope.requestId,
            code = "UNSUPPORTED_EVENT",
            message = "Unsupported WebSocket event type: ${envelope.type}"
        )
    }
}

private suspend fun DefaultWebSocketServerSession.handleMessageSend(
    envelope: WsEnvelope,
    userId: Long,
    nowIso: String,
    repository: ChatRepository,
    sessions: ChatSessionRegistry
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<MessageSendPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid message.send payload")

    if (payload.clientMessageId.isBlank() || payload.conversationId <= 0L) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "clientMessageId and positive conversationId are required")
        return
    }

    val result = repository.saveMessage(userId, payload, nowIso)
    if (!result.isMember) {
        sendError(envelope.requestId, "FORBIDDEN", "User is not an active member of this conversation")
        return
    }

    sendEvent(type = "message.ack", requestId = envelope.requestId, payload = result.ack)

    val created = result.created ?: return
    val recipients = repository.activeMemberIds(payload.conversationId).ifEmpty { setOf(userId) }
    sessions.broadcastToUsers(
        userIds = recipients,
        type = "message.created",
        requestId = null,
        payload = created
    )
}

private suspend fun DefaultWebSocketServerSession.handleTyping(
    envelope: WsEnvelope,
    userId: Long,
    repository: ChatRepository,
    sessions: ChatSessionRegistry
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<TypingPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid typing payload")

    if (payload.conversationId <= 0L) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "Positive conversationId is required")
        return
    }

    val members = repository.activeMemberIds(payload.conversationId)
    if (members.isNotEmpty() && userId !in members) {
        sendError(envelope.requestId, "FORBIDDEN", "User is not an active member of this conversation")
        return
    }

    val recipients = members.ifEmpty { setOf(userId) }
    sessions.broadcastToUsers(
        userIds = recipients,
        type = "typing",
        requestId = envelope.requestId,
        payload = payload.copy(userId = userId)
    )
}

private suspend fun DefaultWebSocketServerSession.handleReceipt(
    envelope: WsEnvelope,
    userId: Long,
    nowIso: String,
    repository: ChatRepository,
    sessions: ChatSessionRegistry
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<ReceiptPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid receipt payload")

    if (payload.conversationId <= 0L || payload.messageId <= 0L || payload.status !in setOf("DELIVERED", "READ")) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid receipt fields or status")
        return
    }

    val members = repository.activeMemberIds(payload.conversationId)
    if (members.isNotEmpty() && userId !in members) {
        sendError(envelope.requestId, "FORBIDDEN", "User is not an active member of this conversation")
        return
    }

    val saved = when (val res = repository.saveReceipt(userId, payload.conversationId, payload.messageId, payload.status, nowIso)) {
        is GroupOpResult.Ok -> res.value
        is GroupOpResult.Err -> {
            sendError(envelope.requestId, res.code, res.message)
            return
        }
    }

    val recipients = members.ifEmpty { setOf(userId) }
    sessions.broadcastToUsers(
        userIds = recipients,
        type = "receipt",
        requestId = envelope.requestId,
        payload = saved
    )
}

private suspend inline fun <reified T> DefaultWebSocketServerSession.sendEvent(
    type: String,
    requestId: String?,
    payload: T
) {
    val frame = WsEnvelope(
        type = type,
        requestId = requestId,
        timestamp = Instant.now().toString(),
        payload = AppJson.encodeToJsonElement(payload)
    )
    send(Frame.Text(AppJson.encodeToString(frame)))
}

private suspend fun DefaultWebSocketServerSession.sendError(
    requestId: String?,
    code: String,
    message: String
) {
    sendEvent(
        type = "error",
        requestId = requestId,
        payload = WsErrorPayload(code = code, message = message)
    )
}
