package vn.edu.hcmute.androidchat.feature.media.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.edu.hcmute.androidchat.feature.media.batch.BatchMediaScreen
import vn.edu.hcmute.androidchat.feature.media.batch.BatchMediaViewModel
import vn.edu.hcmute.androidchat.feature.media.data.AndroidMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.StreamMediaRepository
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.data.UnconfiguredMediaUploadService
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

class BatchMediaDebugActivity : ComponentActivity() {
    private val viewModel: BatchMediaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val source = AndroidMediaSource(applicationContext.contentResolver)
                val repository: MediaRepository = StreamMediaRepository(source, UnconfiguredMediaUploadService)
                @Suppress("UNCHECKED_CAST")
                return BatchMediaViewModel(repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidChatTheme {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris -> viewModel.select(uris.map { it.toString() }) }
                BatchMediaScreen(
                    viewModel = viewModel,
                    onPick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                )
            }
        }
    }
}
