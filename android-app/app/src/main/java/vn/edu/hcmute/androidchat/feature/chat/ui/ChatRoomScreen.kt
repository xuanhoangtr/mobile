package vn.edu.hcmute.androidchat.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.model.ChatRoomUiState
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceError
import vn.edu.hcmute.androidchat.feature.media.voice.VoicePhase
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceUiState
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

/**
 * Chat room skeleton screen aligned with Stitch "Chat cá nhân" / "Chat nhóm" layout:
 * top bar with back action, light surface stream, left white incoming & right blue outgoing bubbles,
 * and bottom input bar keeping draft state in ViewModel across rotation.
 * Note: VISUAL_UNVERIFIED against physical device render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    uiState: ChatRoomUiState,
    onBackClick: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAttachMediaClick: (() -> Unit)? = null,
    voiceState: VoiceUiState? = null,
    hasMicrophonePermission: Boolean = false,
    onRequestMicrophonePermission: () -> Unit = {},
    onStartVoiceRecording: () -> Unit = {},
    onStopVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onToggleVoicePlayback: () -> Unit = {},
    onSendVoice: () -> Unit = {},
    onDiscardVoice: () -> Unit = {},
    playingVoiceMessageId: String? = null,
    playingVoicePositionMs: Long = 0L,
    playingVoiceDurationMs: Long = 0L,
    voiceStatusMessage: String? = null,
    onPlayVoiceMessage: ((MessageUiModel) -> Unit)? = null
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatStitchTokens.CardWhite
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                            tint = ChatStitchTokens.TextPrimary
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ChatStitchTokens.SoftBlueContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = uiState.conversationTitle.firstOrNull()?.uppercase() ?: "?",
                                color = ChatStitchTokens.BrandBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = uiState.conversationTitle,
                                color = ChatStitchTokens.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = connectionLabel(uiState.connectionState),
                                color = ChatStitchTokens.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = ChatStitchTokens.CardWhite,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!voiceStatusMessage.isNullOrBlank()) {
                        Text(
                            text = voiceStatusMessage,
                            color = Color(0xFFB3261E),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    if (voiceState != null && voiceState.phase != VoicePhase.IDLE && voiceState.phase != VoicePhase.SENT) {
                        VoiceComposerBar(
                            voiceState = voiceState,
                            onStopRecording = onStopVoiceRecording,
                            onCancelRecording = onCancelVoiceRecording,
                            onTogglePlayback = onToggleVoicePlayback,
                            onSendVoice = onSendVoice,
                            onDiscardVoice = onDiscardVoice
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (onAttachMediaClick != null) {
                                IconButton(onClick = onAttachMediaClick) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.media_foundation_title),
                                        tint = ChatStitchTokens.BrandBlue
                                    )
                                }
                            }
                            if (voiceState != null) {
                                IconButton(
                                    onClick = {
                                        if (hasMicrophonePermission) {
                                            onStartVoiceRecording()
                                        } else {
                                            onRequestMicrophonePermission()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = stringResource(R.string.chat_voice_mic),
                                        tint = ChatStitchTokens.BrandBlue
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = uiState.draftText,
                                onValueChange = onDraftChange,
                                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                                shape = RoundedCornerShape(24.dp),
                                maxLines = 4,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onSendClick,
                                enabled = uiState.draftText.trim().isNotEmpty(),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ChatStitchTokens.BrandBlue
                                )
                            ) {
                                Text(stringResource(R.string.chat_send))
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (uiState.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.chat_room_empty),
                    color = ChatStitchTokens.TextMuted
                )
            }
        } else {
            val todayLabel = stringResource(R.string.chat_date_today)
            val yesterdayLabel = stringResource(R.string.chat_date_yesterday)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(uiState.messages, key = { _, msg -> msg.localId }) { index, message ->
                    val currentDate = ChatTimeFormatter.dateKey(message.createdAt)
                    val previousDate = if (index > 0) {
                        ChatTimeFormatter.dateKey(uiState.messages[index - 1].createdAt)
                    } else {
                        null
                    }
                    if (currentDate != null && currentDate != previousDate) {
                        DateSeparator(
                            label = ChatTimeFormatter.formatDateHeader(
                                date = currentDate,
                                todayLabel = todayLabel,
                                yesterdayLabel = yesterdayLabel
                            )
                        )
                    }
                    MessageBubble(
                        message = message,
                        isPlayingVoice = playingVoiceMessageId == message.localId,
                        playingVoicePositionMs = if (playingVoiceMessageId == message.localId) {
                            playingVoicePositionMs
                        } else {
                            0L
                        },
                        playingVoiceDurationMs = if (playingVoiceMessageId == message.localId) {
                            playingVoiceDurationMs
                        } else {
                            0L
                        },
                        onPlayVoiceClick = onPlayVoiceMessage?.let { handler -> { handler(message) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ChatStitchTokens.SoftBlueContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                color = ChatStitchTokens.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageUiModel,
    isPlayingVoice: Boolean = false,
    playingVoicePositionMs: Long = 0L,
    playingVoiceDurationMs: Long = 0L,
    onPlayVoiceClick: (() -> Unit)? = null
) {
    val isMine = message.isFromCurrentUser
    val voiceMediaId = if (message.type == MessageType.VOICE) {
        extractVoiceMediaId(message.content)
    } else {
        null
    }
    val parsedDurationMs = if (message.type == MessageType.VOICE) {
        extractVoiceDurationMs(message.content)
    } else {
        0L
    }
    val displayText = if (message.type == MessageType.VOICE) {
        formatVoiceBubbleText(message.content)
    } else {
        message.content
    }
    val totalDurationMs = if (isPlayingVoice && playingVoiceDurationMs > 0L) {
        playingVoiceDurationMs
    } else {
        parsedDurationMs
    }
    val playbackProgress = if (isPlayingVoice && totalDurationMs > 0L) {
        (playingVoicePositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine) ChatStitchTokens.BrandBlue else ChatStitchTokens.CardWhite,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp
            ),
            tonalElevation = if (isMine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.type == MessageType.VOICE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPlayVoiceClick != null) {
                            IconButton(
                                onClick = onPlayVoiceClick,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        voiceMediaId == null -> Icons.Filled.Mic
                                        isPlayingVoice -> Icons.Filled.Pause
                                        else -> Icons.Filled.PlayArrow
                                    },
                                    contentDescription = stringResource(
                                        if (isPlayingVoice) R.string.chat_voice_pause else R.string.chat_voice_play
                                    ),
                                    tint = if (isMine) Color.White else ChatStitchTokens.BrandBlue
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = if (isMine) Color.White else ChatStitchTokens.BrandBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = displayText,
                                color = if (isMine) Color.White else ChatStitchTokens.TextPrimary,
                                fontSize = 15.sp
                            )
                            if (voiceMediaId != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { playbackProgress },
                                    color = if (isMine) Color.White else ChatStitchTokens.BrandBlue,
                                    trackColor = if (isMine) {
                                        Color.White.copy(alpha = 0.3f)
                                    } else {
                                        ChatStitchTokens.SoftBlueContainer
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                                if (isPlayingVoice) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${formatVoiceDuration(playingVoicePositionMs)} / ${formatVoiceDuration(totalDurationMs)}",
                                        color = if (isMine) Color.White.copy(alpha = 0.85f) else ChatStitchTokens.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = displayText,
                        color = if (isMine) Color.White else ChatStitchTokens.TextPrimary,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = ChatTimeFormatter.formatTimeOnly(message.createdAt),
                        color = if (isMine) Color.White.copy(alpha = 0.8f) else ChatStitchTokens.TextMuted,
                        fontSize = 11.sp
                    )
                    if (isMine) {
                        Text(
                            text = statusLabel(message.deliveryStatus),
                            color = if (message.deliveryStatus == MessageDeliveryStatus.FAILED) {
                                Color(0xFFFFDAD6)
                            } else {
                                Color.White.copy(alpha = 0.85f)
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: MessageDeliveryStatus): String = when (status) {
    MessageDeliveryStatus.SENDING -> stringResource(R.string.chat_status_sending)
    MessageDeliveryStatus.ACCEPTED -> stringResource(R.string.chat_status_accepted)
    MessageDeliveryStatus.DELIVERED -> stringResource(R.string.chat_status_delivered)
    MessageDeliveryStatus.READ -> stringResource(R.string.chat_status_read)
    MessageDeliveryStatus.FAILED -> stringResource(R.string.chat_status_failed)
}

@Composable
private fun connectionLabel(state: WsConnectionState): String = when (state) {
    WsConnectionState.DISCONNECTED -> stringResource(R.string.chat_connection_disconnected)
    WsConnectionState.CONNECTING -> stringResource(R.string.chat_connection_connecting)
    WsConnectionState.CONNECTED -> stringResource(R.string.chat_connection_connected)
    WsConnectionState.RECONNECTING -> stringResource(R.string.chat_connection_reconnecting)
}

@Composable
private fun VoiceComposerBar(
    voiceState: VoiceUiState,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSendVoice: () -> Unit,
    onDiscardVoice: () -> Unit
) {
    val durationLabel = formatVoiceDuration(voiceState.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (voiceState.phase) {
            VoicePhase.RECORDING -> {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F)
                )
                Text(
                    text = stringResource(R.string.chat_voice_recording, durationLabel),
                    color = ChatStitchTokens.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onCancelRecording) {
                    Text(stringResource(R.string.chat_voice_cancel))
                }
                Button(
                    onClick = onStopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.chat_voice_stop),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_voice_stop))
                }
            }

            VoicePhase.RECORDED,
            VoicePhase.PLAYING,
            VoicePhase.PAUSED -> {
                val previewProgress = if (voiceState.durationMs > 0L) {
                    (voiceState.playbackPositionMs.toFloat() / voiceState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                IconButton(onClick = onTogglePlayback) {
                    Icon(
                        imageVector = if (voiceState.phase == VoicePhase.PLAYING) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (voiceState.phase == VoicePhase.PLAYING) {
                                R.string.chat_voice_pause
                            } else {
                                R.string.chat_voice_play
                            }
                        ),
                        tint = ChatStitchTokens.BrandBlue
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (voiceState.phase == VoicePhase.PLAYING || voiceState.playbackPositionMs > 0L) {
                            "${formatVoiceDuration(voiceState.playbackPositionMs)} / $durationLabel"
                        } else {
                            durationLabel
                        },
                        color = ChatStitchTokens.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    LinearProgressIndicator(
                        progress = { previewProgress },
                        color = ChatStitchTokens.BrandBlue,
                        trackColor = ChatStitchTokens.SoftBlueContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
                IconButton(onClick = onDiscardVoice) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.chat_voice_discard),
                        tint = ChatStitchTokens.TextSecondary
                    )
                }
                Button(
                    onClick = onSendVoice,
                    colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue)
                ) {
                    Text(stringResource(R.string.chat_send))
                }
            }

            VoicePhase.UPLOADING -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.chat_voice_uploading),
                    color = ChatStitchTokens.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            VoicePhase.ERROR -> {
                val errorText = if (voiceState.error == VoiceError.MICROPHONE_PERMISSION_REQUIRED) {
                    stringResource(R.string.chat_voice_permission_needed)
                } else {
                    stringResource(R.string.chat_voice_error)
                }
                Text(
                    text = errorText,
                    color = Color(0xFFB3261E),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDiscardVoice) {
                    Text(stringResource(R.string.chat_voice_cancel))
                }
            }

            VoicePhase.IDLE,
            VoicePhase.SENT -> Unit
        }
    }
}

private fun formatVoiceDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

internal fun extractVoiceMediaId(content: String): Long? {
    val match = Regex("""(?:\[media:|#)(\d+)\]?""").find(content) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private fun extractVoiceDurationMs(content: String): Long {
    val match = Regex("""\((\d+):(\d{2})\)""").find(content) ?: return 0L
    val minutes = match.groupValues[1].toLongOrNull() ?: return 0L
    val seconds = match.groupValues[2].toLongOrNull() ?: return 0L
    return (minutes * 60L + seconds) * 1_000L
}

private fun formatVoiceBubbleText(content: String): String =
    content
        .replace(Regex("""\s*\[media:\d+\]"""), "")
        .replace(Regex("""\s*#\d+"""), "")
        .trim()

@Preview(showBackground = true)
@Composable
private fun ChatRoomScreenPreview() {
    AndroidChatTheme {
        ChatRoomScreen(
            uiState = ChatRoomUiState(
                conversationId = 1L,
                conversationTitle = "Đinh Công Huy",
                messages = listOf(
                    MessageUiModel(
                        localId = "1",
                        clientMessageId = "1",
                        conversationId = 1L,
                        senderId = 2L,
                        isFromCurrentUser = false,
                        content = "Mình check xong contract auth rồi nhé.",
                        createdAt = "12:58"
                    ),
                    MessageUiModel(
                        localId = "2",
                        clientMessageId = "2",
                        conversationId = 1L,
                        senderId = 3L,
                        isFromCurrentUser = true,
                        content = "Ok, mình vừa cập nhật docs/api-contract.md.",
                        createdAt = "13:00",
                        deliveryStatus = MessageDeliveryStatus.ACCEPTED
                    )
                )
            ),
            onBackClick = {},
            onDraftChange = {},
            onSendClick = {}
        )
    }
}
