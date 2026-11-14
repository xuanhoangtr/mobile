package vn.edu.hcmute.androidchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.auth.AuthFeature
import vn.edu.hcmute.androidchat.feature.auth.ui.AuthEntryScreen
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import vn.edu.hcmute.androidchat.feature.chat.data.OkHttpChatApiService
import vn.edu.hcmute.androidchat.feature.chat.data.OkHttpChatWebSocketClient
import vn.edu.hcmute.androidchat.feature.chat.data.SqliteChatLocalCache
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatListScreen
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatRoomScreen
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel
import vn.edu.hcmute.androidchat.feature.contacts.ui.ContactsSearchScreen
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.ui.CreateGroupScreen
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel
import vn.edu.hcmute.androidchat.feature.media.data.AndroidMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.FileMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.HttpMediaUploadService
import vn.edu.hcmute.androidchat.feature.media.data.StreamMediaRepository
import vn.edu.hcmute.androidchat.feature.media.ui.MediaPickerScreen
import vn.edu.hcmute.androidchat.feature.media.viewmodel.MediaViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoicePlayback
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoiceRecorder
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceMessageViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.VoicePhase
import java.io.File
import vn.edu.hcmute.androidchat.feature.settings.data.AuthAppSettingsStore
import vn.edu.hcmute.androidchat.feature.settings.model.AppLanguage
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference
import vn.edu.hcmute.androidchat.feature.settings.model.resolveDarkTheme
import vn.edu.hcmute.androidchat.feature.settings.ui.ProfileSettingsScreen
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SkeletonRoute {
    CHAT_LIST,
    CHAT_ROOM,
    CREATE_GROUP,
    CONTACTS,
    PROFILE_SETTINGS,
    MEDIA_PICKER
}

class MainActivity : ComponentActivity() {
    private val backendBaseUrl by lazy { authApiBaseUrl().trimEnd('/') }
    private val authFeature by lazy { AuthFeature(applicationContext, backendBaseUrl) }
    private val appSettingsStore by lazy { AuthAppSettingsStore(applicationContext) }

    private val chatApiService by lazy { OkHttpChatApiService() }
    private val chatTransport by lazy { OkHttpChatWebSocketClient() }
    private val chatLocalCache by lazy { SqliteChatLocalCache(applicationContext) }

    @Volatile
    private var cachedAccessToken: String? = null

    private val authViewModel: AuthViewModel by viewModels { authFeature.viewModelFactory }
    private val contactsViewModel: ContactsViewModel by viewModels { authFeature.contactsViewModelFactory }
    private val chatListViewModel: ChatListViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatListViewModel(
                    transport = chatTransport,
                    apiService = chatApiService,
                    localCache = chatLocalCache
                ) as T
            }
        }
    }
    private val chatRoomViewModel: ChatRoomViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatRoomViewModel(
                    transport = chatTransport,
                    apiService = chatApiService,
                    localCache = chatLocalCache
                ) as T
            }
        }
    }
    private val createGroupViewModel: CreateGroupViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CreateGroupViewModel(apiService = chatApiService) as T
            }
        }
    }
    private fun createMediaUploadService(): HttpMediaUploadService = HttpMediaUploadService(
        endpoint = URL("$backendBaseUrl/media/upload"),
        accessToken = {
            kotlinx.coroutines.runBlocking {
                val fresh = authFeature.tokenProvider.accessToken()
                if (!fresh.isNullOrBlank()) {
                    cachedAccessToken = fresh
                    fresh
                } else {
                    cachedAccessToken.orEmpty()
                }
            }
        }
    )

    private val mediaViewModel: MediaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = StreamMediaRepository(
                    source = AndroidMediaSource(applicationContext.contentResolver),
                    service = createMediaUploadService()
                )
                return MediaViewModel(repository) as T
            }
        }
    }

    private val voiceViewModel: VoiceMessageViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = StreamMediaRepository(
                    source = FileMediaSource(),
                    service = createMediaUploadService()
                )
                return VoiceMessageViewModel(
                    recorder = AndroidVoiceRecorder(),
                    playback = AndroidVoicePlayback(applicationContext),
                    outputDirectory = File(cacheDir, "voice"),
                    mediaRepository = repository
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePreference by appSettingsStore.themePreference.collectAsState(
                initial = AppThemePreference.SYSTEM
            )
            val languagePreference by appSettingsStore.languagePreference.collectAsState(
                initial = AppLanguage.SYSTEM
            )
            val settingsScope = rememberCoroutineScope()
            AndroidChatTheme(
                darkTheme = themePreference.resolveDarkTheme(isSystemInDarkTheme()),
                appLanguage = languagePreference
            ) {
                var currentRoute by rememberSaveable { mutableStateOf(SkeletonRoute.CHAT_LIST) }
                val authState by authViewModel.uiState.collectAsState()

                LaunchedEffect(authState.authenticatedUser?.sessionId) {
                    currentRoute = SkeletonRoute.CHAT_LIST
                    val authenticated = authState.authenticatedUser
                    if (authenticated == null) {
                        cachedAccessToken = null
                        chatTransport.disconnect()
                    } else {
                        val token = authFeature.tokenProvider.accessToken()
                        cachedAccessToken = token
                        if (!token.isNullOrBlank()) {
                            chatTransport.connect(toWebSocketUrl(backendBaseUrl), token)
                            chatListViewModel.loadConversations(backendBaseUrl, token)
                            contactsViewModel.loadConnections()
                        }
                    }
                }

                if (authState.authenticatedUser == null) {
                    AuthEntryScreen(viewModel = authViewModel)
                } else {
                    BackHandler(enabled = currentRoute != SkeletonRoute.CHAT_LIST) {
                        if (currentRoute == SkeletonRoute.CHAT_ROOM) {
                            voiceViewModel.clear()
                        }
                        currentRoute = when (currentRoute) {
                            SkeletonRoute.MEDIA_PICKER -> SkeletonRoute.CHAT_ROOM
                            SkeletonRoute.CHAT_ROOM,
                            SkeletonRoute.CREATE_GROUP,
                            SkeletonRoute.CONTACTS,
                            SkeletonRoute.PROFILE_SETTINGS -> SkeletonRoute.CHAT_LIST
                            SkeletonRoute.CHAT_LIST -> SkeletonRoute.CHAT_LIST
                        }
                    }
                    when (currentRoute) {
                        SkeletonRoute.CHAT_LIST -> {
                            val listState by chatListViewModel.uiState.collectAsState()
                            ChatListScreen(
                                uiState = listState,
                                onSearchQueryChange = chatListViewModel::onSearchQueryChanged,
                                onConversationClick = { conversation ->
                                    chatRoomViewModel.openConversation(
                                        conversationId = conversation.id,
                                        title = conversation.title,
                                        isGroup = conversation.type == ConversationType.GROUP,
                                        currentUserId = requireNotNull(authState.authenticatedUser).user.id
                                    )
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                },
                                onCreateGroupClick = {
                                    contactsViewModel.loadConnections()
                                    currentRoute = SkeletonRoute.CREATE_GROUP
                                },
                                onContactsClick = { currentRoute = SkeletonRoute.CONTACTS },
                                onProfileClick = { currentRoute = SkeletonRoute.PROFILE_SETTINGS }
                            )
                        }

                        SkeletonRoute.CHAT_ROOM -> {
                            val roomState by chatRoomViewModel.uiState.collectAsState()
                            val voiceState by voiceViewModel.uiState.collectAsState()
                            val context = LocalContext.current
                            var hasMicPermission by remember {
                                mutableStateOf(
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                )
                            }
                            val micPermissionLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.RequestPermission()
                            ) { granted ->
                                hasMicPermission = granted
                                if (granted) {
                                    voiceViewModel.startRecording()
                                }
                            }
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            LaunchedEffect(voiceState.phase, voiceState.attachment) {
                                val attachment = voiceState.attachment
                                if (voiceState.phase == VoicePhase.SENT && attachment != null) {
                                    val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    chatRoomViewModel.queueOutgoingVoiceMessage(
                                        currentUserId = currentUserId,
                                        mediaId = attachment.mediaId,
                                        durationMs = attachment.durationMs,
                                        createdAtLabel = nowLabel,
                                        isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                    )
                                    voiceViewModel.clear()
                                }
                            }
                            LaunchedEffect(roomState.conversationId, authState.authenticatedUser?.sessionId) {
                                val token = authFeature.tokenProvider.accessToken()
                                cachedAccessToken = token
                                if (!token.isNullOrBlank() && roomState.conversationId > 0L) {
                                    chatRoomViewModel.loadInitialHistory(
                                        baseUrl = backendBaseUrl,
                                        accessToken = token,
                                        currentUserId = currentUserId
                                    )
                                }
                            }
                            ChatRoomScreen(
                                uiState = roomState,
                                onBackClick = {
                                    voiceViewModel.clear()
                                    currentRoute = SkeletonRoute.CHAT_LIST
                                },
                                onDraftChange = chatRoomViewModel::onDraftChanged,
                                onSendClick = {
                                    val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    chatRoomViewModel.queueOutgoingTextMessage(
                                        currentUserId = currentUserId,
                                        createdAtLabel = nowLabel,
                                        isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                    )
                                },
                                onAttachMediaClick = { currentRoute = SkeletonRoute.MEDIA_PICKER },
                                voiceState = voiceState,
                                hasMicrophonePermission = hasMicPermission,
                                onRequestMicrophonePermission = {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onStartVoiceRecording = voiceViewModel::startRecording,
                                onStopVoiceRecording = voiceViewModel::stopRecording,
                                onCancelVoiceRecording = voiceViewModel::interrupt,
                                onToggleVoicePlayback = voiceViewModel::togglePlayback,
                                onSendVoice = { voiceViewModel.upload(roomState.conversationId) },
                                onDiscardVoice = voiceViewModel::clear
                            )
                        }

                        SkeletonRoute.CREATE_GROUP -> {
                            val groupState by createGroupViewModel.uiState.collectAsState()
                            val contactsState by contactsViewModel.uiState.collectAsState()
                            LaunchedEffect(contactsState.friends) {
                                createGroupViewModel.setContacts(
                                    contactsState.friends.map {
                                        GroupMemberUiModel(
                                            userId = it.id,
                                            displayName = it.displayName
                                        )
                                    }
                                )
                            }
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            CreateGroupScreen(
                                uiState = groupState,
                                onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST },
                                onGroupNameChange = createGroupViewModel::onGroupNameChanged,
                                onToggleMember = createGroupViewModel::toggleMemberSelection,
                                onSubmitClick = {
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: return@launch
                                        cachedAccessToken = token
                                        createGroupViewModel.submitCreateGroup(
                                            baseUrl = backendBaseUrl,
                                            accessToken = token
                                        ) { created ->
                                            chatListViewModel.loadConversations(backendBaseUrl, token)
                                            chatRoomViewModel.openConversation(
                                                conversationId = created.id,
                                                title = created.title,
                                                isGroup = true,
                                                currentUserId = currentUserId
                                            )
                                            currentRoute = SkeletonRoute.CHAT_ROOM
                                        }
                                    }
                                }
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
                            },
                            languagePreference = languagePreference,
                            onLanguagePreferenceChanged = { preference ->
                                settingsScope.launch { appSettingsStore.setLanguagePreference(preference) }
                            }
                        )

                        SkeletonRoute.MEDIA_PICKER -> {
                            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { currentRoute = SkeletonRoute.CHAT_ROOM }) {
                                        Text("← Quay lại phòng chat")
                                    }
                                }
                                MediaPickerScreen(
                                    viewModel = mediaViewModel,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            chatTransport.disconnect()
        }
    }

    private fun toWebSocketUrl(httpBaseUrl: String): String {
        val wsBase = when {
            httpBaseUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + httpBaseUrl.removePrefix("https://")
            httpBaseUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + httpBaseUrl.removePrefix("http://")
            else -> httpBaseUrl
        }
        return "${wsBase.trimEnd('/')}/ws/chat"
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
