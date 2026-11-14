package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.SecureRandom
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** Media metadata catalog with optional PostgreSQL persistence and in-memory dev fallback. */
class MediaService(
    val storage: MediaStorage,
    val maxBytes: Long = 10L * 1024 * 1024,
    private val maxObjects: Int = 1000,
    internal val uploadTimeoutMillis: Long = 60_000,
    private val dataSource: DataSource? = null,
    private val isConversationMember: suspend (conversationId: Long, userId: Long) -> Boolean = { _, _ -> false }
) {
    private data class Entry(val metadata: MediaObject, val blob: StoredBlob)

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val random = SecureRandom()
    private val publishMutex = Mutex()
    internal val uploadSlots = Semaphore(6)

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

    /** Publishes a staged media blob, persisting metadata to PostgreSQL when configured. */
    suspend fun publish(ownerId: Long, staged: Pair<StoredBlob, Pair<String, String>>, conversationId: Long? = null): MediaObject {
        if (conversationId != null && conversationId <= 0L) invalidMedia("A positive conversation ID is required")
        val (blob, fields) = staged
        val now = Instant.now()
        return publishMutex.withLock {
            if (dataSource == null && entries.size >= maxObjects) {
                throw MediaFailure(HttpStatusCode.ServiceUnavailable, "MEDIA_CAPACITY", "Development catalog capacity reached")
            }
            var id: Long
            do {
                id = random.nextLong() and Long.MAX_VALUE
            } while (id == 0L || entries.containsKey(id))

            val metadata = MediaObject(
                id = id,
                ownerId = ownerId,
                conversationId = conversationId,
                fileName = fields.first,
                mimeType = fields.second,
                byteSize = blob.size,
                createdAt = now.toString()
            )
            if (dataSource != null) {
                persistMetadata(metadata, blob.key, now)
            }
            if (dataSource == null || entries.size < maxObjects) {
                entries[id] = Entry(metadata, blob)
            }
            metadata
        }
    }

    /** Returns true if the user owns the media or is an active member of its conversation. */
    internal suspend fun authorized(meta: MediaObject, userId: Long): Boolean {
        if (meta.ownerId == userId) return true
        val convId = meta.conversationId ?: return false
        return isConversationMember(convId, userId)
    }

    suspend fun metadata(id: Long, userId: Long): MediaObject = authorizedEntry(id, userId).metadata

    suspend fun open(id: Long, userId: Long): InputStream {
        val entry = authorizedEntry(id, userId)
        if (entry.blob.key.isBlank()) {
            throw MediaFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Media is unavailable")
        }
        return storage.open(entry.blob.key)
    }

    private suspend fun authorizedEntry(id: Long, userId: Long): Entry {
        val entry = loadEntry(id)?.takeIf { authorized(it.metadata, userId) }
        return entry ?: throw MediaFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Media is unavailable")
    }

    private suspend fun persistMetadata(metadata: MediaObject, storageKey: String, createdAt: Instant) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO media_objects (
                        id, owner_id, conversation_id, file_name, mime_type, byte_size, upload_state, created_at, access_scope, storage_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setLong(1, metadata.id)
                    stmt.setLong(2, metadata.ownerId)
                    if (metadata.conversationId != null) {
                        stmt.setLong(3, metadata.conversationId)
                    } else {
                        stmt.setNull(3, Types.BIGINT)
                    }
                    stmt.setString(4, metadata.fileName)
                    stmt.setString(5, metadata.mimeType)
                    stmt.setLong(6, metadata.byteSize)
                    stmt.setString(7, metadata.uploadState)
                    stmt.setTimestamp(8, Timestamp.from(createdAt))
                    stmt.setString(9, metadata.accessScope)
                    stmt.setString(10, storageKey)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private suspend fun loadEntry(id: Long): Entry? {
        entries[id]?.let { return it }
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT id, owner_id, conversation_id, file_name, mime_type, byte_size, upload_state, created_at, access_scope, storage_key
                    FROM media_objects
                    WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setLong(1, id)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        val convId = rs.getLong("conversation_id").let { if (rs.wasNull()) null else it }
                        val meta = MediaObject(
                            id = rs.getLong("id"),
                            ownerId = rs.getLong("owner_id"),
                            conversationId = convId,
                            fileName = rs.getString("file_name"),
                            mimeType = rs.getString("mime_type"),
                            byteSize = rs.getLong("byte_size"),
                            uploadState = rs.getString("upload_state"),
                            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                            accessScope = rs.getString("access_scope")
                        )
                        val key = rs.getString("storage_key").orEmpty()
                        Entry(meta, StoredBlob(key, meta.byteSize))
                    }
                }
            }
        }
    }
}
