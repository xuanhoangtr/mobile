package vn.edu.hcmute.androidchat.feature.media.wallpaper

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens

@Composable
fun WallpaperPickerScreen(
    selected: ChatWallpaper,
    onSelected: (ChatWallpaper) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Hình nền cuộc trò chuyện",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatStitchTokens.TextPrimary
                    )
                    Text(
                        text = "Chọn màu nền cho khung chat hiện tại",
                        fontSize = 12.sp,
                        color = ChatStitchTokens.TextMuted
                    )
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Đóng",
                            tint = ChatStitchTokens.TextSecondary
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatWallpaper.entries.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { wallpaper ->
                            WallpaperPreviewCard(
                                wallpaper = wallpaper,
                                isSelected = wallpaper == selected,
                                onClick = { onSelected(wallpaper) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreviewCard(
    wallpaper: ChatWallpaper,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) ChatStitchTokens.BrandBlue else Color(0xFFE0E4EE)
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = wallpaper.toComposeColor(),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = wallpaper.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = ChatStitchTokens.TextPrimary
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Đang chọn",
                        tint = ChatStitchTokens.BrandBlue,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.85f))
                    )
                }
            }

            // Simulated chat bubbles preview
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(ChatStitchTokens.BrandBlue.copy(alpha = 0.85f))
                    )
                }
            }
        }
    }
}
