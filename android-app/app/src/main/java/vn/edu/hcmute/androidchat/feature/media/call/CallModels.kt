package vn.edu.hcmute.androidchat.feature.media.call

enum class CallMedia { AUDIO, VIDEO }

enum class CallPhase {
    IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTED, RECONNECTING, DECLINED, ENDED, ERROR
}

data class CallUiState(
    val callId: String? = null,
    val peerId: Long? = null,
    val media: CallMedia = CallMedia.AUDIO,
    val phase: CallPhase = CallPhase.IDLE,
    val microphoneMuted: Boolean = false,
    val cameraEnabled: Boolean = true,
    val error: String? = null,
    val conversationId: Long? = null,
    val speakerEnabled: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val peerName: String? = null
)

object CallSignalTypes {
    const val INVITE = "INVITE"
    const val OFFER = "OFFER"
    const val ANSWER = "ANSWER"
    const val ICE_CANDIDATE = "ICE_CANDIDATE"
    const val ACCEPT = "ACCEPT"
    const val DECLINE = "DECLINE"
    const val END = "END"
    const val GROUP_JOIN = "GROUP_JOIN"
    const val GROUP_LEAVE = "GROUP_LEAVE"
}

typealias CallSignalSender = (
    conversationId: Long,
    callId: String,
    signalType: String,
    targetUserId: Long?,
    sdpOrCandidate: String?
) -> Unit

interface CallSignalingClient {
    suspend fun invite(calleeId: Long, media: CallMedia): String
    suspend fun answer(callId: String, accepted: Boolean)
    suspend fun end(callId: String)
}
