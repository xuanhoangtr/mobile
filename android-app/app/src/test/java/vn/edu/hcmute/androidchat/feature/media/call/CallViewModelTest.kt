package vn.edu.hcmute.androidchat.feature.media.call

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
