package vn.edu.hcmute.androidchat.feature.media.call

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.EglBase
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

class GroupCallViewModel(
    private var signalSender: CallSignalSender? = null,
    private val peerClientFactory: ((Context, Long, GroupCallViewModel) -> WebRtcCallClient)? = null
) : ViewModel() {

    private val controller = GroupCallController()

    private val _uiState = MutableStateFlow(controller.state)
    val uiState: StateFlow<GroupCallUiState> = _uiState.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<Long, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<Long, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val peerClients = ConcurrentHashMap<Long, WebRtcCallClient>()
    private val pendingIceByPeer = ConcurrentHashMap<Long, MutableList<String>>()
    private var audioFocus: CallAudioFocus? = null

    val eglBaseContext: EglBase.Context?
        get() = peerClients.values.firstOrNull()?.eglBaseContext

    fun attachSignaling(sender: CallSignalSender?) {
        signalSender = sender
    }

    fun joinCall(
        context: Context?,
        conversationId: Long,
        callId: String,
        localUserId: Long,
        media: CallMedia = CallMedia.VIDEO,
        initialParticipants: Set<Long> = emptySet()
    ) {
        require(conversationId > 0L && callId.isNotBlank() && localUserId > 0L)
        closeAllPeers()
        controller.configureSession(conversationId, localUserId, media)
        controller.join(callId, localUserId)
        syncState()

        if (context != null) {
            requestAudioFocusSafely(context)
        }

        sendSignal(conversationId, callId, CallSignalTypes.GROUP_JOIN, null, media.name)

        initialParticipants
            .filter { it > 0L && it != localUserId }
            .forEach { peerId ->
                onParticipantJoined(
                    context = context,
                    participantId = peerId,
                    initiateOffer = localUserId < peerId
                )
            }
    }

    fun onParticipantJoined(
        context: Context?,
        participantId: Long,
        initiateOffer: Boolean = true
    ) {
        val current = controller.state
        val callId = current.callId ?: return
        val convId = current.conversationId ?: return
        if (participantId <= 0L) return

        controller.join(callId, participantId)
        syncState()

        if (participantId == current.localUserId || context == null) return

        val client = getOrCreatePeerClient(context, participantId, current.media == CallMedia.VIDEO) ?: return
        if (initiateOffer) {
            client.createOffer(
                onSdp = { sdp ->
                    sendSignal(convId, callId, CallSignalTypes.OFFER, participantId, sdp)
                },
                onError = { err ->
                    controller.setError(err)
                    syncState()
                }
            )
        }
    }

    fun onParticipantLeft(participantId: Long) {
        if (participantId <= 0L) return
        controller.leave(participantId)
        syncState()
        removePeerClient(participantId)
    }

    fun onIncomingSignal(
        context: Context?,
        conversationId: Long,
        callId: String,
        signalType: String,
        fromUserId: Long,
        targetUserId: Long? = null,
        sdpOrCandidate: String? = null
    ) {
        val current = controller.state
        if (current.callId != null && current.callId != callId) return
        if (targetUserId != null && current.localUserId != null && targetUserId != current.localUserId) {
            return
        }
        if (fromUserId <= 0L) return

        when (signalType.uppercase()) {
            CallSignalTypes.GROUP_JOIN -> {
                val activeCallId = current.callId ?: callId
                controller.join(activeCallId, fromUserId)
                syncState()
                val localId = controller.state.localUserId
                if (context != null && localId != null && fromUserId != localId) {
                    val shouldOffer = localId < fromUserId
                    val client = getOrCreatePeerClient(
                        context,
                        fromUserId,
                        controller.state.media == CallMedia.VIDEO
                    )
                    if (shouldOffer && client != null) {
                        client.createOffer(onSdp = { sdp ->
                            sendSignal(conversationId, activeCallId, CallSignalTypes.OFFER, fromUserId, sdp)
                        })
                    }
                }
            }

            CallSignalTypes.OFFER -> {
                val activeCallId = current.callId ?: callId
                controller.join(activeCallId, fromUserId)
                syncState()
                if (context != null && sdpOrCandidate != null && fromUserId != controller.state.localUserId) {
                    val client = getOrCreatePeerClient(
                        context,
                        fromUserId,
                        controller.state.media == CallMedia.VIDEO
                    ) ?: return
                    client.setRemoteDescription(
                        type = SessionDescription.Type.OFFER,
                        sdp = sdpOrCandidate,
                        onDone = {
                            client.createAnswer(onSdp = { answer ->
                                sendSignal(conversationId, activeCallId, CallSignalTypes.ANSWER, fromUserId, answer)
                            })
                        },
                        onError = { err ->
                            controller.setError(err)
                            syncState()
                        }
                    )
                }
            }

            CallSignalTypes.ANSWER -> {
                if (sdpOrCandidate == null) return
                peerClients[fromUserId]?.setRemoteDescription(
                    type = SessionDescription.Type.ANSWER,
                    sdp = sdpOrCandidate,
                    onError = { err ->
                        controller.setError(err)
                        syncState()
                    }
                )
            }

            CallSignalTypes.ICE_CANDIDATE -> {
                if (sdpOrCandidate.isNullOrBlank()) return
                val client = peerClients[fromUserId]
                if (client != null) {
                    client.addIceCandidateJson(sdpOrCandidate)
                } else {
                    pendingIceByPeer.getOrPut(fromUserId) { mutableListOf() }.add(sdpOrCandidate)
                }
            }

            CallSignalTypes.GROUP_LEAVE, CallSignalTypes.END -> {
                onParticipantLeft(fromUserId)
            }
        }
    }

    fun toggleMicrophone() {
        val muted = controller.toggleMicrophone()
        syncState()
        peerClients.values.forEach { it.setMicEnabled(!muted) }
    }

    fun toggleCamera() {
        val enabled = controller.toggleCamera()
        syncState()
        peerClients.values.forEach { it.setCameraEnabled(enabled) }
    }

    fun leaveCall() {
        val current = controller.state
        val convId = current.conversationId
        val callId = current.callId
        val localId = current.localUserId
        if (current.phase == GroupCallPhase.JOINED && convId != null && callId != null) {
            sendSignal(convId, callId, CallSignalTypes.GROUP_LEAVE, null, localId?.toString())
        }
        controller.markLeft()
        syncState()
        closeAllPeers()
    }

    private fun getOrCreatePeerClient(
        context: Context,
        peerId: Long,
        isVideo: Boolean
    ): WebRtcCallClient? {
        peerClients[peerId]?.let { return it }

        val client = runCatching {
            peerClientFactory?.invoke(context, peerId, this) ?: WebRtcCallClient(
                context = context,
                onLocalIceCandidate = { candidate ->
                    val current = controller.state
                    val convId = current.conversationId
                    val callId = current.callId
                    if (convId != null && callId != null) {
                        sendSignal(
                            convId,
                            callId,
                            CallSignalTypes.ICE_CANDIDATE,
                            peerId,
                            WebRtcCallClient.iceCandidateToJson(candidate)
                        )
                    }
                },
                onLocalVideoTrack = { track ->
                    if (_localVideoTrack.value == null && track != null) {
                        _localVideoTrack.value = track
                    }
                },
                onRemoteVideoTrack = { track ->
                    _remoteVideoTracks.update { currentMap ->
                        if (track != null) currentMap + (peerId to track) else currentMap - peerId
                    }
                }
            )
        }.getOrNull() ?: return null

        if (!client.start(isVideo)) {
            client.close()
            return null
        }

        client.setMicEnabled(!controller.state.microphoneMuted)
        if (isVideo) {
            client.setCameraEnabled(controller.state.cameraEnabled)
        }
        peerClients[peerId] = client
        if (_localVideoTrack.value == null) {
            _localVideoTrack.value = client.localVideoTrackFlow.value
        }

        pendingIceByPeer.remove(peerId)?.forEach { candidateJson ->
            client.addIceCandidateJson(candidateJson)
        }
        return client
    }

    private fun removePeerClient(peerId: Long) {
        pendingIceByPeer.remove(peerId)
        peerClients.remove(peerId)?.let { client ->
            runCatching { client.close() }
        }
        _remoteVideoTracks.update { it - peerId }
    }

    private fun closeAllPeers() {
        pendingIceByPeer.clear()
        val clients = peerClients.values.toList()
        peerClients.clear()
        clients.forEach { client -> runCatching { client.close() } }
        _remoteVideoTracks.value = emptyMap()
        _localVideoTrack.value = null
        runCatching { audioFocus?.abandon() }
        audioFocus = null
    }

    private fun requestAudioFocusSafely(context: Context) {
        runCatching {
            audioFocus?.abandon()
            audioFocus = CallAudioFocus(context) {
                if (!controller.state.microphoneMuted && controller.state.phase == GroupCallPhase.JOINED) {
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

    private fun syncState() {
        _uiState.value = controller.state
    }

    override fun onCleared() {
        leaveCall()
    }
}
