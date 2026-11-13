package vn.edu.hcmute.androidchat.feature.media.debug

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoicePlayback
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoiceRecorder
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceMessageViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.VoicePhase
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

class VoiceDebugActivity : ComponentActivity() {
    private val viewModel: VoiceMessageViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == VoiceMessageViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return VoiceMessageViewModel(
                    recorder = AndroidVoiceRecorder(),
                    playback = AndroidVoicePlayback(),
                    outputDirectory = cacheDir
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidChatTheme {
                val state by viewModel.uiState.collectAsState()
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> if (granted) viewModel.startRecording() }
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Voice message debug")
                    Text("State: ${state.phase}, duration: ${state.durationMs} ms")
                    when (state.phase) {
                        VoicePhase.RECORDING -> Button(onClick = viewModel::stopRecording) {
                            Text("Stop recording")
                        }
                        VoicePhase.RECORDED, VoicePhase.PLAYING -> {
                            Button(onClick = viewModel::togglePlayback) {
                                Text(if (state.phase == VoicePhase.PLAYING) "Stop playback" else "Play preview")
                            }
                            OutlinedButton(onClick = viewModel::clear) { Text("Discard") }
                        }
                        else -> Button(onClick = {
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text("Record voice message") }
                    }
                    state.error?.let { Text("Error: $it") }
                }
            }
        }
    }
}
