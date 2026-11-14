package vn.edu.hcmute.androidchat.feature.media.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.feature.media.model.*
import java.io.InputStream

/** Uses content:// grants. Never resolves a content URI to an absolute filesystem path. */
class AndroidMediaSource(private val resolver: ContentResolver) : MediaSource {
    private val acquiredGrants = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override suspend fun inspect(uri: String): SelectedMedia {
        try {
            return withContext(Dispatchers.IO) {
                val parsed = Uri.parse(uri)
                if (parsed.scheme != "content") throw MediaException(MediaError.URI_ACCESS)
                var name = "document"
                var size: Long? = null
                resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex).take(180)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    }
                }
                // Some providers grant only temporary access. Keep the selection usable but report the distinction.
                val wasPersisted = resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }
                try {
                    resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (!wasPersisted) acquiredGrants.add(uri)
                }
                catch (_: SecurityException) { /* Temporary read grant may still be valid. */ }
                val persisted = resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }
                resolver.openInputStream(parsed)?.use { /* Verify access without loading the file into memory. */ }
                    ?: throw MediaException(MediaError.URI_ACCESS)
                SelectedMedia(uri, name, resolver.getType(parsed)?.lowercase() ?: "application/octet-stream", size, persisted)
            }
        } catch (failure: Throwable) {
            releaseUri(uri)
            throw failure
        }
    }

    override fun open(media: SelectedMedia): InputStream = resolver.openInputStream(Uri.parse(media.uri))
        ?: throw MediaException(MediaError.URI_ACCESS)

    override fun release(media: SelectedMedia) {
        releaseUri(media.uri)
    }

    private fun releaseUri(uri: String) {
        // Only release grants acquired by this source; never revoke another feature's pre-existing grant.
        if (acquiredGrants.remove(uri)) {
            try { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Already revoked by provider. */ }
        }
    }
}
