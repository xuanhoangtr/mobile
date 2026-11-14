package vn.edu.hcmute.androidchat.backend.media

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import vn.edu.hcmute.androidchat.backend.common.*
import java.io.IOException
import java.nio.file.Path

private const val MAX_MULTIPART_OVERHEAD_BYTES = 64L * 1024

private class RequestBodyLimitExceeded : IOException("Media request body limit exceeded")

private class MultipartClosingBoundaryDetector(boundary: String?) {
    private val closingLine = boundary?.takeIf { candidate ->
        candidate.isNotEmpty() && candidate.length <= 70 && candidate.all { it.code in 0x21..0x7e }
    }?.let { "\r\n--$it--\r\n".toByteArray(Charsets.US_ASCII) }
    private val prefix = IntArray(closingLine?.size ?: 0)
    private var matched = 0
    var found: Boolean = false
        private set
    var hasEpilogue: Boolean = false
        private set

    init {
        closingLine?.let { pattern ->
            var length = 0
            for (index in 1 until pattern.size) {
                while (length > 0 && pattern[index] != pattern[length]) length = prefix[length - 1]
                if (pattern[index] == pattern[length]) length++
                prefix[index] = length
            }
        }
    }

    /** Returns the number of bytes safe to pass to Ktor's parser; epilogue is counted but discarded. */
    fun consume(bytes: ByteArray, count: Int): Int {
        if (found) {
            if (count > 0) hasEpilogue = true
            return 0
        }
        val pattern = closingLine ?: return count
        for (index in 0 until count) {
            val byte = bytes[index]
            while (matched > 0 && byte != pattern[matched]) matched = prefix[matched - 1]
            if (byte == pattern[matched]) matched++
            if (matched == pattern.size) {
                found = true
                if (index + 1 < count) hasEpilogue = true
                return index + 1
            }
        }
        return count
    }

    /** Multipart permits EOF immediately after the closing delimiter, without the trailing CRLF. */
    fun isCompleteAtEof(): Boolean = found || (closingLine != null && matched == closingLine.size - 2)
}

/**
 * Counts bytes as they leave the engine request channel, before Ktor's multipart parser.
 * The bridge has bounded channel buffering and stops the source as soon as limit + 1 is read.
 */
private class LimitedRequestBody(
    private val source: ByteReadChannel,
    private val output: ByteChannel,
    private val pump: Job,
    private val exceeded: AtomicBoolean,
    private val closingBoundary: MultipartClosingBoundaryDetector
) {
    val wasExceeded: Boolean get() = exceeded.get()
    val hasClosingBoundary: Boolean get() = closingBoundary.isCompleteAtEof()
    val hasUnexpectedEpilogue: Boolean get() = closingBoundary.hasEpilogue

    suspend fun awaitWithinLimit() {
        pump.join()
        if (exceeded.get()) throw RequestBodyLimitExceeded()
        source.closedCause?.let { throw it }
    }

    fun abort() {
        val cause = CancellationException("Media request body was rejected")
        source.cancel(cause)
        pump.cancel(cause)
        output.cancel(cause)
    }

    fun releaseSlotWhenStopped(release: () -> Unit) {
        val released = AtomicBoolean(false)
        val releaseOnce = { if (released.compareAndSet(false, true)) release() }
        if (pump.isCompleted) releaseOnce() else pump.invokeOnCompletion { releaseOnce() }
    }
}

@OptIn(InternalAPI::class)
private fun ApplicationCall.installMediaRequestBodyLimit(limit: Long, boundary: String?): LimitedRequestBody {
    val routingCall = this as? RoutingCall
        ?: throw IllegalStateException("Media upload must run inside a Ktor route")
    val pipelineRequest = routingCall.pipelineCall.request as PipelineRequest
    val source = pipelineRequest.receiveChannel()
    val output = ByteChannel(autoFlush = true)
    val exceeded = AtomicBoolean(false)
    val closingBoundary = MultipartClosingBoundaryDetector(boundary)
    val pump = launch {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        try {
            while (true) {
                val remainingIncludingProbe = (limit - total + 1).coerceAtMost(buffer.size.toLong()).toInt()
                val count = source.readAvailable(buffer, 0, remainingIncludingProbe)
                if (count == -1) {
                    output.close()
                    return@launch
                }
                if (count == 0) continue
                if (total > limit - count) {
                    exceeded.set(true)
                    val cause = RequestBodyLimitExceeded()
                    source.cancel(cause)
                    output.cancel(cause)
                    return@launch
                }
                total += count
                val parserBytes = closingBoundary.consume(buffer, count)
                if (parserBytes > 0) output.writeFully(buffer, 0, parserBytes)
            }
        } catch (cancelled: CancellationException) {
            output.cancel(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            output.cancel(failure)
        }
    }
    pipelineRequest.setReceiveChannel(output)
    return LimitedRequestBody(source, output, pump, exceeded, closingBoundary)
}

/** Explicit opt-in media/push/call foundation; preserves shared JWT/session and CHAT contracts. */
fun Application.configureMediaFoundation(
    config: AppConfig,
    service: MediaService? = null,
    pushTokenRegistry: PushTokenRegistry? = null,
    callSessionRegistry: CallSessionRegistry? = null,
    dataSource: DataSource? = null,
    isConversationMember: (suspend (conversationId: Long, userId: Long) -> Boolean)? = null,
    onCallEvent: suspend (targetUserIds: Set<Long>, eventType: String, payload: JsonElement) -> Unit = { _, _, _ -> }
) {
    val mediaMemberChecker: suspend (Long, Long) -> Boolean = isConversationMember ?: { _, _ -> false }
    val resolvedService = service ?: mediaFromEnv(config, dataSource, mediaMemberChecker) ?: return
    val resolvedPushRegistry = pushTokenRegistry ?: PushTokenRegistry(dataSource)
    val resolvedCallRegistry = callSessionRegistry ?: CallSessionRegistry(
        dataSource = dataSource,
        isConversationMember = isConversationMember,
        onCallEvent = onCallEvent
    )
    routing {
        registerMediaRoutes(resolvedService)
        registerPushTokenRoutes(resolvedPushRegistry)
        registerCallRoutes(resolvedCallRegistry)
    }
}

private fun mediaFromEnv(
    config: AppConfig,
    dataSource: DataSource?,
    isConversationMember: suspend (conversationId: Long, userId: Long) -> Boolean
): MediaService? {
    val enabled = System.getenv("MEDIA_ENABLED") == "true" ||
        System.getenv("MEDIA_DEV_ENABLED") == "true"
    if (!enabled) return null
    val storageDir = System.getenv("MEDIA_STORAGE_DIR")?.takeIf { it.isNotBlank() } ?: "storage/media-dev"
    return MediaService(
        storage = LocalMediaStorage(Path.of(storageDir)),
        dataSource = dataSource,
        isConversationMember = isConversationMember
    )
}

fun Route.registerMediaRoutes(service: MediaService) {
    authenticate(AUTH_JWT_NAME) {
        post("/media/upload") {
            call.mediaResponse {
                val owner = call.principal<JWTPrincipal>()!!.authenticatedUserId()!!
                if (!service.uploadSlots.tryAcquire()) throw MediaFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many concurrent uploads")
                var staged: Pair<StoredBlob, Pair<String, String>>? = null
                var conversationId: Long? = null
                var published = false
                var requestBodyFullyRead = false
                var requestBody: LimitedRequestBody? = null
                try {
                    try {
                        withTimeout(service.uploadTimeoutMillis) {
                        val maxRequestBytes = Math.addExact(service.maxBytes, MAX_MULTIPART_OVERHEAD_BYTES)
                        val requestContentType = call.request.contentType()
                        val isMultipart = requestContentType.match(ContentType.MultiPart.FormData)
                        val boundary = requestContentType.parameter("boundary")
                        requestBody = call.installMediaRequestBodyLimit(maxRequestBytes, boundary)
                        if (!isMultipart) invalidMedia("Expected multipart/form-data")
                        if (boundary.isNullOrBlank() || boundary.length > 70 || boundary.any { it.code !in 0x21..0x7e }) {
                            invalidMedia("A supported ASCII multipart boundary is required")
                        }
                        call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let {
                            if (it < 0 || it > maxRequestBytes) tooLarge()
                        }
                        // This parser limit handles individual form fields; the receive-channel bridge
                        // above is authoritative for headers, all parts, preamble, and epilogue together.
                        // Ktor applies this per-part; keep it at the global ceiling so files up to
                        // service.maxBytes remain streamable. The pre-parser bridge caps the whole body.
                        val multipart = call.receiveMultipart(formFieldLimit = maxRequestBytes)
                        var filePartSeen = false
                        while (true) {
                            val part = multipart.readPart() ?: break
                            if (part is PartData.FormItem && part.name == "conversationId" && conversationId == null) {
                                conversationId = part.value.toLongOrNull()?.takeIf { it > 0L }
                                    ?: invalidMedia("A positive conversation ID is required")
                                part.dispose()
                                continue
                            }
                            if (part !is PartData.FileItem || part.name != "file" || filePartSeen) {
                                // Do not dispose an unconsumed part: Ktor's dispose drains synchronously.
                                invalidMedia("Exactly one file part is supported; additional fields are not accepted")
                            }
                            filePartSeen = true
                            // On validation failure, leave cleanup to abort() below instead of draining.
                            staged = service.stage(part.provider(), part.originalFileName, part.contentType?.toString())
                            // service.stage returns only after EOF, so this release cannot drain an attacker body.
                            part.dispose()
                        }
                        requestBody!!.awaitWithinLimit()
                        if (!requestBody!!.hasClosingBoundary) invalidMedia("Multipart closing boundary is required")
                        if (requestBody!!.hasUnexpectedEpilogue) invalidMedia("Multipart epilogue is not supported")
                        requestBodyFullyRead = true
                        }
                    } catch (failure: Throwable) {
                        if (requestBody?.wasExceeded == true) throw RequestBodyLimitExceeded()
                        throw failure
                    }
                    val uploaded = staged ?: invalidMedia("A file part is required")
                    val metadata = service.publish(owner, uploaded, conversationId)
                    published = true
                    call.respond(HttpStatusCode.Created, metadata)
                } finally {
                    try {
                        if (!published) staged?.let { service.storage.delete(it.first) }
                    } finally {
                        val body = requestBody
                        if (body == null) service.uploadSlots.release()
                        else {
                            if (!requestBodyFullyRead) body.abort()
                            body.releaseSlotWhenStopped { service.uploadSlots.release() }
                        }
                    }
                }
            }
        }
        get("/media/{id}") {
            call.mediaResponse { call.respond(service.metadata(call.mediaId(), call.mediaOwner())) }
        }
        get("/media/{id}/download") {
            call.mediaResponse {
                val id = call.mediaId()
                val owner = call.mediaOwner()
                val metadata = service.metadata(id, owner)
                // Never inline user-controlled HTML/text; no filesystem key is exposed.
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"media-$id\"")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                // Probe before committing headers; the response producer owns its own stream lifetime.
                withContext(Dispatchers.IO) { service.open(id, owner).close() }
                call.respondOutputStream(ContentType.parse(metadata.mimeType), HttpStatusCode.OK, metadata.byteSize) {
                    val output = this
                    withContext(Dispatchers.IO) { service.open(id, owner).use { it.copyTo(output) } }
                }
            }
        }
    }
}

private fun ApplicationCall.mediaId(): Long = parameters["id"]?.toLongOrNull()?.takeIf { it > 0 }
    ?: invalidMedia("A positive media ID is required")
private fun ApplicationCall.mediaOwner(): Long = principal<JWTPrincipal>()!!.authenticatedUserId()!!

private suspend fun ApplicationCall.mediaResponse(block: suspend () -> Unit) {
    try { block() }
    catch (_: TimeoutCancellationException) { respond(HttpStatusCode.RequestTimeout, ApiErrorResponse(ApiErrorDetail("UPLOAD_TIMEOUT", "Upload timed out"))) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RequestBodyLimitExceeded) { respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse(ApiErrorDetail("MEDIA_TOO_LARGE", "Media request body is too large"))) }
    catch (failure: MediaFailure) { respond(failure.status, ApiErrorResponse(ApiErrorDetail(failure.code, failure.message))) }
    catch (_: java.nio.charset.CharacterCodingException) { respond(HttpStatusCode.BadRequest, ApiErrorResponse(ApiErrorDetail("VALIDATION_ERROR", "Invalid UTF-8 text"))) }
    catch (_: io.ktor.server.plugins.BadRequestException) { respond(HttpStatusCode.BadRequest, ApiErrorResponse(ApiErrorDetail("VALIDATION_ERROR", "Invalid multipart request"))) }
    catch (_: IOException) { respond(HttpStatusCode.ServiceUnavailable, ApiErrorResponse(ApiErrorDetail("STORAGE_UNAVAILABLE", "Media storage or upload stream is unavailable"))) }
}
