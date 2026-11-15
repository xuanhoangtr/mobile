package vn.edu.hcmute.androidchat.feature.media.wallpaper

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class ChatWallpaper(
    val value: String,
    val backgroundColorHex: Long,
    val displayName: String
) {
    DEFAULT("default", 0xFFF8F9FFL, "Mặc định"),
    SOFT_BLUE("soft_blue", 0xFFE8F0FEL, "Xanh dịu"),
    SOFT_GREEN("soft_green", 0xFFE6F4EAL, "Xanh lá"),
    WARM("warm", 0xFFFFF4E5L, "Ấm áp");

    fun toComposeColor(): Color = Color(backgroundColorHex)
}

class WallpaperPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("chat_wallpaper", Context.MODE_PRIVATE)

    fun get(conversationId: Long): ChatWallpaper =
        ChatWallpaper.entries.firstOrNull {
            it.value == preferences.getString(key(conversationId), ChatWallpaper.DEFAULT.value)
        } ?: ChatWallpaper.DEFAULT

    fun set(conversationId: Long, wallpaper: ChatWallpaper) {
        require(conversationId > 0L)
        preferences.edit().putString(key(conversationId), wallpaper.value).apply()
    }

    fun clear(conversationId: Long) {
        require(conversationId > 0L)
        preferences.edit().remove(key(conversationId)).apply()
    }

    private fun key(conversationId: Long) = "conversation:$conversationId"
}
