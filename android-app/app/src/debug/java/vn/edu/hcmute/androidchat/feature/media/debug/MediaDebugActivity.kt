package vn.edu.hcmute.androidchat.feature.media.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.edu.hcmute.androidchat.feature.media.data.AndroidMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.StreamMediaRepository
import vn.edu.hcmute.androidchat.feature.media.ui.MediaPickerScreen
import vn.edu.hcmute.androidchat.feature.media.viewmodel.MediaViewModel
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

/** Debug-only host for exercising the SAF picker without changing the app's CHAT navigation. */
class MediaDebugActivity : ComponentActivity() {
    private val mediaViewModel: MediaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == MediaViewModel::class.java) {
                    "MediaDebugActivity only creates MediaViewModel"
                }
                val repository = StreamMediaRepository(
                    source = AndroidMediaSource(applicationContext.contentResolver)
                    // Keep the default UnconfiguredMediaUploadService. This host must not fake success.
                )
                @Suppress("UNCHECKED_CAST")
                return MediaViewModel(repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidChatTheme {
                MediaPickerScreen(
                    viewModel = mediaViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
