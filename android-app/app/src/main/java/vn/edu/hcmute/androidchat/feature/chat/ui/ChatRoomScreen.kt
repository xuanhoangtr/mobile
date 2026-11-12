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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
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
    modifier: Modifier = Modifier
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
                    TextButton(onClick = onBackClick) {
                        Text(
                            text = "←",
                            fontSize = 20.sp,
                            color = ChatStitchTokens.TextPrimary
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages, key = { it.localId }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageUiModel) {
    val isMine = message.isFromCurrentUser
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
                Text(
                    text = message.content,
                    color = if (isMine) Color.White else ChatStitchTokens.TextPrimary,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = message.createdAt,
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
