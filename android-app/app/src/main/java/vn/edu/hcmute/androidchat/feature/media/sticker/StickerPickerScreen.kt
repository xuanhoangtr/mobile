package vn.edu.hcmute.androidchat.feature.media.sticker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens

@Composable
fun StickerPickerScreen(
    onStickerSelected: (StickerAttachment) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChatStitchTokens.CardWhite,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Nhãn dán",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatStitchTokens.TextPrimary
                    )
                    Text(
                        text = "Chạm để gửi nhanh vào cuộc trò chuyện",
                        fontSize = 12.sp,
                        color = ChatStitchTokens.TextMuted
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Đóng",
                            tint = ChatStitchTokens.TextSecondary
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StickerCatalog.stickers.chunked(6).forEach { rowStickers ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowStickers.forEach { sticker ->
                            StickerTile(
                                sticker = sticker,
                                onClick = { onStickerSelected(StickerAttachment(sticker.id)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(6 - rowStickers.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerTile(
    sticker: Sticker,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ChatStitchTokens.ChatSurfaceBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = sticker.unicode, fontSize = 24.sp)
        }
        Text(
            text = sticker.label,
            fontSize = 10.sp,
            color = ChatStitchTokens.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
