package vn.edu.hcmute.androidchat.feature.media.call

enum class GroupCallPhase { IDLE, JOINED, LEFT }

data class GroupCallUiState(
    val callId: String? = null,
    val participantIds: Set<Long> = emptySet(),
    val phase: GroupCallPhase = GroupCallPhase.IDLE,
    val conversationId: Long? = null,
    val localUserId: Long? = null,
    val media: CallMedia = CallMedia.VIDEO,
    val microphoneMuted: Boolean = false,
    val cameraEnabled: Boolean = true,
    val error: String? = null
)

class GroupCallController {
    var state: GroupCallUiState = GroupCallUiState()
        private set

    fun join(callId: String, participantId: Long) {
        require(callId.isNotBlank() && participantId > 0L)
        state = state.copy(
            callId = callId,
            participantIds = state.participantIds + participantId,
            phase = GroupCallPhase.JOINED,
            error = null
        )
    }

    fun configureSession(conversationId: Long, localUserId: Long, media: CallMedia) {
        require(conversationId > 0L && localUserId > 0L)
        state = state.copy(
            conversationId = conversationId,
            localUserId = localUserId,
            media = media,
            cameraEnabled = (media == CallMedia.VIDEO)
        )
    }

    fun leave(participantId: Long) {
        require(participantId > 0L)
        state = state.copy(participantIds = state.participantIds - participantId)
        if (state.participantIds.isEmpty()) state = state.copy(phase = GroupCallPhase.LEFT)
    }

    fun markLeft() {
        state = state.copy(
            participantIds = emptySet(),
            phase = GroupCallPhase.LEFT
        )
    }

    fun toggleMicrophone(): Boolean {
        val next = !state.microphoneMuted
        state = state.copy(microphoneMuted = next)
        return next
    }

    fun toggleCamera(): Boolean {
        val next = !state.cameraEnabled
        state = state.copy(cameraEnabled = next)
        return next
    }

    fun setError(message: String?) {
        state = state.copy(error = message)
    }
}
