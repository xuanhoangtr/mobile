package vn.edu.hcmute.androidchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatListScreen
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatRoomScreen
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel
import vn.edu.hcmute.androidchat.feature.groups.ui.CreateGroupScreen
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

private enum class SkeletonRoute {
    CHAT_LIST,
    CHAT_ROOM,
    CREATE_GROUP
}

class MainActivity : ComponentActivity() {
    private val chatListViewModel: ChatListViewModel by viewModels()
    private val chatRoomViewModel: ChatRoomViewModel by viewModels()
    private val createGroupViewModel: CreateGroupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidChatTheme {
                var currentRoute by rememberSaveable { mutableStateOf(SkeletonRoute.CHAT_LIST) }

                when (currentRoute) {
                    SkeletonRoute.CHAT_LIST -> {
                        val listState by chatListViewModel.uiState.collectAsState()
                        ChatListScreen(
                            uiState = listState,
                            onSearchQueryChange = chatListViewModel::onSearchQueryChanged,
                            onConversationClick = { conversation ->
                                chatRoomViewModel.openConversation(
                                    conversationId = conversation.id,
                                    title = conversation.title
                                )
                                currentRoute = SkeletonRoute.CHAT_ROOM
                            },
                            onCreateGroupClick = { currentRoute = SkeletonRoute.CREATE_GROUP }
                        )
                    }

                    SkeletonRoute.CHAT_ROOM -> {
                        val roomState by chatRoomViewModel.uiState.collectAsState()
                        ChatRoomScreen(
                            uiState = roomState,
                            onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST },
                            onDraftChange = chatRoomViewModel::onDraftChanged,
                            onSendClick = {
                                chatRoomViewModel.queueOutgoingTextMessage(
                                    currentUserId = 0L,
                                    createdAtLabel = "--:--",
                                    isTransportConnected = false
                                )
                            }
                        )
                    }

                    SkeletonRoute.CREATE_GROUP -> {
                        val groupState by createGroupViewModel.uiState.collectAsState()
                        CreateGroupScreen(
                            uiState = groupState,
                            onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST },
                            onGroupNameChange = createGroupViewModel::onGroupNameChanged,
                            onToggleMember = createGroupViewModel::toggleMemberSelection,
                            onSubmitClick = { currentRoute = SkeletonRoute.CHAT_LIST }
                        )
                    }
                }
            }
        }
    }
}
