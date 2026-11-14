package vn.edu.hcmute.androidchat.feature.media.batch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
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
