package vn.edu.hcmute.androidchat.feature.media

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import vn.edu.hcmute.androidchat.feature.media.data.*
import vn.edu.hcmute.androidchat.feature.media.model.*
import vn.edu.hcmute.androidchat.feature.media.viewmodel.MediaViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MediaFoundationTest {
    private val dispatcher = StandardTestDispatcher()
    private val selected = SelectedMedia("content://test/1", "hello.txt", "text/plain", 5)
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun selectionCancellationAndAccessFailureHaveExplicitStates() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = MediaViewModel(repo)
        vm.pickerOpened(); vm.onPickerResult(null)
        assertEquals(MediaPhase.CANCELLED, vm.uiState.value.phase)
        vm.onPickerResult(selected.uri); advanceUntilIdle()
        assertEquals(selected, vm.uiState.value.selected)
        repo.selectionError = MediaException(MediaError.URI_ACCESS)
        vm.onPickerResult("content://revoked/1"); advanceUntilIdle()
        assertEquals(MediaError.URI_ACCESS, vm.uiState.value.error)
        assertNull(vm.uiState.value.selected)
    }

    @Test fun pickerLaunchFailureIsDistinctFromUserCancellation() = runTest(dispatcher) {
        val vm = MediaViewModel(FakeRepository())
        vm.pickerOpened()
        vm.pickerLaunchFailed()

        assertEquals(MediaPhase.ERROR, vm.uiState.value.phase)
        assertEquals(MediaError.PICKER_UNAVAILABLE, vm.uiState.value.error)
        assertNull(vm.uiState.value.selected)
    }

    @Test fun cancelStopsUploadAndRetryUsesSameSelection() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = MediaViewModel(repo)
        vm.onPickerResult(selected.uri); advanceUntilIdle()
        vm.upload(); runCurrent()
        assertEquals(MediaPhase.UPLOADING, vm.uiState.value.phase)
        assertEquals(2L, vm.uiState.value.bytesSent)
        vm.cancel(); advanceUntilIdle()
        assertTrue(repo.cancelled)
        assertEquals(MediaPhase.CANCELLED, vm.uiState.value.phase)
        repo.wait = false
        vm.upload(); advanceUntilIdle()
        assertEquals(MediaPhase.UPLOADED, vm.uiState.value.phase)
        assertEquals(2, repo.attempts)
        assertEquals(99L, vm.uiState.value.uploaded!!.mediaId)
        vm.upload(); advanceUntilIdle()
        assertEquals(2, repo.attempts)
    }

    @Test fun uploadFailureCanBeRetriedWithoutFakeSuccess() = runTest(dispatcher) {
        val repo = FakeRepository().apply { wait = false; uploadError = MediaException(MediaError.INTEGRATION_BLOCKED) }
        val vm = MediaViewModel(repo)
        vm.onPickerResult(selected.uri); advanceUntilIdle()
        vm.upload(); advanceUntilIdle()
        assertEquals(MediaPhase.ERROR, vm.uiState.value.phase)
        assertEquals(MediaError.INTEGRATION_BLOCKED, vm.uiState.value.error)
        assertNull(vm.uiState.value.uploaded)
        repo.uploadError = null
        vm.upload(); advanceUntilIdle()
        assertEquals(MediaPhase.UPLOADED, vm.uiState.value.phase)
    }

    @Test fun streamingCountsBytesAndReopensInputOnRetry() = runTest(dispatcher) {
        var opened = 0
        var closed = 0
        val source = object : MediaSource {
            override suspend fun inspect(uri: String) = selected
            override fun open(media: SelectedMedia) = object : ByteArrayInputStream("hello".toByteArray()) {
                init { opened++ }
                override fun close() { closed++; super.close() }
            }
        }
        val service = object : MediaUploadService {
            override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia {
                val buffer = ByteArray(2)
                var size = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; size += read }
                return UploadedMedia(1, media.mimeType, size)
            }
        }
        val repo = StreamMediaRepository(source, service, dispatcher)
        val progress = mutableListOf<Long>()
        repo.upload(selected) { progress += it }
        assertEquals(listOf(0L, 2L, 4L, 5L), progress)
        repo.upload(selected) {}
        assertEquals(2, opened); assertEquals(2, closed)
    }

    @Test fun repositoryRejectsPartialKnownAndUnknownStreamsButAcceptsEof() = runTest(dispatcher) {
        fun repository(bytes: ByteArray, reportedSize: Long?, consume: suspend (InputStream) -> UploadedMedia) =
            StreamMediaRepository(
                object : MediaSource {
                    override suspend fun inspect(uri: String) = selected.copy(byteSize = reportedSize)
                    override fun open(media: SelectedMedia) = ByteArrayInputStream(bytes)
                },
                object : MediaUploadService {
                    override suspend fun upload(media: SelectedMedia, input: InputStream) = consume(input)
                },
                dispatcher
            )

        val fiveBytes = "hello".toByteArray()
        val partialKnown = repository(fiveBytes, 5) { input ->
            assertEquals('h'.code, input.read())
            UploadedMedia(1, "text/plain", 1)
        }
        try { partialKnown.upload(selected) {}; fail("Known-size partial stream must fail") }
        catch (failure: MediaException) { assertEquals(MediaError.UPLOAD_FAILED, failure.reason) }

        val completeKnown = repository(fiveBytes, 5) { input ->
            val payload = ByteArray(5)
            assertEquals(5, input.read(payload)) // exact Content-Length read; repository then proves EOF
            input.close() // service close is isolated; repository retains ownership for the EOF check
            UploadedMedia(2, "text/plain", payload.size.toLong())
        }
        assertEquals(5L, completeKnown.upload(selected) {}.byteSize)

        val partialUnknown = repository(fiveBytes, null) { input ->
            assertEquals('h'.code, input.read())
            UploadedMedia(3, "text/plain", 1)
        }
        try { partialUnknown.upload(selected.copy(byteSize = null)) {}; fail("Unknown-size partial stream must fail") }
        catch (failure: MediaException) { assertEquals(MediaError.UPLOAD_FAILED, failure.reason) }

        val completeUnknown = repository(fiveBytes, null) { input ->
            var count = 0L
            val buffer = ByteArray(3)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                count += read
            }
            UploadedMedia(4, "text/plain", count)
        }
        assertEquals(5L, completeUnknown.upload(selected.copy(byteSize = null)) {}.byteSize)
    }

    @Test fun emptyInputIOExceptionAndServerSizeMismatchCannotReportSuccess() = runTest(dispatcher) {
        fun repository(source: InputStream, size: Long?, service: MediaUploadService) = StreamMediaRepository(
            object : MediaSource {
                override suspend fun inspect(uri: String) = selected.copy(byteSize = size)
                override fun open(media: SelectedMedia) = source
            }, service, dispatcher
        )

        val drains = object : MediaUploadService {
            override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia {
                val bytes = input.readBytes()
                return UploadedMedia(1, media.mimeType, bytes.size.toLong())
            }
        }
        try {
            repository(ByteArrayInputStream(byteArrayOf()), null, drains).upload(selected.copy(byteSize = null)) {}
            fail("Empty unknown-size stream must fail")
        } catch (failure: MediaException) { assertEquals(MediaError.EMPTY_FILE, failure.reason) }

        val throwsAfterTwo = object : InputStream() {
            private var reads = 0
            var closed = false
            override fun read(): Int {
                if (reads == 2) throw IOException("source failed")
                reads++
                return 'a'.code
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads == 2) throw IOException("source failed")
                val count = minOf(length, 2 - reads)
                buffer.fill('a'.code.toByte(), offset, offset + count)
                reads += count
                return count
            }
            override fun close() { closed = true }
        }
        try {
            repository(throwsAfterTwo, 5, drains).upload(selected) {}
            fail("IOException must propagate")
        } catch (_: IOException) { assertTrue(throwsAfterTwo.closed) }

        val wrongResponse = object : MediaUploadService {
            override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia {
                input.readBytes()
                return UploadedMedia(1, media.mimeType, 4)
            }
        }
        try {
            repository(ByteArrayInputStream("hello".toByteArray()), 5, wrongResponse).upload(selected) {}
            fail("Mismatched server byteSize must fail")
        } catch (failure: MediaException) { assertEquals(MediaError.UPLOAD_FAILED, failure.reason) }
    }

    @Test fun policyRejectsWrongMimeKnownSizeAndEmptyFile() = runTest(dispatcher) {
        for ((media, reason) in listOf(
            selected.copy(mimeType = "text/html") to MediaError.UNSUPPORTED_TYPE,
            selected.copy(mimeType = "image/heic") to MediaError.UNSUPPORTED_TYPE,
            selected.copy(mimeType = "image/webp") to MediaError.UNSUPPORTED_TYPE,
            selected.copy(mimeType = "video/3gpp") to MediaError.UNSUPPORTED_TYPE,
            selected.copy(byteSize = MediaSelectionPolicy.MAX_BYTES + 1) to MediaError.TOO_LARGE,
            selected.copy(byteSize = 0) to MediaError.EMPTY_FILE
        )) {
            try { MediaSelectionPolicy.validate(media); fail("Expected validation failure") }
            catch (failure: MediaException) { assertEquals(reason, failure.reason) }
        }
    }

    @Test fun unknownSizeStillHasStreamingLimitAndClosesOnFailure() = runTest(dispatcher) {
        var closed = false
        val source = object : MediaSource {
            override suspend fun inspect(uri: String) = selected.copy(byteSize = null)
            override fun open(media: SelectedMedia) = object : InputStream() {
                override fun read(): Int = 65
                override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(65, off, off + len); return len }
                override fun close() { closed = true }
            }
        }
        val consumer = object : MediaUploadService {
            override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia {
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) { /* Deliberately unbounded source must be stopped by repository. */ }
                error("unreachable")
            }
        }
        try { StreamMediaRepository(source, consumer, dispatcher).upload(selected.copy(byteSize = null)) {}; fail("Expected size limit") }
        catch (failure: MediaException) { assertEquals(MediaError.TOO_LARGE, failure.reason) }
        assertTrue(closed)
    }

    @Test fun repositoryClosesStreamOnCancellationAndReleasesInvalidSelection() = runTest(dispatcher) {
        var closed = false
        var released = false
        val source = object : MediaSource {
            override suspend fun inspect(uri: String) = selected.copy(mimeType = "text/html")
            override fun open(media: SelectedMedia) = object : ByteArrayInputStream("hello".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
            override fun release(media: SelectedMedia) { released = true }
        }
        val service = object : MediaUploadService {
            override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia = awaitCancellation()
        }
        val repo = StreamMediaRepository(source, service, dispatcher)
        try { repo.select(selected.uri); fail("Expected wrong MIME") }
        catch (_: MediaException) { assertTrue(released) }
        val upload = launch { repo.upload(selected) {} }
        runCurrent()
        upload.cancelAndJoin()
        assertTrue(closed)
    }

    @Test fun uploadedMediaDefaultsFileNameAndHttpServiceParsesFileName() = runTest(dispatcher) {
        val defaultUploaded = UploadedMedia(10L, "image/png", 123L)
        assertEquals("", defaultUploaded.fileName)

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"id":77,"mimeType":"text/plain","byteSize":5,"fileName":"server_hello.txt"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val service = HttpMediaUploadService(
                endpoint = java.net.URL("http://127.0.0.1:${server.address.port}/upload"),
                accessToken = { "test-token" }
            )
            val result = withContext(Dispatchers.IO) {
                service.upload(selected, ByteArrayInputStream("hello".toByteArray()), conversationId = 12L)
            }
            assertEquals(77L, result.mediaId)
            assertEquals("text/plain", result.mimeType)
            assertEquals(5L, result.byteSize)
            assertEquals("server_hello.txt", result.fileName)
        } finally {
            server.stop(0)
        }
    }

    private inner class FakeRepository : MediaRepository {
        var selectionError: Exception? = null
        var uploadError: Exception? = null
        var wait = true
        var cancelled = false
        var attempts = 0
        override suspend fun select(uri: String): SelectedMedia { selectionError?.let { throw it }; return selected }
        override suspend fun upload(media: SelectedMedia, onBytesSent: (Long) -> Unit): UploadedMedia {
            attempts++
            uploadError?.let { throw it }
            onBytesSent(2)
            try { if (wait) awaitCancellation() }
            finally { if (wait) cancelled = true }
            onBytesSent(5)
            return UploadedMedia(99, media.mimeType, 5)
        }
    }
}
