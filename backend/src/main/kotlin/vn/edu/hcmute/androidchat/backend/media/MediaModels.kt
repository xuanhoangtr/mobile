package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Provisional dev object. Conversation membership remains a CHAT-owned policy. */
@Serializable
data class MediaObject(
    val id: Long,
    val ownerId: Long,
    val conversationId: Long? = null,
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
    val uploadState: String = "READY",
    val createdAt: String,
    val accessScope: String = if (conversationId == null) "OWNER_ONLY_DEV" else "CONVERSATION_POLICY_REQUIRED"
)

class MediaFailure(val status: HttpStatusCode, val code: String, override val message: String) :
    RuntimeException(message)

internal fun invalidMedia(message: String): Nothing =
    throw MediaFailure(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message)

internal fun tooLarge(): Nothing =
    throw MediaFailure(HttpStatusCode.PayloadTooLarge, "MEDIA_TOO_LARGE", "File exceeds the development size limit")

data class StoredBlob(val key: String, val size: Long)
