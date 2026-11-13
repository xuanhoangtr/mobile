package vn.edu.hcmute.androidchat.feature.media.wallpaper

import android.content.Context

enum class ChatWallpaper(val value: String) {
    DEFAULT("default"),
    SOFT_BLUE("soft_blue"),
    SOFT_GREEN("soft_green"),
    WARM("warm")
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
