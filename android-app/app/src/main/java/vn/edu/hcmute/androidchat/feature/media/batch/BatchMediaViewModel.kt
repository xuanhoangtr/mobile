package vn.edu.hcmute.androidchat.feature.media.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.MediaException

class BatchMediaViewModel(private val repository: MediaRepository) : ViewModel() {
    private val state = MutableStateFlow(BatchMediaUiState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null

    fun select(uris: List<String>) {
        operation?.cancel()
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
            state.value = BatchMediaUiState(items = selected, selectionErrors = errors)
        }
    }

    fun uploadAll(conversationId: Long? = null) {
        state.value.items.indices.forEach { upload(it, conversationId) }
    }

    fun retry(index: Int, conversationId: Long? = null) = upload(index, conversationId)

    fun upload(index: Int, conversationId: Long?) {
        val item = state.value.items.getOrNull(index) ?: return
        if (item.phase == BatchItemPhase.UPLOADING || item.phase == BatchItemPhase.UPLOADED) return
        updateItem(index) { it.copy(phase = BatchItemPhase.UPLOADING, bytesSent = 0L, error = null) }
        viewModelScope.launch {
            try {
                val uploaded = repository.upload(item.selected, conversationId) { bytes ->
                    updateItem(index) { current ->
                        if (current.phase == BatchItemPhase.UPLOADING) current.copy(bytesSent = bytes) else current
                    }
                }
                updateItem(index) { it.copy(phase = BatchItemPhase.UPLOADED, uploaded = uploaded) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateItem(index) {
                    it.copy(
                        phase = BatchItemPhase.ERROR,
                        error = (failure as? vn.edu.hcmute.androidchat.feature.media.model.MediaException)?.reason
                            ?: MediaError.UPLOAD_FAILED
                    )
                }
            }
        }
    }

    fun clear() {
        operation?.cancel()
        state.value.items.forEach { repository.release(it.selected) }
        state.value = BatchMediaUiState()
    }

    private fun updateItem(index: Int, transform: (BatchMediaItem) -> BatchMediaItem) {
        state.update { current ->
            current.copy(items = current.items.mapIndexed { itemIndex, item ->
                if (itemIndex == index) transform(item) else item
            })
        }
    }

    override fun onCleared() {
        clear()
    }
}
