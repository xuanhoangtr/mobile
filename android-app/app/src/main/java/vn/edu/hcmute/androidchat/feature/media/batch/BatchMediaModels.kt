package vn.edu.hcmute.androidchat.feature.media.batch

import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia

enum class BatchItemPhase { SELECTING, READY, UPLOADING, UPLOADED, ERROR }

data class BatchMediaItem(
    val selected: SelectedMedia,
    val phase: BatchItemPhase = BatchItemPhase.READY,
    val bytesSent: Long = 0L,
    val uploaded: UploadedMedia? = null,
    val error: MediaError? = null
)

data class BatchMediaUiState(
    val items: List<BatchMediaItem> = emptyList(),
    val isSelecting: Boolean = false,
    val selectionErrors: Map<String, MediaError> = emptyMap()
)
