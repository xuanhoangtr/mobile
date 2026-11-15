package vn.edu.hcmute.androidchat.feature.media.voice

enum class VoicePhase {
    IDLE,
    RECORDING,
    RECORDED,
    PLAYING,
    PAUSED,
    UPLOADING,
    SENT,
    ERROR
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val filePath: String? = null,
    val durationMs: Long = 0L,
    val playbackPositionMs: Long = 0L,
    val attachment: VoiceAttachment? = null,
    val error: VoiceError? = null
)

enum class VoiceError {
    RECORDING_UNAVAILABLE,
    MICROPHONE_PERMISSION_REQUIRED,
    RECORDING_FAILED,
    PLAYBACK_FAILED,
    UPLOAD_FAILED
}

data class VoiceAttachment(
    val mediaId: Long,
    val mimeType: String,
    val byteSize: Long,
    val durationMs: Long,
    val messageType: String = "VOICE"
)
