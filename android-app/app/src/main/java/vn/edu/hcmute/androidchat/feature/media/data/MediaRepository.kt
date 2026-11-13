package vn.edu.hcmute.androidchat.feature.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.feature.media.model.*
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

interface MediaSource {
    suspend fun inspect(uri: String): SelectedMedia
    fun open(media: SelectedMedia): InputStream
    fun release(media: SelectedMedia) {}
}

class FileMediaSource : MediaSource {
    override suspend fun inspect(uri: String): SelectedMedia {
        val file = java.io.File(java.net.URI(uri))
        if (!file.isFile || file.length() <= 0L) throw MediaException(MediaError.URI_ACCESS)
        return SelectedMedia(uri, file.name, "audio/mp4", file.length())
    }

    override fun open(media: SelectedMedia): InputStream =
        java.io.FileInputStream(java.io.File(java.net.URI(media.uri)))
}

interface MediaUploadService {
    /** Consume the stream incrementally. Implementations must cooperate with cancellation and close HTTP resources.
     * The repository owns [input] and verifies its EOF after this method returns; implementations must not close it.
     * The host injects the shared session/JWT; implementations must not invent user IDs or CHAT messages.
     */
    suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia

    suspend fun upload(
        media: SelectedMedia,
        input: InputStream,
        conversationId: Long?
    ): UploadedMedia = upload(media, input)
}

object UnconfiguredMediaUploadService : MediaUploadService {
    override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia =
        throw MediaException(MediaError.INTEGRATION_BLOCKED)
}

interface MediaRepository {
    suspend fun select(uri: String): SelectedMedia
    suspend fun upload(media: SelectedMedia, onBytesSent: (Long) -> Unit): UploadedMedia
    suspend fun upload(
        media: SelectedMedia,
        conversationId: Long?,
        onBytesSent: (Long) -> Unit
    ): UploadedMedia = upload(media, onBytesSent)
    fun release(media: SelectedMedia) {}
}

class StreamMediaRepository(
    private val source: MediaSource,
    private val service: MediaUploadService = UnconfiguredMediaUploadService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MediaRepository {
    override suspend fun select(uri: String): SelectedMedia {
        val media = source.inspect(uri)
        try { MediaSelectionPolicy.validate(media); return media }
        catch (failure: Exception) { source.release(media); throw failure }
    }

    override fun release(media: SelectedMedia) = source.release(media)

    override suspend fun upload(media: SelectedMedia, onBytesSent: (Long) -> Unit): UploadedMedia = withContext(ioDispatcher) {
        uploadInternal(media, null, onBytesSent)
    }

    override suspend fun upload(
        media: SelectedMedia,
        conversationId: Long?,
        onBytesSent: (Long) -> Unit
    ): UploadedMedia = withContext(ioDispatcher) {
        require(conversationId == null || conversationId > 0L) { "conversationId must be positive" }
        uploadInternal(media, conversationId, onBytesSent)
    }

    private suspend fun uploadInternal(
        media: SelectedMedia,
        conversationId: Long?,
        onBytesSent: (Long) -> Unit
    ): UploadedMedia {
        MediaSelectionPolicy.validate(media)
        val context = coroutineContext
        var consumed = 0L
        return source.open(media).use { original ->
            val counted = object : FilterInputStream(original) {
                private fun record(count: Int) {
                    context.ensureActive()
                    if (count > 0) {
                        consumed += count
                        if (consumed > MediaSelectionPolicy.MAX_BYTES) throw MediaException(MediaError.TOO_LARGE)
                        onBytesSent(consumed)
                    }
                }
                override fun read(): Int { context.ensureActive(); return `in`.read().also { record(if (it == -1) 0 else 1) } }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    context.ensureActive()
                    return `in`.read(buffer, offset, length).also(::record)
                }
                override fun close() = Unit // the repository owns and closes the underlying URI stream
            }
            onBytesSent(0)
            val uploaded = service.upload(media, counted, conversationId)
            if (consumed == 0L) throw MediaException(MediaError.EMPTY_FILE)
            if (media.byteSize != null && consumed != media.byteSize) {
                throw MediaException(MediaError.UPLOAD_FAILED)
            }
            if (uploaded.mediaId <= 0 || uploaded.byteSize != consumed || uploaded.mimeType != media.mimeType) {
                throw MediaException(MediaError.UPLOAD_FAILED)
            }
            // The adapter must not report success after sending only a prefix. For adapters that
            // complete at a known Content-Length without probing EOF, verify the source has no
            // additional byte; for unknown size this is the required proof that it reached EOF.
            context.ensureActive()
            if (original.read() != -1) throw MediaException(MediaError.UPLOAD_FAILED)
            context.ensureActive()
            uploaded
        }
    }
}
