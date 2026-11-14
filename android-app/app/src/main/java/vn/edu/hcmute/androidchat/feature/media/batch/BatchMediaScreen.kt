package vn.edu.hcmute.androidchat.feature.media.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BatchMediaScreen(
    viewModel: BatchMediaViewModel,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPick) { Text("Select multiple images") }
        state.items.forEachIndexed { index, item ->
            Text("${item.selected.name}: ${item.phase}")
            if (item.phase == BatchItemPhase.UPLOADING && item.selected.byteSize != null) {
                LinearProgressIndicator(
                    progress = { (item.bytesSent.toFloat() / item.selected.byteSize).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (item.phase == BatchItemPhase.ERROR) {
                Button(onClick = { viewModel.retry(index) }) { Text("Retry ${item.selected.name}") }
            }
        }
        state.selectionErrors.forEach { (uri, error) -> Text("Could not select $uri: $error") }
        if (state.items.isNotEmpty()) {
            Button(onClick = { viewModel.uploadAll() }) { Text("Upload all") }
        }
    }
}
