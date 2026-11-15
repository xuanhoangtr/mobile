package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import vn.edu.hcmute.androidchat.backend.module
import vn.edu.hcmute.androidchat.backend.common.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class MediaRoutesTest {
    private val config = AppConfig("test", 8080, null, null, null, "media-unit-test-key-not-for-runtime", "media-test", "media-client")
    private fun token(user: Long = 7) = JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
        .withSubject(user.toString())
        .withClaim("sid", java.util.UUID.randomUUID().toString())
        .withExpiresAt(Instant.now().plusSeconds(300))
        .sign(Algorithm.HMAC256(config.jwtSecret))

    private fun fixture(
        maxBytes: Long = 64,
        uploadTimeoutMillis: Long = 60_000,
        storage: (Path) -> MediaStorage = { LocalMediaStorage(it) },
        block: suspend ApplicationTestBuilder.(Path, MediaService) -> Unit
    ) = testApplication {
        val root = Files.createTempDirectory("media-foundation-test-")
        val service = MediaService(storage(root), maxBytes, uploadTimeoutMillis = uploadTimeoutMillis)
        application { module(config); configureMediaFoundation(config, service) }
        try { block(root, service) }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun body(bytes: ByteArray = "Xin chào".toByteArray(), mime: String = "text/plain", name: String = "hello.txt", extra: Boolean = false) =
        MultiPartFormDataContent(formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, mime)
                append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
            })
            if (extra) append("conversationId", "101")
        })

    private fun multipartBytes(
        boundary: String,
        fileName: String = "hello.txt",
        mime: String = "text/plain",
        fileBytes: ByteArray = "Xin chào".toByteArray(),
        epilogue: ByteArray = byteArrayOf()
    ): ByteArray {
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mime\r\n\r\n"
        val close = "\r\n--$boundary--\r\n"
        return head.toByteArray() + fileBytes + close.toByteArray() + epilogue
    }

    private class RawMultipartContent(
        private val bytes: ByteArray,
        private val boundary: String,
        private val knownLength: Boolean
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
        override val contentLength: Long? = if (knownLength) bytes.size.toLong() else null
        override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(bytes)
    }

    private class IncompleteMultipartContent(
        private val boundary: String,
        fileName: String,
        mime: String,
        private val partialBytes: ByteArray
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
        override val contentLength: Long? = null
        private val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(prefix)
            channel.writeFully(partialBytes)
            awaitCancellation()
        }
    }

    @Test fun uploadReturnsReadyAndOwnerCanDownloadExactBytes() = fixture { _, _ ->
        val bytes = "Xin chào".toByteArray()
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body(bytes)) }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val id = json.getValue("id").jsonPrimitive.long
        assertTrue(id > 0)
        assertEquals(7, json.getValue("ownerId").jsonPrimitive.int)
        assertEquals("READY", json.getValue("uploadState").jsonPrimitive.content)
        assertEquals(bytes.size, json.getValue("byteSize").jsonPrimitive.int)
        assertEquals("OWNER_ONLY_DEV", json.getValue("accessScope").jsonPrimitive.content)
        assertFalse(json.containsKey("storageKey"))
        val metadata = client.get("/media/$id") { bearerAuth(token()) }
        assertEquals(HttpStatusCode.OK, metadata.status)
        val download = client.get("/media/$id/download") { bearerAuth(token()) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(bytes, download.readRawBytes())
        assertEquals("nosniff", download.headers["X-Content-Type-Options"])
        assertEquals("no-store", download.headers[HttpHeaders.CacheControl])
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
    }

    @Test fun uploadAndReadRequireJwt() = fixture { root, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.post("/media/upload") { setBody(body()) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/media/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/media/1/download").status)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun anotherUserCannotReadEvenWithClaimedConversation() = fixture { _, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body()) }
        val id = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
        assertEquals(HttpStatusCode.NotFound, client.get("/media/$id?conversationId=101") { bearerAuth(token(8)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/media/$id/download?conversationId=101") { bearerAuth(token(8)) }.status)
    }

    @Test fun forgedMimeIsRejectedAndBlobRemoved() = fixture { root, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body("not a PNG".toByteArray(), "image/png", "photo.png")) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun unsupportedMimeAndTraversalFilenameAreRejected() = fixture { root, _ ->
        for (payload in listOf(body(mime = "text/html", name = "page.html"), body(name = "../hello.txt"))) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/media/upload") { bearerAuth(token()); setBody(payload) }.status)
        }
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun streamLimitRejectsOversizedFileAndCleansPartialWrite() = fixture(maxBytes = 8) { root, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body(ByteArray(9000) { 65 })) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("MEDIA_TOO_LARGE"))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun unknownLengthValidMultipartStillUploads() = fixture { root, _ ->
        val boundary = "media-review-boundary"
        val response = client.post("/media/upload") {
            bearerAuth(token())
            setBody(RawMultipartContent(multipartBytes(boundary), boundary, knownLength = false))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1L, Files.list(root).use { it.count() })
    }

    @Test fun unknownLengthEpilogueIsIncludedInTotalRequestLimitAndCleaned() = fixture(maxBytes = 64) { root, _ ->
        val boundary = "media-epilogue-boundary"
        val content = multipartBytes(boundary, fileBytes = "small".toByteArray(), epilogue = ByteArray(66 * 1024) { 65 })
        val response = client.post("/media/upload") {
            bearerAuth(token())
            setBody(RawMultipartContent(content, boundary, knownLength = false))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("MEDIA_TOO_LARGE"))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun declaredContentLengthAboveTotalRequestLimitIsRejectedBeforeParsing() = fixture(maxBytes = 64) { root, _ ->
        val boundary = "media-content-length-boundary"
        val content = multipartBytes(boundary, epilogue = ByteArray(66 * 1024) { 66 })
        val response = client.post("/media/upload") {
            bearerAuth(token())
            setBody(RawMultipartContent(content, boundary, knownLength = true))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun unknownLengthOversizedPartHeaderCannotPublishAnObject() = fixture(maxBytes = 64) { root, _ ->
        val boundary = "media-large-header-boundary"
        val oversizedName = "a".repeat(66 * 1024) + ".txt"
        val content = multipartBytes(boundary, fileName = oversizedName, fileBytes = "small".toByteArray())
        val response = client.post("/media/upload") {
            bearerAuth(token())
            setBody(RawMultipartContent(content, boundary, knownLength = false))
        }
        assertTrue(response.status == HttpStatusCode.PayloadTooLarge || response.status == HttpStatusCode.BadRequest)
        assertFalse(response.status == HttpStatusCode.Created)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun unsupportedBoundaryIsRejectedBeforeMultipartParsing() = fixture(maxBytes = 64) { root, _ ->
        val boundary = "b".repeat(71)
        val response = client.post("/media/upload") {
            bearerAuth(token())
            setBody(RawMultipartContent(multipartBytes(boundary), boundary, knownLength = false))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun timeoutOnIncompletePartCleansStagingAndReleasesUploadSlots() = fixture(uploadTimeoutMillis = 100) { root, service ->
        val boundary = "media-timeout-boundary"
        val response = withTimeout(2_000) {
            client.post("/media/upload") {
                bearerAuth(token())
                setBody(IncompleteMultipartContent(boundary, "hello.txt", "text/plain", "partial".toByteArray()))
            }
        }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals(0L, Files.list(root).use { it.count() })
        withTimeout(2_000) {
            repeat(6) { service.uploadSlots.acquire() }
        }
        repeat(6) { service.uploadSlots.release() }
    }

    @Test fun emptyAndInvalidUtf8AreRejected() = fixture { root, _ ->
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28))) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/media/upload") { bearerAuth(token()); setBody(body(bytes)) }.status)
        }
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun conversationFieldIsStoredButDoesNotGrantAccessByItself() = fixture { root, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body(extra = true)) }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(101, json.getValue("conversationId").jsonPrimitive.int)
        assertEquals("CONVERSATION_POLICY_REQUIRED", json.getValue("accessScope").jsonPrimitive.content)
        val id = json.getValue("id").jsonPrimitive.long
        assertEquals(HttpStatusCode.NotFound, client.get("/media/$id") { bearerAuth(token(8)) }.status)
        assertEquals(1L, Files.list(root).use { it.count() })
    }

    @Test fun storageFailureUsesSanitizedSharedErrorEnvelope() = fixture(storage = { root ->
        object : MediaStorage by LocalMediaStorage(root) {
            override suspend fun write(source: io.ktor.utils.io.ByteReadChannel, maxBytes: Long): StoredBlob = throw IOException("private-path-not-for-response")
        }
    }) { _, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("STORAGE_UNAVAILABLE"))
        assertFalse(text.contains("private-path"))
    }

    @Test fun totalStorageQuotaAndConcurrentUploadGateAreEnforced() = fixture(storage = { LocalMediaStorage(it, totalLimit = 4) }) { root, service ->
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/media/upload") { bearerAuth(token()); setBody(body()) }.status)
        assertEquals(0L, Files.list(root).use { it.count() })
        repeat(6) { service.uploadSlots.acquire() }
        try {
            assertEquals(HttpStatusCode.TooManyRequests, client.post("/media/upload") { bearerAuth(token()); setBody(body()) }.status)
        } finally { repeat(6) { service.uploadSlots.release() } }
    }

    @Test fun mediaIsNotRegisteredWithoutExplicitOptIn() = testApplication {
        application { module(config) }
        assertEquals(HttpStatusCode.NotFound, client.get("/media/1") { bearerAuth(token()) }.status)
    }

    @Test fun missingStoredFileFailsClosedWithoutLeakingPath() = fixture { root, _ ->
        val response = client.post("/media/upload") { bearerAuth(token()); setBody(body()) }
        val id = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
        Files.list(root).use { paths -> paths.forEach(Files::delete) }
        val download = client.get("/media/$id/download") { bearerAuth(token()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, download.status)
        assertFalse(download.bodyAsText().contains(root.toString()))
    }

    @Test fun conversationMemberCanReadAndDownloadWhileOutsiderIsRejected() = testApplication {
        val root = Files.createTempDirectory("media-member-acl-test-")
        val membersByConv = mapOf(101L to setOf(7L, 8L))
        val service = MediaService(
            storage = LocalMediaStorage(root),
            maxBytes = 64,
            isConversationMember = { convId, uid -> uid in membersByConv[convId].orEmpty() }
        )
        application { module(config); configureMediaFoundation(config, service) }
        try {
            val bytes = "Shared photo".toByteArray()
            val upload = client.post("/media/upload") { bearerAuth(token(7)); setBody(body(bytes, extra = true)) }
            assertEquals(HttpStatusCode.Created, upload.status)
            val id = AppJson.parseToJsonElement(upload.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long

            // Member 8 can fetch metadata and download exact bytes
            assertEquals(HttpStatusCode.OK, client.get("/media/$id") { bearerAuth(token(8)) }.status)
            val download = client.get("/media/$id/download") { bearerAuth(token(8)) }
            assertEquals(HttpStatusCode.OK, download.status)
            assertContentEquals(bytes, download.readRawBytes())

            // Non-member 9 is denied
            assertEquals(HttpStatusCode.NotFound, client.get("/media/$id") { bearerAuth(token(9)) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/media/$id/download") { bearerAuth(token(9)) }.status)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun batchConcurrentUploadsAreAllowedUpToSixSlots() = fixture { _, service ->
        assertEquals(6, service.uploadSlots.availablePermits)
        val responses = coroutineScope {
            (1..4).map { idx ->
                async {
                    client.post("/media/upload") {
                        bearerAuth(token())
                        setBody(body("batch-$idx".toByteArray(), name = "photo-$idx.txt"))
                    }
                }
            }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.Created })
    }
}
