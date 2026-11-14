package vn.edu.hcmute.androidchat.feature.media.call

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

class CallViewModel(
    private var signalSender: CallSignalSender? = null,
    private val webRtcFactory: ((Context, CallViewModel) -> WebRtcCallClient)? = null,
    timerDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val state = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val timerScope = CoroutineScope(SupervisorJob() + timerDispatcher)
    private var timerJob: Job? = null

    private var webRtcClient: WebRtcCallClient? = null
    private var audioFocus: CallAudioFocus? = null
    private var pendingOfferSdp: String? = null
    private val pendingRemoteIceCandidates = mutableListOf<String>()

    val eglBaseContext: EglBase.Context?
        get() = webRtcClient?.eglBaseContext

    fun attachSignaling(sender: CallSignalSender?) {
        signalSender = sender
    }

    fun startOutgoing(
        calleeId: Long,
        media: CallMedia,
        callId: String,
        conversationId: Long? = null,
        peerName: String? = null
    ) {
        require(calleeId > 0L)
        stopTimer()
        state.value = CallUiState(
            callId = callId,
            peerId = calleeId,
            media = media,
            phase = CallPhase.OUTGOING_RINGING,
            cameraEnabled = (media == CallMedia.VIDEO),
            conversationId = conversationId,
            peerName = peerName
        )
    }

    fun showIncoming(
        callId: String,
        callerId: Long,
        media: CallMedia,
        conversationId: Long? = null,
        peerName: String? = null
    ) {
        require(callId.isNotBlank() && callerId > 0L)
        stopTimer()
        state.value = CallUiState(
            callId = callId,
            peerId = callerId,
            media = media,
            phase = CallPhase.INCOMING_RINGING,
            cameraEnabled = (media == CallMedia.VIDEO),
            conversationId = conversationId,
            peerName = peerName
        )
    }

    fun startWebRtcCall(
        context: Context,
        conversationId: Long,
        calleeId: Long,
        media: CallMedia,
        callId: String = UUID.randomUUID().toString(),
        peerName: String? = null
    ) {
        require(calleeId > 0L)
        releaseMediaResources()
        startOutgoing(
            calleeId = calleeId,
            media = media,
            callId = callId,
            conversationId = conversationId,
            peerName = peerName
        )
        requestAudioFocusSafely(context)
        sendSignal(conversationId, callId, CallSignalTypes.INVITE, calleeId, media.name)

        val client = ensureWebRtcClient(context, media == CallMedia.VIDEO)
        if (client != null) {
            client.createOffer(
                onSdp = { sdp ->
                    sendSignal(conversationId, callId, CallSignalTypes.OFFER, calleeId, sdp)
                },
                onError = { err ->
                    state.update { it.copy(phase = CallPhase.ERROR, error = err) }
                }
            )
        }
    }

    fun acceptIncoming() {
        if (state.value.phase == CallPhase.INCOMING_RINGING) {
            state.update { it.copy(phase = CallPhase.CONNECTED, error = null) }
            startTimerIfNeeded()
        }
    }

    fun acceptWebRtcCall(context: Context) {
        val current = state.value
        if (current.phase != CallPhase.INCOMING_RINGING) return

        requestAudioFocusSafely(context)
        val isVideo = current.media == CallMedia.VIDEO
        val client = ensureWebRtcClient(context, isVideo)

        val convId = current.conversationId
        val callId = current.callId
        val peerId = current.peerId

        acceptIncoming()

        if (convId != null && callId != null) {
            sendSignal(convId, callId, CallSignalTypes.ACCEPT, peerId, null)
        }

        val offer = pendingOfferSdp
        if (client != null && offer != null) {
            pendingOfferSdp = null
            client.setRemoteDescription(
                type = SessionDescription.Type.OFFER,
                sdp = offer,
                onDone = {
                    client.createAnswer(
                        onSdp = { answerSdp ->
                            if (convId != null && callId != null) {
                                sendSignal(convId, callId, CallSignalTypes.ANSWER, peerId, answerSdp)
                            }
                        },
                        onError = { err ->
                            state.update { it.copy(phase = CallPhase.ERROR, error = err) }
                        }
                    )
                },
                onError = { err ->
                    state.update { it.copy(phase = CallPhase.ERROR, error = err) }
                }
            )
        }
    }

    fun onIncomingSignal(
        context: Context?,
        conversationId: Long,
        callId: String,
        signalType: String,
        fromUserId: Long,
        sdpOrCandidate: String?
    ) {
        when (signalType.uppercase()) {
            CallSignalTypes.INVITE -> {
                val media = if (sdpOrCandidate.equals(CallMedia.VIDEO.name, ignoreCase = true)) {
                    CallMedia.VIDEO
                } else {
                    CallMedia.AUDIO
                }
                showIncoming(
                    callId = callId,
                    callerId = fromUserId.coerceAtLeast(1L),
                    media = media,
                    conversationId = conversationId
                )
            }

            CallSignalTypes.OFFER -> {
                if (state.value.phase == CallPhase.IDLE) {
                    val detectedMedia = if (sdpOrCandidate?.contains("m=video") == true) {
                        CallMedia.VIDEO
                    } else {
                        CallMedia.AUDIO
                    }
                    showIncoming(
                        callId = callId,
                        callerId = fromUserId.coerceAtLeast(1L),
                        media = detectedMedia,
                        conversationId = conversationId
                    )
                }
                val activeClient = webRtcClient
                if (activeClient != null && sdpOrCandidate != null && state.value.phase == CallPhase.CONNECTED) {
                    activeClient.setRemoteDescription(
                        type = SessionDescription.Type.OFFER,
                        sdp = sdpOrCandidate,
                        onDone = {
                            activeClient.createAnswer(onSdp = { answer ->
                                sendSignal(conversationId, callId, CallSignalTypes.ANSWER, fromUserId, answer)
                            })
                        }
                    )
                } else if (sdpOrCandidate != null) {
                    pendingOfferSdp = sdpOrCandidate
                }
            }

            CallSignalTypes.ACCEPT -> {
                if (state.value.callId == callId && state.value.phase == CallPhase.OUTGOING_RINGING) {
                    markConnected()
                }
            }

            CallSignalTypes.ANSWER -> {
                if (state.value.callId != null && state.value.callId != callId) return
                val client = webRtcClient ?: context?.let { ensureWebRtcClient(it, state.value.media == CallMedia.VIDEO) }
                if (client != null && sdpOrCandidate != null) {
                    client.setRemoteDescription(
                        type = SessionDescription.Type.ANSWER,
                        sdp = sdpOrCandidate,
                        onDone = { markConnected() },
                        onError = { err -> state.update { it.copy(phase = CallPhase.ERROR, error = err) } }
                    )
                } else {
                    markConnected()
                }
            }

            CallSignalTypes.ICE_CANDIDATE -> {
                if (state.value.callId != null && state.value.callId != callId) return
                if (sdpOrCandidate.isNullOrBlank()) return
                val client = webRtcClient
                if (client != null) {
                    client.addIceCandidateJson(sdpOrCandidate)
                } else {
                    pendingRemoteIceCandidates.add(sdpOrCandidate)
                }
            }

            CallSignalTypes.DECLINE -> {
                if (state.value.callId == null || state.value.callId == callId) {
                    stopTimer()
                    releaseMediaResources()
                    state.update { it.copy(phase = CallPhase.DECLINED) }
                }
            }

            CallSignalTypes.END -> {
                if (state.value.callId == null || state.value.callId == callId) {
                    stopTimer()
                    releaseMediaResources()
                    state.update { it.copy(phase = CallPhase.ENDED) }
                }
            }
        }
    }

    fun declineIncoming() {
        val current = state.value
        if (current.phase == CallPhase.INCOMING_RINGING) {
            if (current.conversationId != null && current.callId != null) {
                sendSignal(
                    current.conversationId,
                    current.callId,
                    CallSignalTypes.DECLINE,
                    current.peerId,
                    null
                )
            }
            stopTimer()
            releaseMediaResources()
            state.value = current.copy(phase = CallPhase.DECLINED)
        }
    }

    fun markConnected() {
        if (state.value.phase == CallPhase.OUTGOING_RINGING ||
            state.value.phase == CallPhase.RECONNECTING
        ) {
            state.value = state.value.copy(phase = CallPhase.CONNECTED, error = null)
            startTimerIfNeeded()
        }
    }

    fun onTransportLost() {
        if (state.value.phase == CallPhase.CONNECTED) {
            stopTimer()
            state.value = state.value.copy(phase = CallPhase.RECONNECTING, error = "Connection lost")
        }
    }

    fun onTimeout() {
        if (state.value.phase == CallPhase.OUTGOING_RINGING ||
            state.value.phase == CallPhase.INCOMING_RINGING ||
            state.value.phase == CallPhase.RECONNECTING
        ) {
            stopTimer()
            releaseMediaResources()
            state.value = state.value.copy(phase = CallPhase.ERROR, error = "Call timed out")
        }
    }

    fun toggleMicrophone() {
        val nextMuted = !state.value.microphoneMuted
        state.value = state.value.copy(microphoneMuted = nextMuted)
        webRtcClient?.setMicEnabled(!nextMuted)
    }

    fun toggleCamera() {
        val nextEnabled = !state.value.cameraEnabled
        state.value = state.value.copy(cameraEnabled = nextEnabled)
        webRtcClient?.setCameraEnabled(nextEnabled)
    }

    fun toggleSpeaker() {
        val nextSpeaker = !state.value.speakerEnabled
        state.value = state.value.copy(speakerEnabled = nextSpeaker)
        audioFocus?.setSpeakerphoneOn(nextSpeaker)
    }

    fun switchCamera() {
        webRtcClient?.switchCamera()
    }

    fun end() {
        val current = state.value
        if (current.phase != CallPhase.IDLE) {
            if (current.phase != CallPhase.ENDED &&
                current.phase != CallPhase.DECLINED &&
                current.conversationId != null &&
                current.callId != null
            ) {
                sendSignal(
                    current.conversationId,
                    current.callId,
                    CallSignalTypes.END,
                    current.peerId,
                    null
                )
            }
            stopTimer()
            releaseMediaResources()
            state.value = current.copy(phase = CallPhase.ENDED)
        }
    }

    private fun ensureWebRtcClient(context: Context, isVideo: Boolean): WebRtcCallClient? {
        webRtcClient?.let { return it }
        val created = runCatching {
            webRtcFactory?.invoke(context, this) ?: WebRtcCallClient(
                context = context,
                onLocalIceCandidate = { candidate ->
                    val current = state.value
                    val convId = current.conversationId
                    val callId = current.callId
                    if (convId != null && callId != null) {
                        sendSignal(
                            convId,
                            callId,
                            CallSignalTypes.ICE_CANDIDATE,
                            current.peerId,
                            WebRtcCallClient.iceCandidateToJson(candidate)
                        )
                    }
                },
                onLocalVideoTrack = { track ->
                    _localVideoTrack.value = track
                },
                onRemoteVideoTrack = { track ->
                    _remoteVideoTrack.value = track
                },
                onConnectionStateChanged = { peerState ->
                    when (peerState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> markConnected()
                        PeerConnection.PeerConnectionState.DISCONNECTED -> onTransportLost()
                        PeerConnection.PeerConnectionState.FAILED -> {
                            stopTimer()
                            state.update {
                                it.copy(phase = CallPhase.ERROR, error = "WebRTC connection failed")
                            }
                        }
                        else -> Unit
                    }
                }
            )
        }.getOrNull() ?: return null

        if (!created.start(isVideo)) {
            created.close()
            return null
        }
        webRtcClient = created
        _localVideoTrack.value = created.localVideoTrackFlow.value
        _remoteVideoTrack.value = created.remoteVideoTrackFlow.value

        if (pendingRemoteIceCandidates.isNotEmpty()) {
            val queued = pendingRemoteIceCandidates.toList()
            pendingRemoteIceCandidates.clear()
            queued.forEach { created.addIceCandidateJson(it) }
        }
        return created
    }

    private fun requestAudioFocusSafely(context: Context) {
        runCatching {
            audioFocus?.abandon()
            audioFocus = CallAudioFocus(context) {
                if (!state.value.microphoneMuted && state.value.phase == CallPhase.CONNECTED) {
                    toggleMicrophone()
                }
            }.also { it.request() }
        }
    }

    private fun sendSignal(
        conversationId: Long,
        callId: String,
        signalType: String,
        targetUserId: Long?,
        sdpOrCandidate: String?
    ) {
        signalSender?.invoke(conversationId, callId, signalType, targetUserId, sdpOrCandidate)
    }

    private fun startTimerIfNeeded() {
        if (timerJob?.isActive == true) return
        timerJob = timerScope.launch {
            while (isActive && state.value.phase == CallPhase.CONNECTED) {
                delay(1_000L)
                if (state.value.phase == CallPhase.CONNECTED) {
                    state.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1L) }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun releaseMediaResources() {
        pendingOfferSdp = null
        pendingRemoteIceCandidates.clear()
        runCatching { webRtcClient?.close() }
        webRtcClient = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        runCatching { audioFocus?.abandon() }
        audioFocus = null
    }

    override fun onCleared() {
        end()
        timerScope.cancel()
    }
}
