package vn.edu.hcmute.androidchat.feature.media.call

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList

open class WebRtcCallClient(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer> = defaultIceServers(),
    private val onLocalIceCandidate: (IceCandidate) -> Unit = {},
    private val onRemoteVideoTrack: (VideoTrack?) -> Unit = {},
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onLocalVideoTrack: (VideoTrack?) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var eglBase: EglBase? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null

    private var remoteDescriptionSet = false
    private val pendingIceCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var isVideoCall = false
    private var isClosed = false

    private val _localVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrackFlow.asStateFlow()

    private val _remoteVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrackFlow: StateFlow<VideoTrack?> = _remoteVideoTrackFlow.asStateFlow()

    val eglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    open fun start(isVideo: Boolean): Boolean {
        if (isClosed) return false
        isVideoCall = isVideo
        return runCatching {
            ensureInitialized()
            if (peerConnection != null) return@runCatching true

            val factory = peerConnectionFactory ?: return@runCatching false
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = factory.createPeerConnection(rtcConfig, createObserver())
                ?: return@runCatching false
            peerConnection = pc

            val audioConstraints = MediaConstraints()
            val createdAudioSource = factory.createAudioSource(audioConstraints)
            audioSource = createdAudioSource
            val createdAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, createdAudioSource)
            localAudioTrack = createdAudioTrack
            pc.addTrack(createdAudioTrack, listOf(STREAM_ID))

            if (isVideo) {
                setupLocalVideo(factory, pc)
            }
            true
        }.getOrDefault(false)
    }

    private fun ensureInitialized() {
        if (!factoryInitialized) {
            synchronized(initLock) {
                if (!factoryInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    factoryInitialized = true
                }
            }
        }
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        if (peerConnectionFactory == null) {
            val eglCtx = eglBase?.eglBaseContext
            val adm = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
            audioDeviceModule = adm
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglCtx, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglCtx))
                .createPeerConnectionFactory()
        }
    }

    private fun setupLocalVideo(factory: PeerConnectionFactory, pc: PeerConnection) {
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        val textureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", eglBase?.eglBaseContext)
        surfaceTextureHelper = textureHelper
        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(textureHelper, appContext, source.capturerObserver)
        runCatching { capturer.startCapture(720, 1280, 30) }
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        localVideoTrack = track
        _localVideoTrackFlow.value = track
        onLocalVideoTrack(track)
        pc.addTrack(track, listOf(STREAM_ID))
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        if (frontCamera != null) {
            enumerator.createCapturer(frontCamera, null)?.let { return it }
        }
        val backCamera = deviceNames.firstOrNull { !enumerator.isFrontFacing(it) }
        return backCamera?.let { enumerator.createCapturer(it, null) }
    }

    private fun createObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    onConnectionStateChanged(PeerConnection.PeerConnectionState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    onConnectionStateChanged(PeerConnection.PeerConnectionState.DISCONNECTED)
                PeerConnection.IceConnectionState.FAILED ->
                    onConnectionStateChanged(PeerConnection.PeerConnectionState.FAILED)
                PeerConnection.IceConnectionState.CLOSED ->
                    onConnectionStateChanged(PeerConnection.PeerConnectionState.CLOSED)
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState != null) onConnectionStateChanged(newState)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate != null) onLocalIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            val track = stream?.videoTracks?.firstOrNull() ?: return
            _remoteVideoTrackFlow.value = track
            onRemoteVideoTrack(track)
        }

        override fun onRemoveStream(stream: MediaStream?) {
            if (stream?.videoTracks?.any { it.id() == _remoteVideoTrackFlow.value?.id() } == true) {
                _remoteVideoTrackFlow.value = null
                onRemoteVideoTrack(null)
            }
        }

        override fun onDataChannel(dataChannel: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack ?: return
            _remoteVideoTrackFlow.value = track
            onRemoteVideoTrack(track)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            _remoteVideoTrackFlow.value = track
            onRemoteVideoTrack(track)
        }
    }

    open fun createOffer(onSdp: (String) -> Unit, onError: (String) -> Unit = {}) {
        val pc = peerConnection ?: run {
            onError("PeerConnection not initialized")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
        }
        runCatching {
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) {
                        onError("Empty SDP offer")
                        return
                    }
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() = onSdp(sdp.description)
                        override fun onSetFailure(error: String?) = onError(error ?: "Failed to set local offer")
                    }, sdp)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) = onError(error ?: "Failed to create offer")
                override fun onSetFailure(error: String?) = onError(error ?: "Failed to set local offer")
            }, constraints)
        }.onFailure { onError(it.message ?: "createOffer failed") }
    }

    open fun createAnswer(onSdp: (String) -> Unit, onError: (String) -> Unit = {}) {
        val pc = peerConnection ?: run {
            onError("PeerConnection not initialized")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
        }
        runCatching {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) {
                        onError("Empty SDP answer")
                        return
                    }
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() = onSdp(sdp.description)
                        override fun onSetFailure(error: String?) = onError(error ?: "Failed to set local answer")
                    }, sdp)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) = onError(error ?: "Failed to create answer")
                override fun onSetFailure(error: String?) = onError(error ?: "Failed to set local answer")
            }, constraints)
        }.onFailure { onError(it.message ?: "createAnswer failed") }
    }

    open fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val pc = peerConnection ?: run {
            onError("PeerConnection not initialized")
            return
        }
        runCatching {
            val sessionDescription = SessionDescription(type, sdp)
            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIceCandidates(pc)
                    onDone()
                }

                override fun onSetFailure(error: String?) {
                    onError(error ?: "Failed to set remote description")
                }
            }, sessionDescription)
        }.onFailure { onError(it.message ?: "setRemoteDescription failed") }
    }

    open fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val pc = peerConnection
        if (pc != null && remoteDescriptionSet) {
            runCatching { pc.addIceCandidate(iceCandidate) }
        } else {
            pendingIceCandidates.add(iceCandidate)
        }
    }

    open fun addIceCandidateJson(rawCandidate: String) {
        val parsed = parseIceCandidate(rawCandidate) ?: return
        addIceCandidate(parsed.sdpMid ?: "0", parsed.sdpMLineIndex, parsed.sdp)
    }

    private fun flushPendingIceCandidates(pc: PeerConnection) {
        val queued = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        queued.forEach { candidate ->
            runCatching { pc.addIceCandidate(candidate) }
        }
    }

    open fun setMicEnabled(enabled: Boolean) {
        runCatching {
            localAudioTrack?.setEnabled(enabled)
            audioDeviceModule?.setMicrophoneMute(!enabled)
        }
    }

    open fun setCameraEnabled(enabled: Boolean) {
        runCatching {
            localVideoTrack?.setEnabled(enabled)
            val capturer = videoCapturer ?: return@runCatching
            if (enabled) {
                capturer.startCapture(720, 1280, 30)
            } else {
                capturer.stopCapture()
            }
        }
    }

    open fun switchCamera() {
        runCatching {
            (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        }
    }

    open fun close() {
        if (isClosed) return
        isClosed = true
        remoteDescriptionSet = false
        pendingIceCandidates.clear()

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localVideoTrack?.setEnabled(false) }
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        _localVideoTrackFlow.value = null
        onLocalVideoTrack(null)

        _remoteVideoTrackFlow.value = null
        onRemoteVideoTrack(null)

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { localAudioTrack?.setEnabled(false) }
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null

        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null

        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null

        runCatching { eglBase?.release() }
        eglBase = null
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val STREAM_ID = "androidchat_stream"
        private const val AUDIO_TRACK_ID = "androidchat_audio_0"
        private const val VIDEO_TRACK_ID = "androidchat_video_0"

        private val initLock = Any()
        @Volatile
        private var factoryInitialized = false

        fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        fun iceCandidateToJson(candidate: IceCandidate): String =
            JSONObject()
                .put("sdpMid", candidate.sdpMid ?: "0")
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp)
                .toString()

        fun parseIceCandidate(raw: String): IceCandidate? {
            if (raw.isBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                val sdpMid = obj.optString("sdpMid", "0")
                val sdpMLineIndex = obj.optInt("sdpMLineIndex", 0)
                val candidate = obj.getString("candidate")
                IceCandidate(sdpMid, sdpMLineIndex, candidate)
            }.getOrNull()
        }
    }
}
