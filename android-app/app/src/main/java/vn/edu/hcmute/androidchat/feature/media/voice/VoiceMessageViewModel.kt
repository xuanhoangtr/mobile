package vn.edu.hcmute.androidchat.feature.media.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class VoiceMessageViewModel(
    private val recorder: VoiceRecorder,
    private val playback: VoicePlayback,
    private val outputDirectory: File,
    private val nowMs: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val state = MutableStateFlow(VoiceUiState())
    val uiState = state.asStateFlow()
    private var timer: Job? = null
    private var recordingStartedAt = 0L

    fun startRecording() {
        if (state.value.phase == VoicePhase.RECORDING) return
        val output = File(outputDirectory, "voice-${nowMs()}.m4a")
        try {
            recorder.start(output)
            recordingStartedAt = nowMs()
            state.value = VoiceUiState(phase = VoicePhase.RECORDING, filePath = output.absolutePath)
            timer = viewModelScope.launch {
                while (isActive) {
                    delay(200)
                    state.update { current ->
                        if (current.phase == VoicePhase.RECORDING) {
                            current.copy(durationMs = (nowMs() - recordingStartedAt).coerceAtLeast(0L))
                        } else current
                    }
                }
            }
        } catch (_: SecurityException) {
            state.value = VoiceUiState(error = VoiceError.MICROPHONE_PERMISSION_REQUIRED)
        } catch (_: Exception) {
            state.value = VoiceUiState(error = VoiceError.RECORDING_FAILED)
        }
    }

    fun stopRecording() {
        if (state.value.phase != VoicePhase.RECORDING) return
        timer?.cancel()
        try {
            val bytes = recorder.stop()
            val path = state.value.filePath
            if (path == null || bytes <= 0L) {
                clear()
            } else {
                state.update { it.copy(phase = VoicePhase.RECORDED, durationMs = it.durationMs.coerceAtLeast(1L)) }
            }
        } catch (_: Exception) {
            state.value.filePath?.let(::File)?.delete()
            state.value = VoiceUiState(error = VoiceError.RECORDING_FAILED)
        }
    }

    fun togglePlayback() {
        val current = state.value
        val path = current.filePath ?: return
        when (current.phase) {
            VoicePhase.PLAYING -> {
                playback.stop()
                state.update { it.copy(phase = VoicePhase.RECORDED) }
            }
            VoicePhase.RECORDED -> try {
                playback.play(path) {
                    state.update { if (it.filePath == path) it.copy(phase = VoicePhase.RECORDED) else it }
                }
                state.update { it.copy(phase = VoicePhase.PLAYING) }
            } catch (_: Exception) {
                state.update { it.copy(phase = VoicePhase.ERROR, error = VoiceError.PLAYBACK_FAILED) }
            }
            else -> Unit
        }
    }

    fun clear() {
        timer?.cancel()
        playback.stop()
        recorder.cancel()
        state.value.filePath?.let(::File)?.delete()
        state.value = VoiceUiState()
    }

    override fun onCleared() {
        clear()
    }
}
