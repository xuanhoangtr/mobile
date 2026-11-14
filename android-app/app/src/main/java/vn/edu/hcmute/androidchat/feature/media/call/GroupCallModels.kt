package vn.edu.hcmute.androidchat.feature.media.call

enum class GroupCallPhase { IDLE, JOINED, LEFT }

data class GroupCallUiState(
    val callId: String? = null,
    val participantIds: Set<Long> = emptySet(),
    val phase: GroupCallPhase = GroupCallPhase.IDLE
)

class GroupCallController {
    var state: GroupCallUiState = GroupCallUiState()
        private set

    fun join(callId: String, participantId: Long) {
        require(callId.isNotBlank() && participantId > 0L)
        state = state.copy(
            callId = callId,
            participantIds = state.participantIds + participantId,
            phase = GroupCallPhase.JOINED
        )
    }

    fun leave(participantId: Long) {
        require(participantId > 0L)
        state = state.copy(participantIds = state.participantIds - participantId)
        if (state.participantIds.isEmpty()) state = state.copy(phase = GroupCallPhase.LEFT)
    }
}
