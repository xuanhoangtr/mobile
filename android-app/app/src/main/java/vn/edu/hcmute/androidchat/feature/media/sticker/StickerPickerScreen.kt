package vn.edu.hcmute.androidchat.feature.media.sticker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StickerPickerScreen(
    onStickerSelected: (StickerAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.wrapContentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StickerCatalog.stickers.forEach { sticker ->
            FilterChip(
                selected = false,
                onClick = { onStickerSelected(StickerAttachment(sticker.id)) },
                label = { Text(sticker.unicode) }
            )
        }
    }
}
