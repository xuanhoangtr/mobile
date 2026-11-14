package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-memory dev catalog: READY means stored/validated, not a CHAT message delivery receipt. */
class MediaService(
    val storage: MediaStorage,
    val maxBytes: Long = 10L * 1024 * 1024,
    private val maxObjects: Int = 1000,
    internal val uploadTimeoutMillis: Long = 60_000
) {
    private data class Entry(val metadata: MediaObject, val blob: StoredBlob)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val random = SecureRandom()
    internal val uploadSlots = Semaphore(2)

    suspend fun stage(source: ByteReadChannel, rawName: String?, declaredMime: String?): Pair<StoredBlob, Pair<String, String>> {
        val name = MediaValidation.fileName(rawName)
        val mime = MediaValidation.declaredType(declaredMime, name)
        val blob = storage.write(source, maxBytes)
        try {
            withContext(Dispatchers.IO) { MediaValidation.verify(storage.open(blob.key), mime) }
            return blob to (name to mime)
        } catch (failure: Throwable) {
            storage.delete(blob)
            throw failure
        }
    }

    @Synchronized
    fun publish(ownerId: Long, staged: Pair<StoredBlob, Pair<String, String>>, conversationId: Long? = null): MediaObject {
        if (conversationId != null && conversationId <= 0L) invalidMedia("A positive conversation ID is required")
        if (entries.size >= maxObjects) throw MediaFailure(HttpStatusCode.ServiceUnavailable, "MEDIA_CAPACITY", "Development catalog capacity reached")
        var id: Long
        do { id = random.nextLong() and Long.MAX_VALUE } while (id == 0L || entries.containsKey(id))
        val (blob, fields) = staged
        val metadata = MediaObject(
            id = id,
            ownerId = ownerId,
            conversationId = conversationId,
            fileName = fields.first,
            mimeType = fields.second,
            byteSize = blob.size,
            createdAt = Instant.now().toString()
        )
        entries[id] = Entry(metadata, blob)
        return metadata
    }

    fun metadata(id: Long, userId: Long): MediaObject = authorized(id, userId).metadata
    fun open(id: Long, userId: Long): InputStream = storage.open(authorized(id, userId).blob.key)

    private fun authorized(id: Long, userId: Long): Entry {
        val entry = entries[id]?.takeIf { it.metadata.ownerId == userId }
        return entry ?: throw MediaFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Media is unavailable")
    }
}
