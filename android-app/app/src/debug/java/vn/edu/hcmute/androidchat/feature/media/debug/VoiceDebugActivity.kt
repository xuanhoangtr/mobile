package vn.edu.hcmute.androidchat.feature.media.debug

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoicePlayback
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoiceRecorder
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceMessageViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceMessageScreen
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

class VoiceDebugActivity : ComponentActivity() {
    private val viewModel: VoiceMessageViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == VoiceMessageViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return VoiceMessageViewModel(
                    recorder = AndroidVoiceRecorder(),
                    playback = AndroidVoicePlayback(applicationContext),
                    outputDirectory = cacheDir
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                viewModel.interrupt()
            }
        })
        setContent {
            AndroidChatTheme {
                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasPermission = granted }
                VoiceMessageScreen(
                    viewModel = viewModel,
                    hasMicrophonePermission = hasPermission,
                    onRequestMicrophonePermission = {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }
}
