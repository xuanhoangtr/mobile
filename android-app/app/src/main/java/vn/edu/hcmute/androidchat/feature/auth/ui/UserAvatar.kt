package vn.edu.hcmute.androidchat.feature.auth.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private object AvatarBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24) {}
    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }
}

fun resolveAvatarUrl(avatarUrl: String?, baseUrl: String): String? {
    val raw = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
        raw
    } else {
        val cleanBase = baseUrl.trimEnd('/')
        if (cleanBase.isEmpty()) null else "$cleanBase/${raw.trimStart('/')}"
    }
}

/**
 * Circular user avatar displaying a remote image when [avatarUrl] is present,
 * falling back cleanly to the user's uppercase initial.
 */
@Composable
fun UserAvatar(
    avatarUrl: String?,
    displayName: String,
    baseUrl: String,
    size: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFF1A56DB),
    contentColor: Color = Color.White
) {
    val resolvedUrl = resolveAvatarUrl(avatarUrl, baseUrl)
    val bitmapState = produceState<Bitmap?>(initialValue = resolvedUrl?.let(AvatarBitmapCache::get), resolvedUrl) {
        if (resolvedUrl == null) {
            value = null
            return@produceState
        }
        AvatarBitmapCache.get(resolvedUrl)?.let { cached ->
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            loadAvatarBitmap(resolvedUrl)?.also { loaded ->
                AvatarBitmapCache.put(resolvedUrl, loaded)
            }
        }
    }

    val bitmap = bitmapState.value
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = containerColor
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Text(
                    text = initial,
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp
                )
            }
        }
    }
}

internal fun decodeScaledBitmapBytes(
    contentResolver: ContentResolver,
    uri: Uri,
    maxDimension: Int = 512
): ByteArray? = runCatching {
    val rawBytes = contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        val maxInputBytes = 16 * 1024 * 1024
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxInputBytes) return@runCatching null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: run {
        android.util.Log.w("UserAvatar", "openInputStream returned null for selected image")
        return@runCatching null
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        android.util.Log.w("UserAvatar", "Invalid image dimensions (${bounds.outWidth}x${bounds.outHeight})")
        return@runCatching null
    }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension * 2 ||
        bounds.outHeight / sampleSize > maxDimension * 2
    ) {
        sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
        ?: return@runCatching null

    val scaled = if (decoded.width > maxDimension || decoded.height > maxDimension) {
        val ratio = maxDimension.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        ).also {
            if (it !== decoded) decoded.recycle()
        }
    } else {
        decoded
    }

    try {
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    } finally {
        scaled.recycle()
    }
}.onFailure { error ->
    android.util.Log.w("UserAvatar", "Failed to decode avatar bitmap", error)
}.getOrNull()

private fun loadAvatarBitmap(urlString: String): Bitmap? {
    val connection = (URL(urlString).openConnection() as? HttpURLConnection)?.apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 10_000
    } ?: return null
    return try {
        val status = connection.responseCode
        if (status !in 200..299) {
            android.util.Log.w("UserAvatar", "Avatar fetch failed with HTTP $status")
            return null
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (exception: Exception) {
        android.util.Log.w("UserAvatar", "Avatar fetch threw exception", exception)
        null
    } finally {
        connection.disconnect()
    }
}
