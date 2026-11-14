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
import java.util.concurrent.atomic.AtomicLong

private val wsLogger = LoggerFactory.getLogger("ChatWebSocket")

/**
 * In-memory skeleton registry for active user WebSocket connections and
 * temporary clientMessageId deduplication before PostgreSQL persistence is wired in D04.
 */
class ChatWsSkeletonState {
    private val sessionsByUser = ConcurrentHashMap<Long, MutableSet<DefaultWebSocketServerSession>>()
    private val nextMessageId = AtomicLong(1L)
    private val seenClientMessages = ConcurrentHashMap<String, MessageAckPayload>()

    fun register(userId: Long, session: DefaultWebSocketServerSession) {
        sessionsByUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(userId: Long, session: DefaultWebSocketServerSession) {
        val set = sessionsByUser[userId] ?: return
        set.remove(session)
        if (set.isEmpty()) {
            sessionsByUser.remove(userId, set)
        }
    }

    fun recordMessageSkeleton(senderId: Long, payload: MessageSendPayload, nowIso: String): Pair<MessageAckPayload, Boolean> {
        val dedupKey = "$senderId:${payload.clientMessageId}"
        val existing = seenClientMessages[dedupKey]
        if (existing != null) {
            return existing.copy(status = "DUPLICATE") to true
        }
        val assignedId = nextMessageId.getAndIncrement()
        val ack = MessageAckPayload(
            clientMessageId = payload.clientMessageId,
            conversationId = payload.conversationId,
            messageId = assignedId,
            status = "ACCEPTED",
            serverCreatedAt = nowIso
        )
        val raced = seenClientMessages.putIfAbsent(dedupKey, ack)
        return if (raced != null) {
            raced.copy(status = "DUPLICATE") to true
        } else {
            ack to false
        }
    }
}

/**
 * Registers authenticated `/ws/chat` WebSocket skeleton supporting
 * `message.send`, `message.ack`, `message.created`, `typing`, `presence`, and `receipt` envelopes.
 */
fun Route.registerChatWebSocketRoutes(state: ChatWsSkeletonState = ChatWsSkeletonState()) {
    authenticate(AUTH_JWT_NAME) {
        webSocket("/ws/chat") {
            val userId = call.principal<JWTPrincipal>()?.authenticatedUserId()
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid JWT subject"))
                return@webSocket
            }

            state.register(userId, this)
            wsLogger.info("WebSocket connected for userId={}", userId)

            try {
                sendEvent(
                    type = "presence",
                    requestId = null,
                    payload = PresencePayload(
                        userId = userId,
                        status = "ONLINE",
                        lastSeenAt = Instant.now().toString()
                    )
                )

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    handleIncomingTextFrame(
                        rawText = frame.readText(),
                        userId = userId,
                        state = state
                    )
                }
            } finally {
                state.unregister(userId, this)
                wsLogger.info("WebSocket disconnected for userId={}", userId)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleIncomingTextFrame(
    rawText: String,
    userId: Long,
    state: ChatWsSkeletonState
) {
    val envelope = try {
        AppJson.decodeFromString<WsEnvelope>(rawText)
    } catch (_: Exception) {
        sendError(requestId = null, code = "VALIDATION_ERROR", message = "Malformed WebSocket JSON envelope")
        return
    }

    val nowIso = Instant.now().toString()
    when (envelope.type) {
        "message.send" -> handleMessageSend(envelope, userId, nowIso, state)
        "typing" -> handleTyping(envelope, userId)
        "receipt" -> handleReceipt(envelope, userId, nowIso)
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
    state: ChatWsSkeletonState
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<MessageSendPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid message.send payload")

    if (payload.clientMessageId.isBlank() || payload.conversationId <= 0L) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "clientMessageId and positive conversationId are required")
        return
    }

    val (ack, isDuplicate) = state.recordMessageSkeleton(userId, payload, nowIso)
    sendEvent(type = "message.ack", requestId = envelope.requestId, payload = ack)

    if (!isDuplicate) {
        // Skeleton echoes message.created to sender session; multi-member broadcast moves to D04 repository.
        sendEvent(
            type = "message.created",
            requestId = null,
            payload = MessageDto(
                id = ack.messageId,
                clientMessageId = payload.clientMessageId,
                conversationId = payload.conversationId,
                senderId = userId,
                type = payload.type,
                content = payload.content,
                mediaRef = payload.mediaRef,
                replyToId = payload.replyToId,
                metadata = payload.metadata,
                createdAt = ack.serverCreatedAt
            )
        )
    }
}

private suspend fun DefaultWebSocketServerSession.handleTyping(
    envelope: WsEnvelope,
    userId: Long
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<TypingPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid typing payload")

    if (payload.conversationId <= 0L) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "Positive conversationId is required")
        return
    }

    sendEvent(
        type = "typing",
        requestId = envelope.requestId,
        payload = payload.copy(userId = userId)
    )
}

private suspend fun DefaultWebSocketServerSession.handleReceipt(
    envelope: WsEnvelope,
    userId: Long,
    nowIso: String
) {
    val payload = runCatching {
        AppJson.decodeFromJsonElement<ReceiptPayload>(envelope.payload)
    }.getOrNull() ?: return sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid receipt payload")

    if (payload.conversationId <= 0L || payload.messageId <= 0L || payload.status !in setOf("DELIVERED", "READ")) {
        sendError(envelope.requestId, "VALIDATION_ERROR", "Invalid receipt fields or status")
        return
    }

    sendEvent(
        type = "receipt",
        requestId = envelope.requestId,
        payload = payload.copy(userId = userId, at = nowIso)
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
