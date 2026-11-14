package vn.edu.hcmute.androidchat.feature.media.wallpaper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WallpaperPickerScreen(
    selected: ChatWallpaper,
    onSelected: (ChatWallpaper) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.wrapContentSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatWallpaper.entries.forEach { wallpaper ->
            FilterChip(
                selected = wallpaper == selected,
                onClick = { onSelected(wallpaper) },
                label = { Text(wallpaper.value.replace('_', ' ')) }
            )
        }
    }
}
