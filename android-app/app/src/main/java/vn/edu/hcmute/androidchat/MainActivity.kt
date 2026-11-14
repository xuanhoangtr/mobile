package vn.edu.hcmute.androidchat

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatListScreen
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatRoomScreen
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel
import vn.edu.hcmute.androidchat.feature.auth.AuthFeature
import vn.edu.hcmute.androidchat.feature.auth.ui.AuthEntryScreen
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import vn.edu.hcmute.androidchat.feature.contacts.ui.ContactsSearchScreen
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel
import vn.edu.hcmute.androidchat.feature.groups.ui.CreateGroupScreen
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel
import vn.edu.hcmute.androidchat.feature.settings.data.AuthAppSettingsStore
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference
import vn.edu.hcmute.androidchat.feature.settings.model.resolveDarkTheme
import vn.edu.hcmute.androidchat.feature.settings.ui.ProfileSettingsScreen
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

private enum class SkeletonRoute {
    CHAT_LIST,
    CHAT_ROOM,
    CREATE_GROUP,
    CONTACTS,
    PROFILE_SETTINGS
}

class MainActivity : ComponentActivity() {
    private val authFeature by lazy { AuthFeature(applicationContext, authApiBaseUrl()) }
    private val appSettingsStore by lazy { AuthAppSettingsStore(applicationContext) }
    private val authViewModel: AuthViewModel by viewModels { authFeature.viewModelFactory }
    private val contactsViewModel: ContactsViewModel by viewModels { authFeature.contactsViewModelFactory }
    private val chatListViewModel: ChatListViewModel by viewModels()
    private val chatRoomViewModel: ChatRoomViewModel by viewModels()
    private val createGroupViewModel: CreateGroupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePreference by appSettingsStore.themePreference.collectAsState(
                initial = AppThemePreference.SYSTEM
            )
            val settingsScope = rememberCoroutineScope()
            AndroidChatTheme(
                darkTheme = themePreference.resolveDarkTheme(isSystemInDarkTheme())
            ) {
                var currentRoute by rememberSaveable { mutableStateOf(SkeletonRoute.CHAT_LIST) }
                val authState by authViewModel.uiState.collectAsState()

                LaunchedEffect(authState.authenticatedUser?.sessionId) {
                    currentRoute = SkeletonRoute.CHAT_LIST
                }

                if (authState.authenticatedUser == null) {
                    AuthEntryScreen(viewModel = authViewModel)
                } else {
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
                                onCreateGroupClick = { currentRoute = SkeletonRoute.CREATE_GROUP },
                                onContactsClick = { currentRoute = SkeletonRoute.CONTACTS },
                                onProfileClick = { currentRoute = SkeletonRoute.PROFILE_SETTINGS }
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
                                        currentUserId = requireNotNull(authState.authenticatedUser).user.id,
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

                        SkeletonRoute.CONTACTS -> ContactsSearchScreen(
                            viewModel = contactsViewModel,
                            onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST }
                        )

                        SkeletonRoute.PROFILE_SETTINGS -> ProfileSettingsScreen(
                            uiState = authState,
                            onDisplayNameChanged = authViewModel::onProfileDisplayNameChanged,
                            onBioChanged = authViewModel::onProfileBioChanged,
                            onSaveProfile = authViewModel::saveProfile,
                            onLogout = authViewModel::logout,
                            onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST },
                            themePreference = themePreference,
                            onThemePreferenceChanged = { preference ->
                                settingsScope.launch { appSettingsStore.setThemePreference(preference) }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun authApiBaseUrl(): String {
        val applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        return requireNotNull(applicationInfo.metaData?.getString(AUTH_API_BASE_URL_METADATA)) {
            "AUTH_API_BASE_URL metadata is missing"
        }
    }

    private companion object {
        const val AUTH_API_BASE_URL_METADATA = "vn.edu.hcmute.androidchat.AUTH_API_BASE_URL"
    }
}
