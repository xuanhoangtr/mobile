package vn.edu.hcmute.androidchat.feature.chat.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.model.ChatMessageFormatter
import vn.edu.hcmute.androidchat.feature.chat.model.ChatRoomUiState
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.MessageDeliveryStatus
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceError
import vn.edu.hcmute.androidchat.feature.media.voice.VoicePhase
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceUiState
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme
import java.util.Locale

private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

/**
 * Full chat room screen supporting realtime messaging, replies, edits, recalls, forwards,
 * reactions, pinning, in-room search, typing indicator, wallpapers, voice/media/location/sticker
 * attachments, and call/group-member top bar actions.
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
    onPlayVoiceMessage: ((MessageUiModel) -> Unit)? = null,
    availableConversations: List<ConversationUiModel> = emptyList(),
    wallpaperColorArgb: Long? = null,
    onToggleReaction: (Long, String) -> Unit = { _, _ -> },
    onStartReply: (MessageUiModel) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onStartEdit: (MessageUiModel) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSubmitEdit: () -> Unit = {},
    onForwardMessage: (Long, Long) -> Unit = { _, _ -> },
    onTogglePinMessage: (Long, Boolean) -> Unit = { _, _ -> },
    onRecallMessage: (Long) -> Unit = {},
    onDeleteMessage: (Long) -> Unit = {},
    onRetryMessage: (String) -> Unit = {},
    onCancelFailedMessage: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onLoadOlderHistory: () -> Unit = {},
    onOpenMediaClick: ((MessageUiModel) -> Unit)? = null,
    onOpenLocationClick: ((MessageUiModel) -> Unit)? = null,
    mediaPreviewLoader: (@Composable (Long, Modifier) -> Unit)? = null,
    onAttachBatchMediaClick: (() -> Unit)? = null,
    onAttachLocationClick: (() -> Unit)? = null,
    onOpenStickerClick: () -> Unit = {},
    onVoiceCallClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onGroupMembersClick: () -> Unit = {},
    onWallpaperClick: () -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.firstVisibleMessageIndex,
        initialFirstVisibleItemScrollOffset = uiState.firstVisibleMessageOffset
    )

    var showSearchBar by rememberSaveable(uiState.conversationId) { mutableStateOf(false) }
    var selectedActionMessage by remember { mutableStateOf<MessageUiModel?>(null) }
    var forwardingMessage by remember { mutableStateOf<MessageUiModel?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    val roomBackgroundColor = wallpaperColorArgb?.let { Color(it) } ?: ChatStitchTokens.ChatSurfaceBackground

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
        }
    }

    val scrollToServerMessage: (Long) -> Unit = { targetId ->
        val idx = uiState.messages.indexOfFirst { it.serverId == targetId }
        if (idx >= 0) {
            val offsetForHeader = if (uiState.hasMoreHistory) 1 else 0
            coroutineScope.launch {
                listState.animateScrollToItem(idx + offsetForHeader)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = roomBackgroundColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
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
                            Box(contentAlignment = Alignment.BottomEnd) {
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
                                if (!uiState.isGroup && uiState.isPeerOnline) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(ChatStitchTokens.OnlineGreen)
                                            .border(1.5.dp, ChatStitchTokens.CardWhite, CircleShape)
                                    )
                                }
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
                                val subtitleInfo = roomSubtitleInfo(uiState)
                                Text(
                                    text = subtitleInfo.first,
                                    color = subtitleInfo.second,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onVoiceCallClick) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "Gọi thoại",
                                tint = ChatStitchTokens.BrandBlue
                            )
                        }
                        IconButton(onClick = onVideoCallClick) {
                            Icon(
                                imageVector = Icons.Filled.Videocam,
                                contentDescription = "Gọi video",
                                tint = ChatStitchTokens.BrandBlue
                            )
                        }
                        if (uiState.isGroup) {
                            IconButton(onClick = onGroupMembersClick) {
                                Icon(
                                    imageVector = Icons.Filled.Group,
                                    contentDescription = "Thành viên nhóm",
                                    tint = ChatStitchTokens.BrandBlue
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (showSearchBar) {
                                    showSearchBar = false
                                    onClearSearch()
                                } else {
                                    showSearchBar = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Tìm kiếm tin nhắn",
                                tint = ChatStitchTokens.TextPrimary
                            )
                        }
                        IconButton(onClick = onWallpaperClick) {
                            Icon(
                                imageVector = Icons.Filled.Wallpaper,
                                contentDescription = "Hình nền chat",
                                tint = ChatStitchTokens.TextPrimary
                            )
                        }
                    }
                )

                if (showSearchBar) {
                    InRoomSearchBar(
                        query = uiState.searchQuery,
                        results = uiState.searchResults,
                        onQueryChange = onSearchQueryChange,
                        onResultClick = { matched ->
                            matched.serverId?.let(scrollToServerMessage)
                        },
                        onCloseClick = {
                            showSearchBar = false
                            onClearSearch()
                        }
                    )
                }

                if (uiState.pinnedMessageIds.isNotEmpty()) {
                    PinnedMessagesBanner(
                        pinnedIds = uiState.pinnedMessageIds,
                        messages = uiState.messages,
                        onJumpToMessage = scrollToServerMessage,
                        onUnpinMessage = { id -> onTogglePinMessage(id, true) }
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = ChatStitchTokens.CardWhite,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (uiState.typingUserNames.isNotEmpty()) {
                        Text(
                            text = "${uiState.typingUserNames.joinToString(", ")} đang nhập...",
                            color = ChatStitchTokens.BrandBlue,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    if (!voiceStatusMessage.isNullOrBlank()) {
                        Text(
                            text = voiceStatusMessage,
                            color = Color(0xFFB3261E),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    val editingMsg = uiState.editingMessage
                    val replyingMsg = uiState.replyingToMessage
                    if (editingMsg != null) {
                        ComposerContextBanner(
                            title = "Đang chỉnh sửa tin nhắn",
                            preview = editingMsg.content,
                            onCancel = onCancelEdit
                        )
                    } else if (replyingMsg != null) {
                        ComposerContextBanner(
                            title = "Đang trả lời",
                            preview = if (replyingMsg.isRecalled) "Tin nhắn đã được thu hồi" else ChatMessageFormatter.sanitizePreview(replyingMsg.content),
                            onCancel = onCancelReply
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
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showAttachmentSheet = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Đính kèm",
                                    tint = ChatStitchTokens.BrandBlue
                                )
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
                            IconButton(onClick = onOpenStickerClick) {
                                Icon(
                                    imageVector = Icons.Filled.EmojiEmotions,
                                    contentDescription = "Sticker",
                                    tint = ChatStitchTokens.BrandBlue
                                )
                            }
                            OutlinedTextField(
                                value = uiState.draftText,
                                onValueChange = onDraftChange,
                                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                                shape = RoundedCornerShape(24.dp),
                                maxLines = 4,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    if (editingMsg != null) onSubmitEdit() else onSendClick()
                                },
                                enabled = uiState.draftText.trim().isNotEmpty(),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ChatStitchTokens.BrandBlue
                                )
                            ) {
                                Text(
                                    if (editingMsg != null) "Lưu" else stringResource(R.string.chat_send)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.messages.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.hasMoreHistory) {
                        item(key = "load_older_history") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingOlder) {
                                    CircularProgressIndicator(
                                        color = ChatStitchTokens.BrandBlue,
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(onClick = onLoadOlderHistory) {
                                        Text("Tải tin nhắn cũ hơn")
                                    }
                                }
                            }
                        }
                    }

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
                        val replyTarget = message.replyToId?.let { targetId ->
                            uiState.messages.firstOrNull { it.serverId == targetId }
                        }
                        val senderDisplayName = if (uiState.isGroup && !message.isFromCurrentUser) {
                            uiState.memberNamesByUserId[message.senderId] ?: "User #${message.senderId}"
                        } else {
                            null
                        }
                        MessageBubble(
                            message = message,
                            senderDisplayName = senderDisplayName,
                            replyTargetMessage = replyTarget,
                            onReplyQuoteClick = {
                                message.replyToId?.let(scrollToServerMessage)
                            },
                            onReactionPillClick = { emoji ->
                                message.serverId?.let { id -> onToggleReaction(id, emoji) }
                            },
                            onLongPressOrClick = {
                                if (!message.isRecalled) {
                                    selectedActionMessage = message
                                }
                            },
                            onRetryClick = { onRetryMessage(message.localId) },
                            onCancelFailedClick = { onCancelFailedMessage(message.localId) },
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
                            onPlayVoiceClick = onPlayVoiceMessage?.let { handler -> { handler(message) } },
                            onOpenMediaClick = onOpenMediaClick?.let { handler -> { handler(message) } },
                            onOpenLocationClick = {
                                if (onOpenLocationClick != null) {
                                    onOpenLocationClick(message)
                                } else {
                                    parseLocationFromMessage(message)?.let { (lat, lng, label) ->
                                        val labelPart = label?.takeIf { it.isNotBlank() }
                                            ?.let { "(${Uri.encode(it)})" }
                                            .orEmpty()
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("geo:$lat,$lng$labelPart?q=$lat,$lng")
                                        )
                                        runCatching { context.startActivity(intent) }
                                    }
                                }
                            },
                            mediaPreviewLoader = mediaPreviewLoader
                        )
                    }
                }
            }

            if (uiState.isLoading && uiState.messages.isEmpty()) {
                CircularProgressIndicator(
                    color = ChatStitchTokens.BrandBlue,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    selectedActionMessage?.let { target ->
        MessageActionBottomSheet(
            message = target,
            isPinned = target.serverId != null && target.serverId in uiState.pinnedMessageIds,
            onDismiss = { selectedActionMessage = null },
            onQuickReaction = { emoji ->
                selectedActionMessage = null
                target.serverId?.let { id -> onToggleReaction(id, emoji) }
            },
            onReply = {
                selectedActionMessage = null
                onStartReply(target)
            },
            onEdit = {
                selectedActionMessage = null
                onStartEdit(target)
            },
            onCopy = {
                selectedActionMessage = null
                clipboardManager.setText(AnnotatedString(target.content))
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Đã sao chép tin nhắn")
                }
            },
            onForward = {
                selectedActionMessage = null
                forwardingMessage = target
            },
            onTogglePin = { currentlyPinned ->
                selectedActionMessage = null
                target.serverId?.let { id -> onTogglePinMessage(id, currentlyPinned) }
            },
            onRecall = {
                selectedActionMessage = null
                target.serverId?.let(onRecallMessage)
            },
            onDelete = {
                selectedActionMessage = null
                if (target.serverId != null) {
                    onDeleteMessage(target.serverId)
                } else if (target.deliveryStatus == MessageDeliveryStatus.FAILED) {
                    onCancelFailedMessage(target.localId)
                }
            }
        )
    }

    forwardingMessage?.let { sourceMsg ->
        ForwardConversationPickerSheet(
            conversations = availableConversations,
            currentConversationId = uiState.conversationId,
            onDismiss = { forwardingMessage = null },
            onSelectConversation = { targetConv ->
                forwardingMessage = null
                sourceMsg.serverId?.let { srcId ->
                    onForwardMessage(srcId, targetConv.id)
                }
            }
        )
    }

    if (showAttachmentSheet) {
        AttachmentOptionsSheet(
            hasMediaPicker = onAttachMediaClick != null,
            hasBatchMediaPicker = onAttachBatchMediaClick != null,
            hasLocationPicker = onAttachLocationClick != null,
            hasVoiceRecorder = voiceState != null,
            onDismiss = { showAttachmentSheet = false },
            onPickMedia = {
                showAttachmentSheet = false
                onAttachMediaClick?.invoke()
            },
            onPickBatchMedia = {
                showAttachmentSheet = false
                onAttachBatchMediaClick?.invoke()
            },
            onPickLocation = {
                showAttachmentSheet = false
                onAttachLocationClick?.invoke()
            },
            onStartVoice = {
                showAttachmentSheet = false
                if (hasMicrophonePermission) {
                    onStartVoiceRecording()
                } else {
                    onRequestMicrophonePermission()
                }
            },
            onPickWallpaper = {
                showAttachmentSheet = false
                onWallpaperClick()
            }
        )
    }
}

@Composable
private fun InRoomSearchBar(
    query: String,
    results: List<MessageUiModel>,
    onQueryChange: (String) -> Unit,
    onResultClick: (MessageUiModel) -> Unit,
    onCloseClick: () -> Unit
) {
    Surface(
        color = ChatStitchTokens.CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Tìm trong cuộc trò chuyện…") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Đóng tìm kiếm",
                        tint = ChatStitchTokens.TextSecondary
                    )
                }
            }
            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${results.size} kết quả",
                    color = ChatStitchTokens.BrandBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                ) {
                    items(results, key = { "sr_${it.localId}_${it.serverId}" }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultClick(item) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.content,
                                color = ChatStitchTokens.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ChatTimeFormatter.formatTimeOnly(item.createdAt),
                                color = ChatStitchTokens.TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        HorizontalDivider(color = ChatStitchTokens.SoftBlueContainer, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedMessagesBanner(
    pinnedIds: List<Long>,
    messages: List<MessageUiModel>,
    onJumpToMessage: (Long) -> Unit,
    onUnpinMessage: (Long) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val topPinnedId = pinnedIds.first()
    val previewText = messages.firstOrNull { it.serverId == topPinnedId }?.content?.let(ChatMessageFormatter::sanitizePreview)
        ?.takeIf { it.isNotBlank() }
        ?: "Tin nhắn #$topPinnedId"

    Surface(
        color = ChatStitchTokens.SoftBlueContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJumpToMessage(topPinnedId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = null,
                    tint = ChatStitchTokens.BrandBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (pinnedIds.size == 1) "Tin nhắn đã ghim" else "${pinnedIds.size} tin nhắn đã ghim",
                        color = ChatStitchTokens.BrandBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = previewText,
                        color = ChatStitchTokens.TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (pinnedIds.size > 1) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Thu gọn" else "Mở rộng",
                            tint = ChatStitchTokens.BrandBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { onUnpinMessage(topPinnedId) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Bỏ ghim",
                        tint = ChatStitchTokens.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (expanded && pinnedIds.size > 1) {
                HorizontalDivider(color = ChatStitchTokens.BrandBlue.copy(alpha = 0.2f), thickness = 0.5.dp)
                pinnedIds.drop(1).forEach { id ->
                    val text = messages.firstOrNull { it.serverId == id }?.content
                        ?.takeIf { it.isNotBlank() }
                        ?: "Tin nhắn #$id"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToMessage(id) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = text,
                            color = ChatStitchTokens.TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onUnpinMessage(id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Bỏ ghim",
                                tint = ChatStitchTokens.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerContextBanner(
    title: String,
    preview: String,
    onCancel: () -> Unit
) {
    Surface(
        color = ChatStitchTokens.SoftBlueContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(ChatStitchTokens.BrandBlue, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = ChatStitchTokens.BrandBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = preview,
                    color = ChatStitchTokens.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Hủy",
                    tint = ChatStitchTokens.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: MessageUiModel,
    senderDisplayName: String? = null,
    replyTargetMessage: MessageUiModel? = null,
    onReplyQuoteClick: () -> Unit = {},
    onReactionPillClick: (String) -> Unit = {},
    onLongPressOrClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onCancelFailedClick: () -> Unit = {},
    isPlayingVoice: Boolean = false,
    playingVoicePositionMs: Long = 0L,
    playingVoiceDurationMs: Long = 0L,
    onPlayVoiceClick: (() -> Unit)? = null,
    onOpenMediaClick: (() -> Unit)? = null,
    onOpenLocationClick: (() -> Unit)? = null,
    mediaPreviewLoader: (@Composable (Long, Modifier) -> Unit)? = null
) {
    val isMine = message.isFromCurrentUser
    val isSticker = !message.isRecalled && message.type == MessageType.STICKER

    val bubbleColor = when {
        isSticker -> Color.Transparent
        message.isRecalled -> ChatStitchTokens.SoftBlueContainer.copy(alpha = 0.65f)
        isMine -> ChatStitchTokens.BrandBlue
        else -> ChatStitchTokens.CardWhite
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isMine) 18.dp else 4.dp,
        bottomEnd = if (isMine) 4.dp else 18.dp
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (senderDisplayName != null) {
            Text(
                text = senderDisplayName,
                color = ChatStitchTokens.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            tonalElevation = if (isMine || isSticker || message.isRecalled) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 288.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {
                        when {
                            message.isRecalled -> Unit
                            message.type == MessageType.LOCATION -> onOpenLocationClick?.invoke()
                            message.type in setOf(
                                MessageType.IMAGE,
                                MessageType.VIDEO,
                                MessageType.AUDIO,
                                MessageType.FILE
                            ) -> onOpenMediaClick?.invoke() ?: onLongPressOrClick()
                            else -> onLongPressOrClick()
                        }
                    },
                    onLongClick = {
                        if (!message.isRecalled) onLongPressOrClick()
                    }
                )
        ) {
            if (message.isRecalled) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = "Tin nhắn đã được thu hồi",
                        color = ChatStitchTokens.TextMuted,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = ChatTimeFormatter.formatTimeOnly(message.createdAt),
                        color = ChatStitchTokens.TextMuted,
                        fontSize = 11.sp
                    )
                }
            } else {
                Column(
                    modifier = if (isSticker) {
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    }
                ) {
                    if (message.forwardedFromId != null && !isSticker) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Forward,
                                contentDescription = null,
                                tint = if (isMine) Color.White.copy(alpha = 0.85f) else ChatStitchTokens.TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Đã chuyển tiếp",
                                color = if (isMine) Color.White.copy(alpha = 0.85f) else ChatStitchTokens.TextMuted,
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }

                    if (message.replyToId != null && !isSticker) {
                        val quotedPreview = when {
                            replyTargetMessage == null -> "Tin nhắn gốc #${message.replyToId}"
                            replyTargetMessage.isRecalled -> "Tin nhắn đã được thu hồi"
                            else -> ChatMessageFormatter.sanitizePreview(replyTargetMessage.content)
                        }
                        Surface(
                            color = if (isMine) Color.White.copy(alpha = 0.16f) else ChatStitchTokens.SoftBlueContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clickable(onClick = onReplyQuoteClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(24.dp)
                                        .background(
                                            if (isMine) Color.White else ChatStitchTokens.BrandBlue,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = quotedPreview,
                                    color = if (isMine) Color.White.copy(alpha = 0.92f) else ChatStitchTokens.TextPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    MessageTypeBody(
                        message = message,
                        isMine = isMine,
                        isPlayingVoice = isPlayingVoice,
                        playingVoicePositionMs = playingVoicePositionMs,
                        playingVoiceDurationMs = playingVoiceDurationMs,
                        onPlayVoiceClick = onPlayVoiceClick,
                        onOpenMediaClick = onOpenMediaClick,
                        onOpenLocationClick = onOpenLocationClick,
                        mediaPreviewLoader = mediaPreviewLoader
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val timeColor = when {
                            isSticker -> ChatStitchTokens.TextMuted
                            isMine -> Color.White.copy(alpha = 0.8f)
                            else -> ChatStitchTokens.TextMuted
                        }
                        Text(
                            text = ChatTimeFormatter.formatTimeOnly(message.createdAt),
                            color = timeColor,
                            fontSize = 11.sp
                        )
                        if (message.isEdited) {
                            Text(
                                text = "Đã chỉnh sửa",
                                color = timeColor,
                                fontSize = 10.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                        if (isMine) {
                            Text(
                                text = statusLabel(message.deliveryStatus),
                                color = if (message.deliveryStatus == MessageDeliveryStatus.FAILED) {
                                    if (isSticker) ChatStitchTokens.AlertRed else Color(0xFFFFDAD6)
                                } else {
                                    timeColor
                                },
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        if (!message.isRecalled && message.reactionCounts.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                message.reactionCounts.entries
                    .filter { it.value > 0 }
                    .forEach { (emoji, count) ->
                        val isSelected = emoji in message.myReactions
                        Surface(
                            color = if (isSelected) ChatStitchTokens.SoftBlueContainer else ChatStitchTokens.CardWhite,
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 1.dp else 0.5.dp,
                                    color = if (isSelected) ChatStitchTokens.BrandBlue else ChatStitchTokens.SoftBlueContainer,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onReactionPillClick(emoji) }
                        ) {
                            Text(
                                text = "$emoji $count",
                                color = if (isSelected) ChatStitchTokens.BrandBlue else ChatStitchTokens.TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
            }
        }

        if (message.isFromCurrentUser && message.deliveryStatus == MessageDeliveryStatus.FAILED) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                TextButton(
                    onClick = onRetryClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thử lại", fontSize = 12.sp)
                }
                TextButton(
                    onClick = onCancelFailedClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Hủy", color = ChatStitchTokens.AlertRed, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MessageTypeBody(
    message: MessageUiModel,
    isMine: Boolean,
    isPlayingVoice: Boolean,
    playingVoicePositionMs: Long,
    playingVoiceDurationMs: Long,
    onPlayVoiceClick: (() -> Unit)?,
    onOpenMediaClick: (() -> Unit)?,
    onOpenLocationClick: (() -> Unit)?,
    mediaPreviewLoader: (@Composable (Long, Modifier) -> Unit)?
) {
    val contentColor = if (isMine) Color.White else ChatStitchTokens.TextPrimary
    val secondaryColor = if (isMine) Color.White.copy(alpha = 0.85f) else ChatStitchTokens.TextSecondary
    val meta = remember(message.metadataJson) { parseMetadataObject(message.metadataJson) }

    when (message.type) {
        MessageType.VOICE -> {
            val voiceMediaId = message.mediaRef ?: extractVoiceMediaId(message.content)
            val parsedDurationMs = meta?.optLong("durationMs", 0L)?.takeIf { it > 0L }
                ?: extractVoiceDurationMs(message.content)
            val displayText = formatVoiceBubbleText(message.content)
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
                        color = contentColor,
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
                                color = secondaryColor,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        MessageType.IMAGE -> {
            val fileName = meta?.optString("fileName")?.takeIf { it.isNotBlank() } ?: message.content
            val mediaRef = message.mediaRef
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mediaPreviewLoader != null && mediaRef != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = onOpenMediaClick != null) { onOpenMediaClick?.invoke() }
                    ) {
                        mediaPreviewLoader(mediaRef, Modifier.fillMaxSize())
                    }
                } else {
                    AttachmentCard(
                        icon = Icons.Filled.Image,
                        title = fileName.ifBlank { "Hình ảnh" },
                        subtitle = formatByteSize(meta?.optLong("byteSize", -1L)) ?: "Nhấn để xem ảnh",
                        isMine = isMine,
                        onClick = onOpenMediaClick
                    )
                }
                if (fileName.isNotBlank() && mediaPreviewLoader != null && mediaRef != null) {
                    Text(
                        text = fileName,
                        color = contentColor,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        MessageType.VIDEO -> {
            val fileName = meta?.optString("fileName")?.takeIf { it.isNotBlank() } ?: message.content
            AttachmentCard(
                icon = Icons.Filled.PlayCircle,
                title = fileName.ifBlank { "Video" },
                subtitle = formatByteSize(meta?.optLong("byteSize", -1L)) ?: "Nhấn để phát video",
                isMine = isMine,
                onClick = onOpenMediaClick
            )
        }

        MessageType.AUDIO -> {
            val fileName = meta?.optString("fileName")?.takeIf { it.isNotBlank() } ?: message.content
            AttachmentCard(
                icon = Icons.Filled.AudioFile,
                title = fileName.ifBlank { "Âm thanh" },
                subtitle = formatByteSize(meta?.optLong("byteSize", -1L)) ?: "Nhấn để nghe",
                isMine = isMine,
                onClick = onOpenMediaClick
            )
        }

        MessageType.FILE -> {
            val fileName = meta?.optString("fileName")?.takeIf { it.isNotBlank() } ?: message.content
            AttachmentCard(
                icon = Icons.Filled.InsertDriveFile,
                title = fileName.ifBlank { "Tệp đính kèm" },
                subtitle = formatByteSize(meta?.optLong("byteSize", -1L)) ?: "Nhấn để tải tệp",
                isMine = isMine,
                onClick = onOpenMediaClick
            )
        }

        MessageType.STICKER -> {
            val unicode = meta?.optString("unicode")?.takeIf { it.isNotBlank() } ?: message.content
            Text(
                text = unicode,
                fontSize = 44.sp
            )
        }

        MessageType.LOCATION -> {
            val parsed = parseLocationFromMessage(message)
            val title = parsed?.third?.takeIf { it.isNotBlank() } ?: "Vị trí đã chia sẻ"
            val coordsText = if (parsed != null) {
                String.format(Locale.US, "%.5f, %.5f", parsed.first, parsed.second)
            } else {
                message.content
            }
            AttachmentCard(
                icon = Icons.Filled.LocationOn,
                title = title,
                subtitle = "$coordsText · Nhấn để mở bản đồ",
                isMine = isMine,
                onClick = onOpenLocationClick
            )
        }

        MessageType.TEXT,
        MessageType.SYSTEM -> {
            Text(
                text = message.content,
                color = contentColor,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun AttachmentCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isMine: Boolean,
    onClick: (() -> Unit)?
) {
    Surface(
        color = if (isMine) Color.White.copy(alpha = 0.16f) else ChatStitchTokens.SoftBlueContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isMine) Color.White else ChatStitchTokens.BrandBlue,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isMine) Color.White else ChatStitchTokens.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = if (isMine) Color.White.copy(alpha = 0.85f) else ChatStitchTokens.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionBottomSheet(
    message: MessageUiModel,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onQuickReaction: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit
) {
    val hasServerId = message.serverId != null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            if (hasServerId) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        val active = emoji in message.myReactions
                        Surface(
                            color = if (active) ChatStitchTokens.SoftBlueContainer else Color.Transparent,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .clickable { onQuickReaction(emoji) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(
                    color = ChatStitchTokens.SoftBlueContainer,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            if (hasServerId) {
                ActionSheetRow(icon = Icons.AutoMirrored.Filled.Reply, label = "Trả lời", onClick = onReply)
            }
            if (hasServerId && message.isFromCurrentUser && message.type == MessageType.TEXT) {
                ActionSheetRow(icon = Icons.Filled.Edit, label = "Chỉnh sửa", onClick = onEdit)
            }
            if (message.content.isNotBlank()) {
                ActionSheetRow(icon = Icons.Filled.ContentCopy, label = "Sao chép", onClick = onCopy)
            }
            if (hasServerId) {
                ActionSheetRow(icon = Icons.AutoMirrored.Filled.Forward, label = "Chuyển tiếp", onClick = onForward)
                ActionSheetRow(
                    icon = Icons.Filled.PushPin,
                    label = if (isPinned) "Bỏ ghim" else "Ghim tin nhắn",
                    onClick = { onTogglePin(isPinned) }
                )
            }
            if (hasServerId && message.isFromCurrentUser) {
                ActionSheetRow(
                    icon = Icons.Filled.Undo,
                    label = "Thu hồi",
                    tint = ChatStitchTokens.AlertRed,
                    onClick = onRecall
                )
            }
            if (hasServerId || message.deliveryStatus == MessageDeliveryStatus.FAILED) {
                ActionSheetRow(
                    icon = Icons.Filled.Delete,
                    label = "Xóa",
                    tint = ChatStitchTokens.AlertRed,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    tint: Color = ChatStitchTokens.TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text = label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardConversationPickerSheet(
    conversations: List<ConversationUiModel>,
    currentConversationId: Long,
    onDismiss: () -> Unit,
    onSelectConversation: (ConversationUiModel) -> Unit
) {
    val candidates = conversations.filter { it.id != currentConversationId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Chuyển tiếp đến",
                color = ChatStitchTokens.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (candidates.isEmpty()) {
                Text(
                    text = "Không có cuộc trò chuyện nào khác.",
                    color = ChatStitchTokens.TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(candidates, key = { it.id }) { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectConversation(conv) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(ChatStitchTokens.SoftBlueContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = conv.title.firstOrNull()?.uppercase() ?: "?",
                                    color = ChatStitchTokens.BrandBlue,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = conv.title,
                                color = ChatStitchTokens.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = ChatStitchTokens.SoftBlueContainer, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentOptionsSheet(
    hasMediaPicker: Boolean,
    hasBatchMediaPicker: Boolean,
    hasLocationPicker: Boolean,
    hasVoiceRecorder: Boolean,
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickBatchMedia: () -> Unit,
    onPickLocation: () -> Unit,
    onStartVoice: () -> Unit,
    onPickWallpaper: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Đính kèm & Tiện ích",
                color = ChatStitchTokens.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (hasMediaPicker) {
                ActionSheetRow(
                    icon = Icons.Filled.PermMedia,
                    label = "Ảnh / Video / Tệp",
                    tint = ChatStitchTokens.BrandBlue,
                    onClick = onPickMedia
                )
            }
            if (hasBatchMediaPicker) {
                ActionSheetRow(
                    icon = Icons.Filled.Collections,
                    label = "Gửi nhiều ảnh",
                    tint = ChatStitchTokens.BrandBlue,
                    onClick = onPickBatchMedia
                )
            }
            if (hasLocationPicker) {
                ActionSheetRow(
                    icon = Icons.Filled.LocationOn,
                    label = "Chia sẻ vị trí",
                    tint = ChatStitchTokens.BrandBlue,
                    onClick = onPickLocation
                )
            }
            if (hasVoiceRecorder) {
                ActionSheetRow(
                    icon = Icons.Filled.Mic,
                    label = "Ghi âm thoại",
                    tint = ChatStitchTokens.BrandBlue,
                    onClick = onStartVoice
                )
            }
            ActionSheetRow(
                icon = Icons.Filled.Wallpaper,
                label = "Hình nền chat",
                tint = ChatStitchTokens.BrandBlue,
                onClick = onPickWallpaper
            )
        }
    }
}

private fun parseMetadataObject(metadataJson: String?): JSONObject? {
    if (metadataJson.isNullOrBlank()) return null
    return runCatching { JSONObject(metadataJson) }.getOrNull()
}

private fun parseLocationFromMessage(message: MessageUiModel): Triple<Double, Double, String?>? {
    val meta = parseMetadataObject(message.metadataJson)
    if (meta != null && meta.has("latitude") && meta.has("longitude")) {
        val lat = meta.optDouble("latitude", Double.NaN)
        val lng = meta.optDouble("longitude", Double.NaN)
        if (lat.isFinite() && lng.isFinite()) {
            val label = meta.optString("label").takeIf { it.isNotBlank() && it != "null" }
            return Triple(lat, lng, label)
        }
    }
    val match = Regex("""(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""").find(message.content)
        ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lng = match.groupValues[2].toDoubleOrNull() ?: return null
    return Triple(lat, lng, null)
}

private fun formatByteSize(bytes: Long?): String? {
    if (bytes == null || bytes < 0L) return null
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
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
private fun roomSubtitleInfo(uiState: ChatRoomUiState): Pair<String, Color> {
    if (uiState.connectionState != WsConnectionState.CONNECTED) {
        return connectionLabel(uiState.connectionState) to ChatStitchTokens.TextMuted
    }
    if (uiState.isGroup) {
        return stringResource(R.string.chat_group_subtitle) to ChatStitchTokens.TextMuted
    }
    if (uiState.isPeerOnline) {
        return stringResource(R.string.chat_presence_online) to ChatStitchTokens.OnlineGreen
    }
    val formattedLastSeen = ChatTimeFormatter.formatLastSeen(
        isoTimestamp = uiState.peerLastSeenAt,
        yesterdayLabel = stringResource(R.string.chat_date_yesterday)
    )
    return if (formattedLastSeen != null) {
        stringResource(R.string.chat_presence_last_seen, formattedLastSeen) to ChatStitchTokens.TextMuted
    } else {
        stringResource(R.string.chat_presence_offline) to ChatStitchTokens.TextMuted
    }
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

private fun formatVoiceDuration(durationMs: Long): String =
    ChatMessageFormatter.formatDurationShort(durationMs)

internal fun extractVoiceMediaId(content: String): Long? {
    val match = Regex("""(?:\[media:|#)(\d+)\]?""").find(content) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private fun extractVoiceDurationMs(content: String): Long {
    Regex("""\((\d+)s\)""").find(content)?.let { secMatch ->
        val seconds = secMatch.groupValues[1].toLongOrNull() ?: return 0L
        return seconds * 1_000L
    }
    val match = Regex("""\((\d+):(\d{2})\)""").find(content) ?: return 0L
    val minutes = match.groupValues[1].toLongOrNull() ?: return 0L
    val seconds = match.groupValues[2].toLongOrNull() ?: return 0L
    return (minutes * 60L + seconds) * 1_000L
}

private fun formatVoiceBubbleText(content: String): String =
    ChatMessageFormatter.sanitizePreview(content)

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
