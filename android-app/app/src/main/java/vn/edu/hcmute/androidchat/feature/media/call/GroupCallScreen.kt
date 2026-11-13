package vn.edu.hcmute.androidchat.feature.media.call

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun GroupCallScreen(state: GroupCallUiState) {
    Column {
        Text("Experimental group video call")
        Text("Participants: ${state.participantIds.size}")
        Text("Media transport requires an SFU/WebRTC provider")
    }
}
