package vn.edu.hcmute.androidchat.feature.media.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMessageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var directory: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = createTempDir(prefix = "voice-test-")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test
    fun recordingProducesPreviewableDraftAndPlaybackReturnsToRecorded() = runTest(dispatcher) {
        val playback = FakePlayback()
        val viewModel = VoiceMessageViewModel(
            recorder = FakeRecorder(),
            playback = playback,
            outputDirectory = directory,
            nowMs = { testScheduler.currentTime }
        )

        viewModel.startRecording()
        advanceTimeBy(800)
        assertEquals(VoicePhase.RECORDING, viewModel.uiState.value.phase)
        viewModel.stopRecording()

        assertEquals(VoicePhase.RECORDED, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.durationMs > 0)
        viewModel.togglePlayback()
        assertEquals(VoicePhase.PLAYING, viewModel.uiState.value.phase)
        playback.complete()
        assertEquals(VoicePhase.RECORDED, viewModel.uiState.value.phase)
    }

    @Test
    fun clearStopsPlaybackAndDeletesDraft() = runTest(dispatcher) {
        val playback = FakePlayback()
        val viewModel = VoiceMessageViewModel(
            recorder = FakeRecorder(),
            playback = playback,
            outputDirectory = directory
        )

        viewModel.startRecording()
        viewModel.stopRecording()
        val path = viewModel.uiState.value.filePath!!
        assertTrue(File(path).exists())
        viewModel.togglePlayback()
        viewModel.clear()

        assertTrue(playback.stopped)
        assertEquals(VoiceUiState(), viewModel.uiState.value)
        assertTrue(!File(path).exists())
    }

    @Test
    fun playbackSupportsPauseResumeAndInterruption() = runTest(dispatcher) {
        val playback = FakePlayback()
        val viewModel = VoiceMessageViewModel(FakeRecorder(), playback, directory)
        viewModel.startRecording()
        advanceTimeBy(500)
        viewModel.stopRecording()
        viewModel.togglePlayback()
        viewModel.togglePlayback()
        assertEquals(VoicePhase.PAUSED, viewModel.uiState.value.phase)
        assertEquals(1, playback.pauseCalls)
        viewModel.togglePlayback()
        assertEquals(VoicePhase.PLAYING, viewModel.uiState.value.phase)
        assertEquals(1, playback.resumeCalls)
        viewModel.interrupt()
        assertEquals(VoicePhase.PAUSED, viewModel.uiState.value.phase)
    }

    @Test
    fun uploadProducesVoiceAttachmentWithDuration() = runTest(dispatcher) {
        val viewModel = VoiceMessageViewModel(
            recorder = FakeRecorder(),
            playback = FakePlayback(),
            outputDirectory = directory,
            mediaRepository = FakeMediaRepository()
        )
        viewModel.startRecording()
        advanceTimeBy(400)
        viewModel.stopRecording()
        viewModel.upload(conversationId = 101)
        advanceUntilIdle()

        assertEquals(VoicePhase.SENT, viewModel.uiState.value.phase)
        assertEquals("VOICE", viewModel.uiState.value.attachment?.messageType)
        assertEquals(42L, viewModel.uiState.value.attachment?.mediaId)
    }

    private class FakeRecorder : VoiceRecorder {
        private var output: File? = null

        override fun start(output: File) {
            this.output = output
            output.parentFile?.mkdirs()
        }

        override fun stop(): Long {
            val target = output ?: error("not recording")
            target.writeBytes(byteArrayOf(1, 2, 3))
            output = null
            return target.length()
        }

        override fun cancel() {
            output?.delete()
            output = null
        }
    }

    private class FakePlayback : VoicePlayback {
        private var completed: (() -> Unit)? = null
        var stopped = false
        var pauseCalls = 0
        var resumeCalls = 0

        override fun play(path: String, onCompleted: () -> Unit) {
            completed = onCompleted
            stopped = false
        }

        override fun stop() {
            stopped = true
            completed = null
        }

        override fun pause() {
            pauseCalls++
        }

        override fun resume() {
            resumeCalls++
        }

        fun complete() {
            completed?.invoke()
            completed = null
        }
    }

    private class FakeMediaRepository : MediaRepository {
        override suspend fun select(uri: String): SelectedMedia = error("unused")

        override suspend fun upload(
            media: SelectedMedia,
            onBytesSent: (Long) -> Unit
        ): UploadedMedia = upload(media, null, onBytesSent)

        override suspend fun upload(
            media: SelectedMedia,
            conversationId: Long?,
            onBytesSent: (Long) -> Unit
        ): UploadedMedia {
            onBytesSent(media.byteSize ?: 0L)
            return UploadedMedia(42L, media.mimeType, media.byteSize ?: 1L)
        }
    }
}
