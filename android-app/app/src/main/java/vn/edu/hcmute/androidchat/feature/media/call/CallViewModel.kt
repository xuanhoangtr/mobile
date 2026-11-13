package vn.edu.hcmute.androidchat.feature.media.call

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CallViewModel : ViewModel() {
    private val state = MutableStateFlow(CallUiState())
    val uiState = state.asStateFlow()

    fun startOutgoing(calleeId: Long, media: CallMedia, callId: String) {
        require(calleeId > 0L)
        state.value = CallUiState(callId, calleeId, media, CallPhase.OUTGOING_RINGING)
    }

    fun showIncoming(callId: String, callerId: Long, media: CallMedia) {
        require(callId.isNotBlank() && callerId > 0L)
        state.value = CallUiState(callId, callerId, media, CallPhase.INCOMING_RINGING)
    }

    fun acceptIncoming() {
        if (state.value.phase == CallPhase.INCOMING_RINGING) {
            state.value = state.value.copy(phase = CallPhase.CONNECTED)
        }
    }

    fun declineIncoming() {
        if (state.value.phase == CallPhase.INCOMING_RINGING) {
            state.value = state.value.copy(phase = CallPhase.DECLINED)
        }
    }

    fun markConnected() {
        if (state.value.phase == CallPhase.OUTGOING_RINGING) {
            state.value = state.value.copy(phase = CallPhase.CONNECTED)
        }
    }

    fun toggleMicrophone() {
        state.value = state.value.copy(microphoneMuted = !state.value.microphoneMuted)
    }

    fun toggleCamera() {
        state.value = state.value.copy(cameraEnabled = !state.value.cameraEnabled)
    }

    fun end() {
        if (state.value.phase != CallPhase.IDLE) {
            state.value = state.value.copy(phase = CallPhase.ENDED)
        }
    }
}
