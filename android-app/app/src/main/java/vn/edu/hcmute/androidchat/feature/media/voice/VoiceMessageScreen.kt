package vn.edu.hcmute.androidchat.feature.media.voice

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun VoiceMessageScreen(
    viewModel: VoiceMessageViewModel,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Voice message")
        Text("State: ${state.phase}")
        Text("Duration: ${formatDuration(state.durationMs)}")
        when {
            !hasMicrophonePermission -> Button(onClick = onRequestMicrophonePermission) {
                Text("Allow microphone")
            }
            state.phase == VoicePhase.IDLE || state.phase == VoicePhase.ERROR -> {
                Text(
                    "Press and hold to record",
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.startRecording()
                                    try {
                                        tryAwaitRelease()
                                        viewModel.stopRecording()
                                    } catch (_: kotlinx.coroutines.CancellationException) {
                                        viewModel.interrupt()
                                    }
                                }
                            )
                        }
                        .padding(vertical = 28.dp)
                )
            }
            state.phase == VoicePhase.RECORDING -> {
                Text("Release to stop. Tap outside to cancel.")
                OutlinedButton(onClick = viewModel::interrupt) { Text("Cancel") }
            }
            state.phase == VoicePhase.RECORDED ||
                state.phase == VoicePhase.PLAYING ||
                state.phase == VoicePhase.PAUSED -> {
                Button(onClick = viewModel::togglePlayback) {
                    Text(if (state.phase == VoicePhase.PLAYING) "Pause" else "Play")
                }
                LinearProgressIndicator(
                    progress = {
                        if (state.durationMs == 0L) 0f
                        else (state.playbackPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.upload() }) { Text("Send voice") }
                OutlinedButton(onClick = viewModel::clear) { Text("Discard") }
            }
            state.phase == VoicePhase.UPLOADING -> Text("Uploading voice...")
            state.phase == VoicePhase.SENT -> Text("Voice sent (${state.attachment?.messageType})")
        }
        state.error?.let { Text("Error: $it") }
    }
}

private fun formatDuration(durationMs: Long): String =
    "%d:%02d".format(durationMs / 60_000, (durationMs / 1_000) % 60)
