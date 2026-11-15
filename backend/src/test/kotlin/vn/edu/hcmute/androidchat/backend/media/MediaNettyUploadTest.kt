package vn.edu.hcmute.androidchat.backend.media

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vn.edu.hcmute.androidchat.backend.common.AppConfig
import vn.edu.hcmute.androidchat.backend.module
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local Netty socket tests; they do not open a public listener or use production configuration. */
class MediaNettyUploadTest {
    private val config = AppConfig("test", 8080, null, null, null, "media-netty-test-key-not-for-runtime", "media-test", "media-client")
    private fun token() = JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience)
        .withSubject("7")
        .withClaim("sid", java.util.UUID.randomUUID().toString())
        .withExpiresAt(Instant.now().plusSeconds(300))
        .sign(Algorithm.HMAC256(config.jwtSecret))

    private fun withNetty(uploadTimeoutMillis: Long = 60_000, block: suspend (Path, MediaService, Int) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("media-netty-test-")
        val service = MediaService(LocalMediaStorage(root), maxBytes = 64, uploadTimeoutMillis = uploadTimeoutMillis)
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(config)
            configureMediaFoundation(config, service)
        }
        try {
            server.start(wait = false)
            awaitListener(port)
            block(root, service, port)
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private suspend fun awaitListener(port: Int) = withTimeout(5_000) {
        while (true) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return@withTimeout
            } catch (_: IOException) {
                delay(20)
            }
        }
    }

    private fun partialRequest(port: Int, fileName: String, mime: String): Socket {
        val boundary = "media-netty-boundary"
        val part = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mime\r\n\r\npartial").toByteArray(UTF_8)
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        socket.soTimeout = 2_000
        val output = socket.getOutputStream()
        val requestHeaders = "POST /media/upload HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer ${token()}\r\n" +
            "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        output.write(requestHeaders.toByteArray(UTF_8))
        output.write((part.size.toString(16) + "\r\n").toByteArray(UTF_8))
        output.write(part)
        output.write("\r\n".toByteArray(UTF_8))
        output.flush()
        return socket
    }

    private fun sendCompleteChunkedRequest(port: Int, body: ByteArray): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        socket.soTimeout = 3_000
        val output = socket.getOutputStream()
        val headers = "POST /media/upload HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer ${token()}\r\n" +
            "Content-Type: multipart/form-data; boundary=media-netty-large-boundary\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray(UTF_8))
        var offset = 0
        while (offset < body.size) {
            val count = minOf(8 * 1024, body.size - offset)
            output.write((count.toString(16) + "\r\n").toByteArray(UTF_8))
            output.write(body, offset, count)
            output.write("\r\n".toByteArray(UTF_8))
            offset += count
        }
        output.write("0\r\n\r\n".toByteArray(UTF_8))
        output.flush()
        return socket
    }

    private fun readStatus(socket: Socket): String = BufferedReader(InputStreamReader(socket.getInputStream(), UTF_8)).readLine()
        ?: error("Netty closed without an HTTP status line")

    private suspend fun waitForSlots(service: MediaService) = withTimeout(3_000) {
        while (true) {
            var acquired = 0
            while (acquired < 6 && service.uploadSlots.tryAcquire()) {
                acquired++
            }
            repeat(acquired) { service.uploadSlots.release() }
            if (acquired == 6) return@withTimeout
            delay(10)
        }
    }

    private suspend fun waitForStagingFile(root: Path) = withTimeout(2_000) {
        while (Files.list(root).use { it.count() } == 0L) delay(10)
    }

    @Test fun nettyRejectsInvalidMimeBeforeIncompletePartDrain() = withNetty { root, service, port ->
        val socket = partialRequest(port, "page.html", "text/html")
        try {
            val status = readStatus(socket)
            assertTrue(status.contains("400"), "Expected early 400 from Netty, got $status")
        } finally {
            socket.close()
        }
        waitForSlots(service)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun nettyTimeoutAbortsAnIncompleteUploadAndCleansStaging() = withNetty(uploadTimeoutMillis = 500) { root, service, port ->
        val socket = partialRequest(port, "hello.txt", "text/plain")
        try {
            waitForStagingFile(root)
            val status = readStatus(socket)
            assertTrue(status.contains("408"), "Expected upload timeout from Netty, got $status")
        } finally {
            socket.close()
        }
        waitForSlots(service)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun nettyChunkedEpilogueCannotExceedTotalRequestLimit() = withNetty { root, service, port ->
        val boundary = "media-netty-large-boundary"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n" +
            "Content-Type: text/plain\r\n\r\n"
        val ending = "\r\n--$boundary--\r\n"
        val body = head.toByteArray(UTF_8) + "small".toByteArray() + ending.toByteArray(UTF_8) + ByteArray(66 * 1024) { 65 }
        val socket = sendCompleteChunkedRequest(port, body)
        try {
            val status = readStatus(socket)
            assertTrue(status.contains("413"), "Expected total-body 413 from Netty, got $status")
        } finally {
            socket.close()
        }
        waitForSlots(service)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun nettyClientDisconnectAbortsIncompleteRequestAndReleasesSlot() = withNetty { root, service, port ->
        val socket = partialRequest(port, "hello.txt", "text/plain")
        waitForStagingFile(root)
        socket.close()
        waitForSlots(service)
        assertEquals(0L, Files.list(root).use { it.count() })
    }
}
