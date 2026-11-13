package vn.edu.hcmute.androidchat.feature.chat.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * OkHttp-backed WebSocket transport for `/ws/chat` with exponential-backoff reconnect.
 * Never logs Authorization headers or raw access tokens.
 */
class OkHttpChatWebSocketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val baseReconnectDelayMs: Long = 1_000L,
    private val maxReconnectDelayMs: Long = 30_000L
) : ChatRealtimeTransport {

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ChatServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ChatServerEvent> = _events.asSharedFlow()

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var manualDisconnect = false

    @Volatile
    private var lastWsUrl: String? = null

    @Volatile
    private var lastAccessToken: String? = null

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun connect(wsUrl: String, accessToken: String) {
        if (wsUrl.isBlank() || accessToken.isBlank()) {
            _connectionState.value = WsConnectionState.DISCONNECTED
            return
        }
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        lastWsUrl = wsUrl
        lastAccessToken = accessToken
        openSocket(wsUrl, accessToken, isReconnect = false)
    }

    private fun openSocket(wsUrl: String, accessToken: String, isReconnect: Boolean) {
        activeSocket?.close(1000, "Reconnecting")
        _connectionState.value = if (isReconnect) {
            WsConnectionState.RECONNECTING
        } else {
            WsConnectionState.CONNECTING
        }

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        activeSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    _connectionState.value = WsConnectionState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    ChatRealtimeParser.decodeEvent(text)?.let { event ->
                        _events.tryEmit(event)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (activeSocket === webSocket) {
                        activeSocket = null
                    }
                    if (!manualDisconnect && code != 1008) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = WsConnectionState.DISCONNECTED
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (activeSocket === webSocket) {
                        activeSocket = null
                    }
                    _events.tryEmit(
                        ChatServerEvent.Error(
                            requestId = null,
                            code = "WS_CONNECTION_FAILED",
                            message = t.message ?: "WebSocket connection failed"
                        )
                    )
                    if (!manualDisconnect && response?.code != 401) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = WsConnectionState.DISCONNECTED
                    }
                }
            }
        )
    }

    private fun scheduleReconnect() {
        val url = lastWsUrl
        val token = lastAccessToken
        if (manualDisconnect || url.isNullOrBlank() || token.isNullOrBlank()) {
            _connectionState.value = WsConnectionState.DISCONNECTED
            return
        }
        reconnectJob?.cancel()
        val shift = min(reconnectAttempt, 5)
        val delayMs = min(baseReconnectDelayMs shl shift, maxReconnectDelayMs)
        reconnectAttempt += 1
        _connectionState.value = WsConnectionState.RECONNECTING
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualDisconnect) {
                openSocket(url, token, isReconnect = true)
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        lastWsUrl = null
        lastAccessToken = null
        val socket = activeSocket
        activeSocket = null
        socket?.close(1000, "Client disconnect")
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    override fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        content: String,
        type: MessageType,
        replyToId: Long?
    ): Boolean {
        val socket = activeSocket ?: return false
        val frame = ChatRealtimeParser.encodeMessageSend(
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            content = content,
            type = type,
            replyToId = replyToId
        )
        return socket.send(frame)
    }

    override fun sendTyping(conversationId: Long, isTyping: Boolean): Boolean {
        val socket = activeSocket ?: return false
        return socket.send(ChatRealtimeParser.encodeTyping(conversationId, isTyping))
    }

    override fun sendReceipt(
        conversationId: Long,
        messageId: Long,
        status: MessageDeliveryStatus
    ): Boolean {
        val socket = activeSocket ?: return false
        return socket.send(ChatRealtimeParser.encodeReceipt(conversationId, messageId, status))
    }

    override fun sendCallSignal(
        conversationId: Long,
        callId: String,
        signalType: String,
        targetUserId: Long?,
        sdpOrCandidate: String?
    ): Boolean {
        val socket = activeSocket ?: return false
        return socket.send(
            ChatRealtimeParser.encodeCallSignal(
                conversationId = conversationId,
                callId = callId,
                signalType = signalType,
                targetUserId = targetUserId,
                sdpOrCandidate = sdpOrCandidate
            )
        )
    }
}
