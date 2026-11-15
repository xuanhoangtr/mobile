package vn.edu.hcmute.androidchat.feature.media.call

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupCallControllerTest {
    @Test
    fun participantsCanJoinAndLeaveExperimentalCall() {
        val controller = GroupCallController()
        controller.join("group-call", 7L)
        controller.join("group-call", 8L)
        controller.join("group-call", 9L)
        assertEquals(setOf(7L, 8L, 9L), controller.state.participantIds)

        controller.leave(8L)
        assertEquals(setOf(7L, 9L), controller.state.participantIds)
        controller.leave(7L)
        controller.leave(9L)
        assertEquals(GroupCallPhase.LEFT, controller.state.phase)
    }

    @Test
    fun groupCallViewModelTracksParticipantsSignalsAndControls() {
        val signals = mutableListOf<String>()
        val viewModel = GroupCallViewModel(
            signalSender = { _, _, signalType, _, _ -> signals.add(signalType) }
        )

        viewModel.joinCall(
            context = null,
            conversationId = 200L,
            callId = "grp-1",
            localUserId = 10L,
            media = CallMedia.VIDEO
        )
        assertEquals(GroupCallPhase.JOINED, viewModel.uiState.value.phase)
        assertEquals(setOf(10L), viewModel.uiState.value.participantIds)
        assertEquals(listOf(CallSignalTypes.GROUP_JOIN), signals)

        viewModel.onIncomingSignal(
            context = null,
            conversationId = 200L,
            callId = "grp-1",
            signalType = CallSignalTypes.GROUP_JOIN,
            fromUserId = 15L
        )
        assertEquals(setOf(10L, 15L), viewModel.uiState.value.participantIds)

        // Signal addressed to another user should be ignored
        viewModel.onIncomingSignal(
            context = null,
            conversationId = 200L,
            callId = "grp-1",
            signalType = CallSignalTypes.GROUP_LEAVE,
            fromUserId = 15L,
            targetUserId = 99L
        )
        assertEquals(setOf(10L, 15L), viewModel.uiState.value.participantIds)

        viewModel.toggleMicrophone()
        viewModel.toggleCamera()
        assertEquals(true, viewModel.uiState.value.microphoneMuted)
        assertEquals(false, viewModel.uiState.value.cameraEnabled)

        viewModel.leaveCall()
        assertEquals(GroupCallPhase.LEFT, viewModel.uiState.value.phase)
        assertEquals(emptySet<Long>(), viewModel.uiState.value.participantIds)
        assertEquals(listOf(CallSignalTypes.GROUP_JOIN, CallSignalTypes.GROUP_LEAVE), signals)
    }
}
