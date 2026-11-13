package vn.edu.hcmute.androidchat.feature.media.call

enum class CallMedia { AUDIO, VIDEO }
enum class CallPhase { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTED, DECLINED, ENDED, ERROR }

data class CallUiState(
    val callId: String? = null,
    val peerId: Long? = null,
    val media: CallMedia = CallMedia.AUDIO,
    val phase: CallPhase = CallPhase.IDLE,
    val microphoneMuted: Boolean = false,
    val cameraEnabled: Boolean = true,
    val error: String? = null
)

interface CallSignalingClient {
    suspend fun invite(calleeId: Long, media: CallMedia): String
    suspend fun answer(callId: String, accepted: Boolean)
    suspend fun end(callId: String)
}
