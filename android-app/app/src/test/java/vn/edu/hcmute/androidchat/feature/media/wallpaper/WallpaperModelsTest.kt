package vn.edu.hcmute.androidchat.feature.media.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperModelsTest {
    @Test
    fun allWallpapersHaveStableValuesAndValidColors() {
        assertEquals(
            listOf(
                ChatWallpaper.DEFAULT,
                ChatWallpaper.SOFT_BLUE,
                ChatWallpaper.SOFT_GREEN,
                ChatWallpaper.WARM
            ),
            ChatWallpaper.entries
        )
        ChatWallpaper.entries.forEach { wallpaper ->
            assertTrue(wallpaper.value.isNotBlank())
            assertTrue(wallpaper.backgroundColorHex > 0L)
            assertEquals(wallpaper.backgroundColorHex.toULong(), wallpaper.toComposeColor().value shr 32)
        }
    }
}
