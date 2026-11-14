package vn.edu.hcmute.androidchat.feature.media.model

data class SelectedMedia(
    val uri: String,
    val name: String,
    val mimeType: String,
    val byteSize: Long?,
    val hasPersistedReadPermission: Boolean = false
)

/** Upload completion is not CHAT delivery or a conversation-access grant. */
data class UploadedMedia(
    val mediaId: Long,
    val mimeType: String,
    val byteSize: Long,
    val fileName: String = ""
)

enum class MediaPhase { EMPTY, SELECTING, SELECTED, UPLOADING, UPLOADED, CANCELLED, ERROR }
enum class MediaError { URI_ACCESS, UNSUPPORTED_TYPE, TOO_LARGE, EMPTY_FILE, UPLOAD_FAILED, INTEGRATION_BLOCKED, PREVIEW_FAILED, PICKER_UNAVAILABLE }

data class MediaUiState(
    val selected: SelectedMedia? = null,
    val phase: MediaPhase = MediaPhase.EMPTY,
    val bytesSent: Long = 0,
    val uploaded: UploadedMedia? = null,
    val error: MediaError? = null
)

class MediaException(val reason: MediaError) : Exception(reason.name)

object MediaSelectionPolicy {
    const val MAX_BYTES = 10L * 1024 * 1024
    val supportedTypes = setOf(
        "image/jpeg", "image/png", "video/mp4", "audio/mp4", "audio/3gpp",
        "application/pdf", "text/plain"
    )

    fun validate(media: SelectedMedia) {
        if (media.mimeType !in supportedTypes) throw MediaException(MediaError.UNSUPPORTED_TYPE)
        if (media.byteSize != null && media.byteSize > MAX_BYTES) throw MediaException(MediaError.TOO_LARGE)
        if (media.byteSize == 0L) throw MediaException(MediaError.EMPTY_FILE)
    }
}
