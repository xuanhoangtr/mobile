package vn.edu.hcmute.androidchat.feature.media.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.model.*

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {
    private val state = MutableStateFlow(MediaUiState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    @Volatile private var generation = 0L

    fun pickerOpened() { if (state.value.phase != MediaPhase.UPLOADING) state.update { it.copy(phase = MediaPhase.SELECTING, error = null) } }

    fun pickerLaunchFailed() {
        state.update {
            it.copy(
                phase = if (it.selected == null) MediaPhase.ERROR else MediaPhase.SELECTED,
                error = MediaError.PICKER_UNAVAILABLE
            )
        }
    }

    fun onPickerResult(uri: String?) {
        if (uri == null) {
            state.update { it.copy(phase = when {
                it.uploaded != null -> MediaPhase.UPLOADED
                it.selected != null -> MediaPhase.SELECTED
                else -> MediaPhase.CANCELLED
            }) }
            return
        }
        operation?.cancel()
        state.value.selected?.let(repository::release)
        val current = ++generation
        state.value = MediaUiState(phase = MediaPhase.SELECTING)
        operation = viewModelScope.launch {
            try {
                val media = repository.select(uri)
                if (current == generation) state.value = MediaUiState(selected = media, phase = MediaPhase.SELECTED)
                else repository.release(media)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current == generation) fail(failure, MediaError.URI_ACCESS) }
        }
    }

    fun upload(conversationId: Long? = null) {
        val media = state.value.selected ?: return
        if (state.value.phase in setOf(MediaPhase.UPLOADING, MediaPhase.UPLOADED, MediaPhase.SELECTING)) return
        val current = ++generation
        state.update { it.copy(phase = MediaPhase.UPLOADING, bytesSent = 0, uploaded = null, error = null) }
        operation = viewModelScope.launch {
            try {
                val uploaded = repository.upload(media, conversationId) { bytes ->
                    state.update { if (current == generation && it.phase == MediaPhase.UPLOADING) it.copy(bytesSent = bytes) else it }
                }
                if (current == generation) state.update { it.copy(phase = MediaPhase.UPLOADED, uploaded = uploaded) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current == generation) fail(failure, MediaError.UPLOAD_FAILED) }
        }
    }

    fun cancel() {
        ++generation
        operation?.cancel()
        state.update { it.copy(phase = MediaPhase.CANCELLED, bytesSent = 0, uploaded = null, error = null) }
    }

    fun clear() { cancel(); state.value.selected?.let(repository::release); state.value = MediaUiState() }
    fun previewFailed() { state.update { it.copy(error = MediaError.PREVIEW_FAILED) } }

    private fun fail(failure: Exception, fallback: MediaError) {
        state.update { it.copy(phase = MediaPhase.ERROR, error = (failure as? MediaException)?.reason ?: fallback) }
    }

    override fun onCleared() { state.value.selected?.let(repository::release) }
}
