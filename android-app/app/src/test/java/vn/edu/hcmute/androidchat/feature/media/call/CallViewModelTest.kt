package vn.edu.hcmute.androidchat.feature.media.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    @Test
    fun incomingCallCanBeAcceptedAndEnded() {
        val viewModel = CallViewModel()
        viewModel.showIncoming("call-1", 8L, CallMedia.AUDIO)
        assertEquals(CallPhase.INCOMING_RINGING, viewModel.uiState.value.phase)

        viewModel.acceptIncoming()
        assertEquals(CallPhase.CONNECTED, viewModel.uiState.value.phase)
        viewModel.toggleMicrophone()
        assertEquals(true, viewModel.uiState.value.microphoneMuted)
        viewModel.end()
        assertEquals(CallPhase.ENDED, viewModel.uiState.value.phase)
    }

    @Test
    fun outgoingVideoCallControlsCamera() {
        val viewModel = CallViewModel()
        viewModel.startOutgoing(8L, CallMedia.VIDEO, "call-2")
        viewModel.markConnected()
        viewModel.toggleCamera()
        assertEquals(CallPhase.CONNECTED, viewModel.uiState.value.phase)
        assertEquals(false, viewModel.uiState.value.cameraEnabled)
        viewModel.onTransportLost()
        assertEquals(CallPhase.RECONNECTING, viewModel.uiState.value.phase)
        viewModel.markConnected()
        assertEquals(CallPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun ringingCallTimesOutWithVisibleError() {
        val viewModel = CallViewModel()
        viewModel.startOutgoing(8L, CallMedia.AUDIO, "call-timeout")
        viewModel.onTimeout()

        assertEquals(CallPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals("Call timed out", viewModel.uiState.value.error)
    }

    @Test
    fun incomingSignalsDriveStateAndSignalingCallbacksWithoutNativeWebRtc() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sentSignals = mutableListOf<Triple<String, String, Long?>>()
        val viewModel = CallViewModel(
            signalSender = { _, callId, signalType, targetUserId, _ ->
                sentSignals.add(Triple(callId, signalType, targetUserId))
            },
            timerDispatcher = dispatcher
        )

        viewModel.onIncomingSignal(
            context = null,
            conversationId = 55L,
            callId = "call-rtc-1",
            signalType = CallSignalTypes.INVITE,
            fromUserId = 12L,
            sdpOrCandidate = "VIDEO"
        )
        assertEquals(CallPhase.INCOMING_RINGING, viewModel.uiState.value.phase)
        assertEquals(CallMedia.VIDEO, viewModel.uiState.value.media)
        assertEquals(12L, viewModel.uiState.value.peerId)

        viewModel.acceptIncoming()
        runCurrent()
        advanceTimeBy(3_100L)
        assertTrue(viewModel.uiState.value.elapsedSeconds >= 3L)

        viewModel.toggleSpeaker()
        assertEquals(true, viewModel.uiState.value.speakerEnabled)

        viewModel.end()
        assertEquals(CallPhase.ENDED, viewModel.uiState.value.phase)
        assertEquals(listOf(Triple("call-rtc-1", CallSignalTypes.END, 12L)), sentSignals)
    }

    @Test
    fun declineIncomingSendsDeclineSignal() {
        val sentSignals = mutableListOf<String>()
        val viewModel = CallViewModel(
            signalSender = { _, _, signalType, _, _ -> sentSignals.add(signalType) }
        )
        viewModel.showIncoming("call-decline", 9L, CallMedia.AUDIO, conversationId = 33L)
        viewModel.declineIncoming()

        assertEquals(CallPhase.DECLINED, viewModel.uiState.value.phase)
        assertEquals(listOf(CallSignalTypes.DECLINE), sentSignals)
    }
}
