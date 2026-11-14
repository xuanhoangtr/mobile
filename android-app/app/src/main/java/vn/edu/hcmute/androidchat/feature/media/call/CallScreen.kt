package vn.edu.hcmute.androidchat.feature.media.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(viewModel: CallViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Call: ${state.phase}")
        if (state.media == CallMedia.VIDEO) {
            Text(if (state.cameraEnabled) "Local video preview" else "Camera off")
            Text("Remote video: signaling POC; WebRTC provider required")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::toggleMicrophone) {
                Text(if (state.microphoneMuted) "Unmute" else "Mute")
            }
            if (state.media == CallMedia.VIDEO) {
                Button(onClick = viewModel::toggleCamera) {
                    Text(if (state.cameraEnabled) "Camera off" else "Camera on")
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = viewModel::end) {
                Text("End")
            }
        }
        if (state.phase == CallPhase.INCOMING_RINGING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::acceptIncoming) { Text("Accept") }
                Button(onClick = viewModel::declineIncoming) { Text("Decline") }
            }
        }
    }
}
