package vn.edu.hcmute.androidchat.feature.media.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import vn.edu.hcmute.androidchat.feature.media.data.MediaRepository
import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.MediaException
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia

@OptIn(ExperimentalCoroutinesApi::class)
class BatchMediaViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun duplicateUrisAreCollapsedAndInvalidSelectionsAreReported() = runTest(dispatcher) {
        val viewModel = BatchMediaViewModel(FakeRepository())
        viewModel.select(listOf("content://one", "content://one", "content://bad"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.items.size)
        assertEquals(MediaError.URI_ACCESS, viewModel.uiState.value.selectionErrors["content://bad"])
    }

    @Test
    fun batchUploadTracksEachItemIndependently() = runTest(dispatcher) {
        val viewModel = BatchMediaViewModel(FakeRepository())
        viewModel.select(listOf("content://one", "content://two"))
        advanceUntilIdle()
        viewModel.uploadAll(conversationId = 101)
        advanceUntilIdle()

        assertEquals(
            listOf(BatchItemPhase.UPLOADED, BatchItemPhase.UPLOADED),
            viewModel.uiState.value.items.map { it.phase }
        )
    }

    @Test
    fun batchUploadThrottlesToAtMostTwoConcurrentUploadsAndClearCancelsInFlight() = runTest(dispatcher) {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val cancelledCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val repo = object : MediaRepository {
            override suspend fun select(uri: String) =
                SelectedMedia(uri, uri.substringAfterLast('/'), "image/png", 4L)

            override suspend fun upload(media: SelectedMedia, onBytesSent: (Long) -> Unit) =
                upload(media, null, onBytesSent)

            override suspend fun upload(
                media: SelectedMedia,
                conversationId: Long?,
                onBytesSent: (Long) -> Unit
            ): UploadedMedia {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { prev -> maxOf(prev, now) }
                try {
                    gate.await()
                    return UploadedMedia(1L, media.mimeType, 4L, media.name)
                } catch (e: CancellationException) {
                    cancelledCount.incrementAndGet()
                    throw e
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val viewModel = BatchMediaViewModel(repo)
        viewModel.select(listOf("content://1", "content://2", "content://3", "content://4", "content://5"))
        advanceUntilIdle()

        viewModel.uploadAll(conversationId = 99L)
        runCurrent()

        assertEquals(2, active.get())
        assertEquals(2, maxActive.get())

        viewModel.clear()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.items.size)
        assertTrue(cancelledCount.get() >= 2)
    }

    private class FakeRepository : MediaRepository {
        override suspend fun select(uri: String): SelectedMedia {
            if (uri.endsWith("bad")) throw MediaException(MediaError.URI_ACCESS)
            return SelectedMedia(uri, uri.substringAfterLast('/'), "image/png", 4L)
        }

        override suspend fun upload(media: SelectedMedia, onBytesSent: (Long) -> Unit) =
            upload(media, null, onBytesSent)

        override suspend fun upload(
            media: SelectedMedia,
            conversationId: Long?,
            onBytesSent: (Long) -> Unit
        ): UploadedMedia {
            onBytesSent(4L)
            return UploadedMedia(media.uri.hashCode().toLong().coerceAtLeast(1L), media.mimeType, 4L)
        }
    }
}
