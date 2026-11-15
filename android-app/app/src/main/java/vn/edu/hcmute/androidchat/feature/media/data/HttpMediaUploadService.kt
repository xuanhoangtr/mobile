package vn.edu.hcmute.androidchat.feature.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.media.model.MediaException
import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class HttpMediaUploadService(
    private val endpoint: URL,
    private val accessToken: () -> String
) : MediaUploadService {
    override suspend fun upload(media: SelectedMedia, input: InputStream): UploadedMedia =
        upload(media, input, null)

    override suspend fun upload(
        media: SelectedMedia,
        input: InputStream,
        conversationId: Long?
    ): UploadedMedia = withContext(Dispatchers.IO) {
        val boundary = "Media${UUID.randomUUID().toString().replace("-", "")}"
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${accessToken()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { output ->
                if (conversationId != null) {
                    writePart(output, boundary, "conversationId", null, "text/plain", conversationId.toString())
                }
                writePartHeader(output, boundary, "file", media.name, media.mimeType)
                input.copyTo(output)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) throw MediaException(MediaError.UPLOAD_FAILED)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(response)
            val parsedName = if (json.has("fileName") && !json.isNull("fileName")) {
                json.optString("fileName", media.name).ifBlank { media.name }
            } else {
                media.name
            }
            UploadedMedia(
                mediaId = json.getLong("id"),
                mimeType = json.getString("mimeType"),
                byteSize = json.getLong("byteSize"),
                fileName = parsedName
            )
        } catch (failure: MediaException) {
            throw failure
        } catch (_: Exception) {
            throw MediaException(MediaError.UPLOAD_FAILED)
        } finally {
            connection.disconnect()
        }
    }

    private fun writePart(
        output: java.io.OutputStream,
        boundary: String,
        name: String,
        filename: String?,
        mimeType: String,
        value: String
    ) {
        writePartHeader(output, boundary, name, filename, mimeType)
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writePartHeader(
        output: java.io.OutputStream,
        boundary: String,
        name: String,
        filename: String?,
        mimeType: String
    ) {
        val file = filename?.let { "; filename=\"${it.replace("\"", "")}\"" } ?: ""
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"$file\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
    }
}
