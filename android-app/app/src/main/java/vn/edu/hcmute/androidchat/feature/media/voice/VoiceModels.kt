package vn.edu.hcmute.androidchat.feature.media.voice

enum class VoicePhase {
    IDLE,
    RECORDING,
    RECORDED,
    PLAYING,
    ERROR
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val filePath: String? = null,
    val durationMs: Long = 0L,
    val error: VoiceError? = null
)

enum class VoiceError {
    RECORDING_UNAVAILABLE,
    MICROPHONE_PERMISSION_REQUIRED,
    RECORDING_FAILED,
    PLAYBACK_FAILED
}
