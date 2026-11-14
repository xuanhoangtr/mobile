package vn.edu.hcmute.androidchat.backend.media

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MediaStorageTest {
    @Test fun cancellationDeletesPartialBlobAndRestoresQuota() = runBlocking {
        val root = Files.createTempDirectory("media-cancel-test-")
        try {
            val storage = LocalMediaStorage(root, totalLimit = 7)
            val input = ByteChannel(autoFlush = true)
            val upload = launch(Dispatchers.Default) { storage.write(input, 100) }
            input.writeFully("partial".toByteArray())
            withTimeout(5000) {
                while (Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() } != 7L) delay(10)
            }
            upload.cancelAndJoin()
            input.cancel()
            assertEquals(0L, Files.list(root).use { it.count() })
            val next = storage.write(ByteReadChannel("success".toByteArray()), 100)
            assertEquals(7L, next.size)
            storage.delete(next)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun failedStagingDeleteKeepsQuotaAndStartupScanReconcilesOrphan() = runBlocking {
        val root = Files.createTempDirectory("media-delete-failure-test-")
        try {
            supervisorScope {
                val deletes = AtomicInteger()
                val storage = LocalMediaStorage(root, totalLimit = 7) { path ->
                    if (deletes.getAndIncrement() == 0) throw AccessDeniedException(path.toString())
                    Files.deleteIfExists(path)
                }
                val input = ByteChannel(autoFlush = true)
                val upload = async(Dispatchers.IO) { storage.write(input, maxBytes = 7) }
                input.writeFully("1234567".toByteArray())
                withTimeout(5_000) {
                    while (Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() } != 7L) delay(10)
                }
                input.writeByte('8'.code.toByte())
                input.close()
                assertFailsWith<MediaFailure> { upload.await() }

                val diskBytesAfterFailedDelete = Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() }
                assertEquals(7L, diskBytesAfterFailedDelete)
                assertEquals(7L, storage.accountedBytes)

                assertFailsWith<MediaFailure> { storage.write(ByteReadChannel(byteArrayOf(1)), maxBytes = 1) }
                assertEquals(7L, Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() })
                assertEquals(7L, storage.accountedBytes)

                // Dev storage reconstructs its conservative quota from files that survived a restart.
                val restarted = LocalMediaStorage(root, totalLimit = 7)
                assertEquals(7L, restarted.accountedBytes)
                assertFailsWith<MediaFailure> { restarted.write(ByteReadChannel(byteArrayOf(2)), maxBytes = 1) }
                assertEquals(7L, Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() })
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun concurrentWritesCannotReserveMoreThanQuota() = runBlocking {
        val root = Files.createTempDirectory("media-concurrent-quota-test-")
        try {
            val storage = LocalMediaStorage(root, totalLimit = 7)
            val results = listOf("1234", "5678").map { text ->
                async(Dispatchers.IO) { runCatching { storage.write(ByteReadChannel(text.toByteArray()), maxBytes = 8) } }
            }.awaitAll()
            val stored = results.mapNotNull { it.getOrNull() }
            assertEquals(1, stored.size)
            assertEquals(4L, stored.single().size)
            assertTrue(storage.accountedBytes <= 7L)
            assertEquals(4L, Files.list(root).use { paths -> paths.mapToLong { Files.size(it) }.sum() })
            storage.delete(stored.single())
            assertEquals(0L, storage.accountedBytes)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun storageRejectsExternalPathsAndCatalogCapacityDoesNotGrantAccess() = runBlocking {
        val root = Files.createTempDirectory("media-key-test-")
        try {
            val storage = LocalMediaStorage(root)
            assertFailsWith<IllegalArgumentException> { storage.open("../secrets.txt") }
            val service = MediaService(storage, maxObjects = 1)
            val staged = service.stage(ByteReadChannel("one".toByteArray()), "one.txt", "text/plain")
            val objectOne = service.publish(1, staged)
            assertFailsWith<MediaFailure> { service.metadata(objectOne.id, 2) }
            val second = service.stage(ByteReadChannel("two".toByteArray()), "two.txt", "text/plain")
            assertFailsWith<MediaFailure> { service.publish(1, second) }
            storage.delete(second.first)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun supportedSignaturesAndExtensionMismatchAreChecked() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0)
        MediaValidation.verify(png.inputStream(), "image/png")
        MediaValidation.verify(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 0).inputStream(), "image/jpeg")
        MediaValidation.verify("%PDF-1.4".byteInputStream(), "application/pdf")
        MediaValidation.verify(byteArrayOf(0, 0, 0, 16).plus("ftypisom".toByteArray()).inputStream(), "video/mp4")
        assertFailsWith<MediaFailure> { MediaValidation.declaredType("image/png", "wrong.jpg") }
        assertFailsWith<MediaFailure> { MediaValidation.verify("plain".byteInputStream(), "video/mp4") }
    }
}
