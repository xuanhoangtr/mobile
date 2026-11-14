package vn.edu.hcmute.androidchat.backend.media

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

object MediaValidation {
    private val extensions = mapOf(
        "image/png" to setOf("png"), "image/jpeg" to setOf("jpg", "jpeg"),
        "video/mp4" to setOf("mp4"), "audio/mp4" to setOf("m4a", "mp4"),
        "audio/3gpp" to setOf("3gp"), "application/pdf" to setOf("pdf"), "text/plain" to setOf("txt")
    )

    fun fileName(raw: String?): String {
        val name = Normalizer.normalize(raw ?: invalidMedia("A filename is required"), Normalizer.Form.NFC)
        if (name.length !in 1..180 || name.startsWith('.') || name.contains("..") ||
            name.any { it.isISOControl() || it in "/\\:\"<>|?*" } || name.endsWith(' ') || name.endsWith('.')) {
            invalidMedia("Invalid filename")
        }
        return name
    }

    fun declaredType(raw: String?, name: String): String {
        val mime = raw?.substringBefore(';')?.trim()?.lowercase() ?: invalidMedia("A MIME type is required")
        val allowed = extensions[mime] ?: invalidMedia("Unsupported media type")
        if (name.substringAfterLast('.', "").lowercase() !in allowed) invalidMedia("Filename extension does not match MIME type")
        return mime
    }

    /** Checks signatures (not a malware scanner/complete codec decoder); text is validated in full. */
    fun verify(input: InputStream, mime: String) {
        input.use {
            if (mime == "text/plain") {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val reader = java.io.InputStreamReader(it, decoder)
                val buffer = CharArray(4096)
                var length = 0L
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    length += count
                    if ((0 until count).any { i -> buffer[i].isISOControl() && buffer[i] !in "\r\n\t" }) {
                        invalidMedia("File content does not match MIME type")
                    }
                }
                if (length == 0L) invalidMedia("Empty files are not supported")
                return
            }
            val prefix = it.readNBytes(16)
            fun starts(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { i ->
                (prefix[i].toInt() and 255) == bytes[i]
            }
            val valid = when (mime) {
                "image/png" -> starts(137, 80, 78, 71, 13, 10, 26, 10)
                "image/jpeg" -> starts(255, 216, 255)
                "application/pdf" -> starts(37, 80, 68, 70, 45)
                "video/mp4" -> prefix.size >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                    String(prefix, 8, 4, Charsets.US_ASCII) in setOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ")
                "audio/mp4" -> prefix.size >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                    String(prefix, 8, 4, Charsets.US_ASCII) in setOf("isom", "iso2", "mp41", "mp42", "M4A ")
                "audio/3gpp" -> prefix.size >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                    String(prefix, 8, 4, Charsets.US_ASCII) in setOf("3gp4", "3gp5", "3gp6")
                else -> false
            }
            if (!valid) invalidMedia("File content does not match MIME type")
        }
    }
}
