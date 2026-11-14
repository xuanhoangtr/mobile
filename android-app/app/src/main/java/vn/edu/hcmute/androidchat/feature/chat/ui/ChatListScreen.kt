package vn.edu.hcmute.androidchat.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import vn.edu.hcmute.androidchat.feature.chat.model.ChatListUiState
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

private enum class ChatFilterTab {
    ALL,
    UNREAD,
    GROUPS
}

/**
 * Chat list skeleton screen aligned with Stitch "Trang chủ — Danh sách đoạn chat" layout:
 * top header, search pill, horizontal filter chips, conversation list, primary FAB, and 4-tab nav bar.
 * Note: VISUAL_UNVERIFIED against physical device render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    uiState: ChatListUiState,
    onSearchQueryChange: (String) -> Unit,
    onConversationClick: (ConversationUiModel) -> Unit,
    onCreateGroupClick: () -> Unit,
    onContactsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedFilter by rememberSaveable { mutableStateOf(ChatFilterTab.ALL) }

    val displayedConversations = uiState.visibleConversations.filter { conversation ->
        when (selectedFilter) {
            ChatFilterTab.ALL -> true
            ChatFilterTab.UNREAD -> conversation.unreadCount > 0
            ChatFilterTab.GROUPS -> conversation.type == ConversationType.GROUP
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatStitchTokens.CardWhite
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(ChatStitchTokens.BrandBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "C",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.chat_list_title),
                                color = ChatStitchTokens.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = connectionLabel(uiState.connectionState),
                                color = ChatStitchTokens.TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateGroupClick,
                containerColor = ChatStitchTokens.BrandBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chat_new_group)
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = ChatStitchTokens.CardWhite) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ChatBubble,
                            contentDescription = stringResource(R.string.nav_chats),
                            tint = ChatStitchTokens.BrandBlue
                        )
                    },
                    label = { Text(stringResource(R.string.nav_chats), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onContactsClick,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.People,
                            contentDescription = stringResource(R.string.nav_contacts),
                            tint = ChatStitchTokens.TextMuted
                        )
                    },
                    label = { Text(stringResource(R.string.nav_contacts), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Call,
                            contentDescription = stringResource(R.string.nav_calls),
                            tint = ChatStitchTokens.TextMuted
                        )
                    },
                    label = { Text(stringResource(R.string.nav_calls), maxLines = 1) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onProfileClick,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = stringResource(R.string.nav_profile),
                            tint = ChatStitchTokens.TextMuted
                        )
                    },
                    label = { Text(stringResource(R.string.nav_profile), maxLines = 1) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(R.string.chat_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ChatFilterChip(
                        label = stringResource(R.string.chat_filter_all),
                        selected = selectedFilter == ChatFilterTab.ALL,
                        onClick = { selectedFilter = ChatFilterTab.ALL }
                    )
                }
                item {
                    ChatFilterChip(
                        label = stringResource(R.string.chat_filter_unread),
                        selected = selectedFilter == ChatFilterTab.UNREAD,
                        onClick = { selectedFilter = ChatFilterTab.UNREAD }
                    )
                }
                item {
                    ChatFilterChip(
                        label = stringResource(R.string.chat_filter_groups),
                        selected = selectedFilter == ChatFilterTab.GROUPS,
                        onClick = { selectedFilter = ChatFilterTab.GROUPS }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ChatStitchTokens.BrandBlue)
                    }
                }

                uiState.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = ChatStitchTokens.AlertRed
                        )
                    }
                }

                displayedConversations.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.chat_list_empty),
                            color = ChatStitchTokens.TextMuted
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(displayedConversations, key = { it.id }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = { onConversationClick(conversation) }
                            )
                            HorizontalDivider(
                                color = ChatStitchTokens.SoftBlueContainer,
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ChatStitchTokens.BrandBlue,
            selectedLabelColor = Color.White,
            containerColor = ChatStitchTokens.SoftBlueContainer,
            labelColor = ChatStitchTokens.TextPrimary
        )
    )
}

@Composable
private fun ConversationRow(
    conversation: ConversationUiModel,
    onClick: () -> Unit
) {
    Surface(
        color = ChatStitchTokens.CardWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ChatStitchTokens.SoftBlueContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = conversation.title.firstOrNull()?.uppercase() ?: "?",
                        color = ChatStitchTokens.BrandBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                if (conversation.isOnline) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ChatStitchTokens.OnlineGreen)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title,
                        color = ChatStitchTokens.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.isPinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_pinned_badge),
                            color = ChatStitchTokens.BrandBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (!conversation.lastMessageTime.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = ChatTimeFormatter.formatConversationListTime(conversation.lastMessageTime),
                            color = ChatStitchTokens.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessagePreview.orEmpty(),
                        color = ChatStitchTokens.TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = ChatStitchTokens.BrandBlue,
                            contentColor = Color.White
                        ) {
                            Text(conversation.unreadCount.toString())
                        }
                    }
                }
            }
        }
    }
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
private fun ChatListScreenPreview() {
    AndroidChatTheme {
        ChatListScreen(
            uiState = ChatListUiState(
                conversations = listOf(
                    ConversationUiModel(
                        id = 1L,
                        type = ConversationType.DIRECT,
                        title = "Đinh Công Huy",
                        lastMessagePreview = "Chốt API contract ở D02 nhé",
                        lastMessageTime = "09:30",
                        unreadCount = 2,
                        isPinned = true,
                        isOnline = true
                    ),
                    ConversationUiModel(
                        id = 2L,
                        type = ConversationType.GROUP,
                        title = "Đồ Án Di Động Android",
                        lastMessagePreview = "Quang Huy: Đã dựng xong media skeleton",
                        lastMessageTime = "08:45",
                        unreadCount = 0
                    )
                )
            ),
            onSearchQueryChange = {},
            onConversationClick = {},
            onCreateGroupClick = {}
        )
    }
}
