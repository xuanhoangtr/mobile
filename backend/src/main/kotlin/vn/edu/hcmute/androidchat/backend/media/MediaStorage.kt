package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

interface MediaStorage {
    suspend fun write(source: ByteReadChannel, maxBytes: Long): StoredBlob
    fun open(key: String): InputStream
    fun delete(blob: StoredBlob)
}

/** Bounded local development storage. User filenames never become filesystem paths. */
class LocalMediaStorage(
    directory: Path,
    private val totalLimit: Long = 100L * 1024 * 1024,
    private val deleteStagingFile: (Path) -> Boolean = Files::deleteIfExists
) : MediaStorage {
    private val root: Path = Files.createDirectories(directory).toRealPath()
    private val used = AtomicLong(Files.list(root).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
    })

    private fun path(key: String): Path {
        require(Regex("[0-9a-f-]{36}\\.blob").matches(key)) { "Invalid internal storage key" }
        return root.resolve(key).also { require(it.parent == root) }
    }

    override suspend fun write(source: ByteReadChannel, maxBytes: Long): StoredBlob {
        var result: StoredBlob? = null
        try {
            return withContext(Dispatchers.IO) {
                val key = "${UUID.randomUUID()}.blob"
                val target = path(key)
                var written = 0L
                var reserved = 0L
                var created = false
                var complete = false
                var writeFailure: Throwable? = null
                try {
                    val stream = Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                    created = true
                    stream.use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.readAvailable(buffer, 0, buffer.size)
                            if (count == -1) break
                            if (count == 0) continue
                            if (written + count > maxBytes) tooLarge()
                            while (true) {
                                val current = used.get()
                                if (current + count > totalLimit) throw MediaFailure(
                                    HttpStatusCode.ServiceUnavailable, "MEDIA_CAPACITY", "Development storage capacity reached"
                                )
                                if (used.compareAndSet(current, current + count)) break
                            }
                            reserved += count
                            output.write(buffer, 0, count)
                            written += count
                        }
                    }
                    complete = true
                    StoredBlob(key, written).also { result = it }
                } catch (failure: Throwable) {
                    writeFailure = failure
                    throw failure
                } finally {
                    if (!complete && created) {
                        try {
                            deleteStagingFile(target)
                            // A failed or ineffective delete must keep the reservation. The startup
                            // scan below reconciles orphan bytes after a dev process restart.
                            if (Files.exists(target, NOFOLLOW_LINKS)) {
                                throw java.io.IOException("Staging file cleanup did not remove the file")
                            }
                            used.addAndGet(-reserved)
                        } catch (cleanupFailure: Throwable) {
                            val primaryFailure = writeFailure
                            if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure)
                            else throw cleanupFailure
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            // withContext can be cancelled while dispatching a completed result back to its caller.
            result?.let(::delete)
            throw failure
        }
    }

    override fun open(key: String): InputStream = Files.newInputStream(path(key), NOFOLLOW_LINKS)

    internal val accountedBytes: Long get() = used.get()

    override fun delete(blob: StoredBlob) {
        if (Files.deleteIfExists(path(blob.key))) used.addAndGet(-blob.size)
    }
}
