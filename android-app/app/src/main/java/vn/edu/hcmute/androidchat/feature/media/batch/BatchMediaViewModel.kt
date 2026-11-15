package vn.edu.hcmute.androidchat.feature.media.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.MediaException
import java.util.concurrent.ConcurrentHashMap

class BatchMediaViewModel(private val repository: MediaRepository) : ViewModel() {
    private val state = MutableStateFlow(BatchMediaUiState())
    val uiState = state.asStateFlow()

    private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
    private val uploadJobs = ConcurrentHashMap<String, Job>()
    private var operation: Job? = null
    @Volatile private var generation = 0L

    fun select(uris: List<String>) {
        val currentGen = ++generation
        operation?.cancel()
        cancelAllUploadJobs()
        state.value.items.forEach { repository.release(it.selected) }
        state.value = BatchMediaUiState(isSelecting = true)
        operation = viewModelScope.launch {
            val errors = linkedMapOf<String, MediaError>()
            val selected = buildList {
                for (uri in uris.distinct()) {
                    try {
                        add(BatchMediaItem(repository.select(uri)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        errors[uri] = (failure as? MediaException)?.reason ?: MediaError.URI_ACCESS
                    }
                }
            }
            if (currentGen == generation) {
                state.value = BatchMediaUiState(items = selected, selectionErrors = errors)
            } else {
                selected.forEach { repository.release(it.selected) }
            }
        }
    }

    fun uploadAll(conversationId: Long? = null) {
        state.value.items.indices.forEach { upload(it, conversationId) }
    }

    fun retry(index: Int, conversationId: Long? = null) = upload(index, conversationId)

    fun upload(index: Int, conversationId: Long?) {
        val item = state.value.items.getOrNull(index) ?: return
        if (item.phase == BatchItemPhase.UPLOADING || item.phase == BatchItemPhase.UPLOADED) return

        val uriKey = item.selected.uri
        val currentGen = generation
        uploadJobs.remove(uriKey)?.cancel()
        updateItemByUri(uriKey) {
            it.copy(phase = BatchItemPhase.UPLOADING, bytesSent = 0L, error = null)
        }

        val job = viewModelScope.launch {
            try {
                uploadSemaphore.withPermit {
                    if (currentGen != generation) return@withPermit
                    val uploaded = repository.upload(item.selected, conversationId) { bytes ->
                        if (currentGen == generation) {
                            updateItemByUri(uriKey) { current ->
                                if (current.phase == BatchItemPhase.UPLOADING) {
                                    current.copy(bytesSent = bytes)
                                } else {
                                    current
                                }
                            }
                        }
                    }
                    if (currentGen == generation) {
                        updateItemByUri(uriKey) {
                            it.copy(phase = BatchItemPhase.UPLOADED, uploaded = uploaded, error = null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentGen == generation) {
                    updateItemByUri(uriKey) {
                        it.copy(
                            phase = BatchItemPhase.ERROR,
                            error = (failure as? MediaException)?.reason ?: MediaError.UPLOAD_FAILED
                        )
                    }
                }
            }
        }
        uploadJobs[uriKey] = job
        job.invokeOnCompletion { uploadJobs.remove(uriKey, job) }
    }

    fun clear() {
        ++generation
        operation?.cancel()
        operation = null
        cancelAllUploadJobs()
        state.value.items.forEach { repository.release(it.selected) }
        state.value = BatchMediaUiState()
    }

    private fun cancelAllUploadJobs() {
        val jobs = uploadJobs.values.toList()
        uploadJobs.clear()
        jobs.forEach { it.cancel() }
    }

    private fun updateItemByUri(uri: String, transform: (BatchMediaItem) -> BatchMediaItem) {
        state.update { current ->
            current.copy(items = current.items.map { item ->
                if (item.selected.uri == uri) transform(item) else item
            })
        }
    }

    override fun onCleared() {
        clear()
    }

    private companion object {
        const val MAX_CONCURRENT_UPLOADS = 2
    }
}
