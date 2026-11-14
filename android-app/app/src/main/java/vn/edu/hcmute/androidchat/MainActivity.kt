package vn.edu.hcmute.androidchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.auth.AuthFeature
import vn.edu.hcmute.androidchat.feature.auth.ui.AuthEntryScreen
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import vn.edu.hcmute.androidchat.feature.chat.data.ChatServerEvent
import vn.edu.hcmute.androidchat.feature.chat.data.OkHttpChatApiService
import vn.edu.hcmute.androidchat.feature.chat.data.OkHttpChatWebSocketClient
import vn.edu.hcmute.androidchat.feature.chat.data.SqliteChatLocalCache
import vn.edu.hcmute.androidchat.feature.chat.model.ConversationType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageType
import vn.edu.hcmute.androidchat.feature.chat.model.MessageUiModel
import vn.edu.hcmute.androidchat.feature.chat.model.WsConnectionState
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatListScreen
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatRoomScreen
import vn.edu.hcmute.androidchat.feature.chat.ui.extractVoiceMediaId
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatListViewModel
import vn.edu.hcmute.androidchat.feature.chat.viewmodel.ChatRoomViewModel
import vn.edu.hcmute.androidchat.feature.contacts.ui.ContactsSearchScreen
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel
import vn.edu.hcmute.androidchat.feature.groups.model.GroupMemberUiModel
import vn.edu.hcmute.androidchat.feature.groups.ui.CreateGroupScreen
import vn.edu.hcmute.androidchat.feature.groups.ui.GroupMembersScreen
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.GroupMembersViewModel
import vn.edu.hcmute.androidchat.feature.media.batch.BatchMediaScreen
import vn.edu.hcmute.androidchat.feature.media.batch.BatchMediaViewModel
import vn.edu.hcmute.androidchat.feature.media.call.CallMedia
import vn.edu.hcmute.androidchat.feature.media.call.CallPhase
import vn.edu.hcmute.androidchat.feature.media.call.CallScreen
import vn.edu.hcmute.androidchat.feature.media.call.CallSignalSender
import vn.edu.hcmute.androidchat.feature.media.call.CallSignalTypes
import vn.edu.hcmute.androidchat.feature.media.call.CallViewModel
import vn.edu.hcmute.androidchat.feature.media.call.GroupCallPhase
import vn.edu.hcmute.androidchat.feature.media.call.GroupCallScreen
import vn.edu.hcmute.androidchat.feature.media.call.GroupCallViewModel
import vn.edu.hcmute.androidchat.feature.media.data.AndroidMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.FileMediaSource
import vn.edu.hcmute.androidchat.feature.media.data.HttpMediaUploadService
import vn.edu.hcmute.androidchat.feature.media.data.HttpPushTokenClient
import vn.edu.hcmute.androidchat.feature.media.data.StreamMediaRepository
import vn.edu.hcmute.androidchat.feature.media.location.LocationAttachment
import vn.edu.hcmute.androidchat.feature.media.location.LocationShareScreen
import vn.edu.hcmute.androidchat.feature.media.location.toMapIntent
import vn.edu.hcmute.androidchat.feature.media.push.ChatNotificationManager
import vn.edu.hcmute.androidchat.feature.media.push.DevicePushRegistrar
import vn.edu.hcmute.androidchat.feature.media.sticker.StickerPickerScreen
import vn.edu.hcmute.androidchat.feature.media.ui.MediaPickerScreen
import vn.edu.hcmute.androidchat.feature.media.viewmodel.MediaViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoicePlayback
import vn.edu.hcmute.androidchat.feature.media.voice.AndroidVoiceRecorder
import vn.edu.hcmute.androidchat.feature.media.voice.VoiceMessageViewModel
import vn.edu.hcmute.androidchat.feature.media.voice.VoicePhase
import vn.edu.hcmute.androidchat.feature.media.wallpaper.ChatWallpaper
import vn.edu.hcmute.androidchat.feature.media.wallpaper.WallpaperPickerScreen
import vn.edu.hcmute.androidchat.feature.media.wallpaper.WallpaperPreferences
import vn.edu.hcmute.androidchat.feature.settings.data.AuthAppSettingsStore
import vn.edu.hcmute.androidchat.feature.settings.model.AppLanguage
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference
import vn.edu.hcmute.androidchat.feature.settings.model.resolveDarkTheme
import vn.edu.hcmute.androidchat.feature.settings.ui.ProfileSettingsScreen
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SkeletonRoute {
    CHAT_LIST,
    CHAT_ROOM,
    CREATE_GROUP,
    GROUP_MEMBERS,
    CONTACTS,
    PROFILE_SETTINGS,
    MEDIA_PICKER,
    BATCH_MEDIA,
    LOCATION_SHARE,
    CALL,
    GROUP_CALL
}

private data class PendingCallAction(
    val conversationId: Long,
    val isGroup: Boolean,
    val media: CallMedia,
    val peerUserId: Long?,
    val title: String,
    val initialParticipants: Set<Long> = emptySet()
)

class MainActivity : ComponentActivity() {
    private val backendBaseUrl by lazy { authApiBaseUrl().trimEnd('/') }
    private val authFeature by lazy { AuthFeature(applicationContext, backendBaseUrl) }
    private val appSettingsStore by lazy { AuthAppSettingsStore(applicationContext) }

    private val chatApiService by lazy { OkHttpChatApiService() }
    private val chatTransport by lazy { OkHttpChatWebSocketClient() }
    private val chatLocalCache by lazy { SqliteChatLocalCache(applicationContext) }
    private val wallpaperPreferences by lazy { WallpaperPreferences(applicationContext) }
    private val notificationManager by lazy { ChatNotificationManager(applicationContext) }

    @Volatile
    private var cachedAccessToken: String? = null

    private var pendingNotificationConversationId by mutableLongStateOf(0L)

    private val tokenAccessor: () -> String = {
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

    private val devicePushRegistrar by lazy {
        DevicePushRegistrar(applicationContext) {
            HttpPushTokenClient(
                endpoint = URL("$backendBaseUrl/devices/push-token"),
                accessToken = tokenAccessor
            )
        }
    }

    private val callSignalSender: CallSignalSender = { conversationId, callId, signalType, targetUserId, sdpOrCandidate ->
        chatTransport.sendCallSignal(
            conversationId = conversationId,
            callId = callId,
            signalType = signalType,
            targetUserId = targetUserId,
            sdpOrCandidate = sdpOrCandidate
        )
    }

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
    private val groupMembersViewModel: GroupMembersViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GroupMembersViewModel(apiService = chatApiService) as T
            }
        }
    }

    private fun createMediaUploadService(): HttpMediaUploadService = HttpMediaUploadService(
        endpoint = URL("$backendBaseUrl/media/upload"),
        accessToken = tokenAccessor
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

    private val batchMediaViewModel: BatchMediaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = StreamMediaRepository(
                    source = AndroidMediaSource(applicationContext.contentResolver),
                    service = createMediaUploadService()
                )
                return BatchMediaViewModel(repository) as T
            }
        }
    }

    private val callViewModel: CallViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CallViewModel(signalSender = callSignalSender) as T
            }
        }
    }

    private val groupCallViewModel: GroupCallViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GroupCallViewModel(signalSender = callSignalSender) as T
            }
        }
    }

    private val voiceMessagePlayback by lazy { AndroidVoicePlayback(applicationContext) }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNotificationConversationId(intent)?.let {
            pendingNotificationConversationId = it
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationManager.ensureChannels()
        extractNotificationConversationId(intent)?.let {
            pendingNotificationConversationId = it
        }

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
                var previousRouteBeforeCall by rememberSaveable { mutableStateOf(SkeletonRoute.CHAT_LIST) }
                val authState by authViewModel.uiState.collectAsState()
                val listState by chatListViewModel.uiState.collectAsState()
                val roomState by chatRoomViewModel.uiState.collectAsState()
                val callState by callViewModel.uiState.collectAsState()
                val groupCallState by groupCallViewModel.uiState.collectAsState()
                val contactsState by contactsViewModel.uiState.collectAsState()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                var isAppForeground by remember { mutableStateOf(true) }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> isAppForeground = true
                            Lifecycle.Event.ON_STOP -> isAppForeground = false
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                var activeWallpaper by remember { mutableStateOf(ChatWallpaper.DEFAULT) }
                var showWallpaperSheet by rememberSaveable { mutableStateOf(false) }
                var showStickerSheet by rememberSaveable { mutableStateOf(false) }
                var pendingCallAction by remember { mutableStateOf<PendingCallAction?>(null) }

                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { }

                val callPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val action = pendingCallAction ?: return@rememberLauncherForActivityResult
                    pendingCallAction = null
                    val currentUserId = authState.authenticatedUser?.user?.id ?: return@rememberLauncherForActivityResult
                    val hasAudio = grants[Manifest.permission.RECORD_AUDIO] == true ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!hasAudio) {
                        Toast.makeText(context, "Cần quyền micro để thực hiện cuộc gọi", Toast.LENGTH_SHORT).show()
                        return@rememberLauncherForActivityResult
                    }
                    previousRouteBeforeCall = currentRoute
                    if (action.isGroup) {
                        val participants = action.initialParticipants + currentUserId
                        groupCallViewModel.joinCall(
                            context = applicationContext,
                            conversationId = action.conversationId,
                            callId = "group-${action.conversationId}",
                            localUserId = currentUserId,
                            media = action.media,
                            initialParticipants = participants
                        )
                        currentRoute = SkeletonRoute.GROUP_CALL
                    } else {
                        val peerId = action.peerUserId
                        if (peerId == null || peerId <= 0L) {
                            Toast.makeText(context, "Không xác định được người nhận cuộc gọi", Toast.LENGTH_SHORT).show()
                        } else {
                            callViewModel.startWebRtcCall(
                                context = applicationContext,
                                conversationId = action.conversationId,
                                calleeId = peerId,
                                media = action.media,
                                peerName = action.title
                            )
                            currentRoute = SkeletonRoute.CALL
                        }
                    }
                }

                val launchCallWithPermissions: (PendingCallAction) -> Unit = { action ->
                    val required = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (action.media == CallMedia.VIDEO) add(Manifest.permission.CAMERA)
                    }
                    val missing = required.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    val currentUserId = authState.authenticatedUser?.user?.id ?: 0L
                    if (missing.isNotEmpty()) {
                        pendingCallAction = action
                        callPermissionLauncher.launch(missing.toTypedArray())
                    } else if (currentUserId > 0L) {
                        previousRouteBeforeCall = currentRoute
                        if (action.isGroup) {
                            groupCallViewModel.joinCall(
                                context = applicationContext,
                                conversationId = action.conversationId,
                                callId = "group-${action.conversationId}",
                                localUserId = currentUserId,
                                media = action.media,
                                initialParticipants = action.initialParticipants + currentUserId
                            )
                            currentRoute = SkeletonRoute.GROUP_CALL
                        } else {
                            val peerId = action.peerUserId
                            if (peerId == null || peerId <= 0L) {
                                Toast.makeText(context, "Không xác định được người nhận cuộc gọi", Toast.LENGTH_SHORT).show()
                            } else {
                                callViewModel.startWebRtcCall(
                                    context = applicationContext,
                                    conversationId = action.conversationId,
                                    calleeId = peerId,
                                    media = action.media,
                                    peerName = action.title
                                )
                                currentRoute = SkeletonRoute.CALL
                            }
                        }
                    }
                }

                // Sync room wallpaper when opening a conversation
                LaunchedEffect(roomState.conversationId) {
                    activeWallpaper = if (roomState.conversationId > 0L) {
                        wallpaperPreferences.get(roomState.conversationId)
                    } else {
                        ChatWallpaper.DEFAULT
                    }
                    if (roomState.conversationId > 0L) {
                        notificationManager.cancelConversationNotification(roomState.conversationId)
                    }
                }

                // Auth session setup + push token registration + runtime notification permission
                LaunchedEffect(authState.authenticatedUser?.sessionId) {
                    currentRoute = SkeletonRoute.CHAT_LIST
                    val authenticated = authState.authenticatedUser
                    if (authenticated == null) {
                        cachedAccessToken = null
                        chatListViewModel.setActiveConversation(null)
                        chatRoomViewModel.clearActiveConversation()
                        chatTransport.disconnect()
                    } else {
                        chatListViewModel.setCurrentUserId(authenticated.user.id)
                        groupMembersViewModel.setCurrentUserId(authenticated.user.id)
                        val token = authFeature.tokenProvider.accessToken()
                        cachedAccessToken = token
                        if (!token.isNullOrBlank()) {
                            chatTransport.connect(toWebSocketUrl(backendBaseUrl), token)
                            chatListViewModel.loadConversations(backendBaseUrl, token)
                            contactsViewModel.loadConnections()
                            settingsScope.launch(Dispatchers.IO) {
                                runCatching { devicePushRegistrar.register(enabled = true) }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Feed friend list into GroupMembersViewModel for adding members
                LaunchedEffect(contactsState.friends) {
                    val mapped = contactsState.friends.map {
                        GroupMemberUiModel(
                            userId = it.id,
                            displayName = it.displayName,
                            avatarUrl = it.avatarUrl
                        )
                    }
                    createGroupViewModel.setContacts(mapped)
                    groupMembersViewModel.setAvailableFriends(mapped)
                }

                // Global incoming WebSocket listener for system notifications & call signaling
                LaunchedEffect(authState.authenticatedUser?.sessionId) {
                    val currentUser = authState.authenticatedUser?.user ?: return@LaunchedEffect
                    chatTransport.events.collect { event ->
                        when (event) {
                            is ChatServerEvent.MessageCreated -> {
                                val inThisRoom = isAppForeground &&
                                    currentRoute == SkeletonRoute.CHAT_ROOM &&
                                    roomState.conversationId == event.conversationId
                                if (event.senderId != currentUser.id && !inThisRoom) {
                                    val convTitle = listState.conversations
                                        .firstOrNull { it.id == event.conversationId }
                                        ?.title
                                        ?: "Cuộc trò chuyện #${event.conversationId}"
                                    val preview = when (event.type) {
                                        MessageType.IMAGE -> "Đã gửi một hình ảnh"
                                        MessageType.VIDEO -> "Đã gửi một video"
                                        MessageType.VOICE -> "Đã gửi tin nhắn thoại"
                                        MessageType.AUDIO -> "Đã gửi tệp âm thanh"
                                        MessageType.FILE -> "Đã gửi một tệp"
                                        MessageType.STICKER -> "Đã gửi nhãn dán ${event.content}"
                                        MessageType.LOCATION -> "Đã chia sẻ vị trí"
                                        else -> event.content
                                    }
                                    notificationManager.showMessageNotification(
                                        conversationId = event.conversationId,
                                        senderName = convTitle,
                                        messagePreview = preview
                                    )
                                }
                            }

                            is ChatServerEvent.CallSignal -> {
                                if (event.fromUserId != currentUser.id &&
                                    (event.targetUserId == null || event.targetUserId == currentUser.id)
                                ) {
                                    val isGroupSignal = event.signalType.equals(CallSignalTypes.GROUP_JOIN, ignoreCase = true) ||
                                        event.signalType.equals(CallSignalTypes.GROUP_LEAVE, ignoreCase = true) ||
                                        event.callId.startsWith("group-")
                                    if (isGroupSignal) {
                                        groupCallViewModel.onIncomingSignal(
                                            context = applicationContext,
                                            conversationId = event.conversationId,
                                            callId = event.callId,
                                            signalType = event.signalType,
                                            fromUserId = event.fromUserId,
                                            targetUserId = event.targetUserId,
                                            sdpOrCandidate = event.sdpOrCandidate
                                        )
                                        if (event.signalType.equals(CallSignalTypes.GROUP_JOIN, ignoreCase = true) &&
                                            groupCallState.phase == GroupCallPhase.IDLE &&
                                            currentRoute != SkeletonRoute.GROUP_CALL
                                        ) {
                                            val title = listState.conversations
                                                .firstOrNull { it.id == event.conversationId }
                                                ?.title
                                                ?: "Nhóm #${event.conversationId}"
                                            notificationManager.showIncomingCallNotification(
                                                callId = event.callId,
                                                callerName = title,
                                                conversationId = event.conversationId,
                                                isVideo = true
                                            )
                                        }
                                    } else {
                                        val wasIdle = callViewModel.uiState.value.phase == CallPhase.IDLE
                                        callViewModel.onIncomingSignal(
                                            context = applicationContext,
                                            conversationId = event.conversationId,
                                            callId = event.callId,
                                            signalType = event.signalType,
                                            fromUserId = event.fromUserId,
                                            sdpOrCandidate = event.sdpOrCandidate
                                        )
                                        val nextState = callViewModel.uiState.value
                                        if (wasIdle && nextState.phase == CallPhase.INCOMING_RINGING) {
                                            val callerTitle = listState.conversations
                                                .firstOrNull { it.id == event.conversationId }
                                                ?.title
                                                ?: "Người dùng #${event.fromUserId}"
                                            if (!isAppForeground) {
                                                notificationManager.showIncomingCallNotification(
                                                    callId = event.callId,
                                                    callerName = callerTitle,
                                                    conversationId = event.conversationId,
                                                    isVideo = nextState.media == CallMedia.VIDEO
                                                )
                                            }
                                            if (currentRoute != SkeletonRoute.CALL) {
                                                previousRouteBeforeCall = currentRoute
                                                currentRoute = SkeletonRoute.CALL
                                            }
                                        }
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                }

                // Deep-link from tapped system notification
                LaunchedEffect(pendingNotificationConversationId, listState.conversations, authState.authenticatedUser?.sessionId) {
                    val targetConvId = pendingNotificationConversationId
                    val currentUser = authState.authenticatedUser?.user ?: return@LaunchedEffect
                    val match = listState.conversations.firstOrNull { it.id == targetConvId }
                    if (targetConvId > 0L && match != null) {
                        pendingNotificationConversationId = 0L
                        val latestPresence = match.peerUserId?.let { chatListViewModel.presenceForPeer(it) }
                        chatListViewModel.setCurrentUserId(currentUser.id)
                        chatListViewModel.markConversationRead(match.id)
                        chatListViewModel.setActiveConversation(match.id)
                        chatRoomViewModel.openConversation(
                            conversationId = match.id,
                            title = match.title,
                            isGroup = match.type == ConversationType.GROUP,
                            currentUserId = currentUser.id,
                            peerUserId = match.peerUserId,
                            initialPeerOnline = latestPresence?.first ?: match.isOnline,
                            initialPeerLastSeenAt = latestPresence?.second ?: match.peerLastSeenAt
                        )
                        currentRoute = SkeletonRoute.CHAT_ROOM
                    }
                }

                if (authState.authenticatedUser == null) {
                    AuthEntryScreen(viewModel = authViewModel)
                } else {
                    BackHandler(enabled = currentRoute != SkeletonRoute.CHAT_LIST) {
                        when (currentRoute) {
                            SkeletonRoute.CALL -> {
                                callViewModel.end()
                                currentRoute = previousRouteBeforeCall
                            }
                            SkeletonRoute.GROUP_CALL -> {
                                groupCallViewModel.leaveCall()
                                currentRoute = previousRouteBeforeCall
                            }
                            SkeletonRoute.MEDIA_PICKER,
                            SkeletonRoute.BATCH_MEDIA,
                            SkeletonRoute.LOCATION_SHARE,
                            SkeletonRoute.GROUP_MEMBERS -> currentRoute = SkeletonRoute.CHAT_ROOM
                            SkeletonRoute.CHAT_ROOM -> {
                                voiceViewModel.clear()
                                voiceMessagePlayback.stop()
                                chatListViewModel.setActiveConversation(null)
                                chatRoomViewModel.clearActiveConversation()
                                currentRoute = SkeletonRoute.CHAT_LIST
                            }
                            SkeletonRoute.CREATE_GROUP,
                            SkeletonRoute.CONTACTS,
                            SkeletonRoute.PROFILE_SETTINGS -> currentRoute = SkeletonRoute.CHAT_LIST
                            SkeletonRoute.CHAT_LIST -> Unit
                        }
                    }

                    when (currentRoute) {
                        SkeletonRoute.CHAT_LIST -> {
                            ChatListScreen(
                                uiState = listState,
                                onSearchQueryChange = chatListViewModel::onSearchQueryChanged,
                                onConversationClick = { conversation ->
                                    val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                                    val latestPresence = conversation.peerUserId?.let {
                                        chatListViewModel.presenceForPeer(it)
                                    }
                                    chatListViewModel.setCurrentUserId(currentUserId)
                                    chatListViewModel.markConversationRead(conversation.id)
                                    chatListViewModel.setActiveConversation(conversation.id)
                                    chatRoomViewModel.openConversation(
                                        conversationId = conversation.id,
                                        title = conversation.title,
                                        isGroup = conversation.type == ConversationType.GROUP,
                                        currentUserId = currentUserId,
                                        peerUserId = conversation.peerUserId,
                                        initialPeerOnline = latestPresence?.first ?: conversation.isOnline,
                                        initialPeerLastSeenAt = latestPresence?.second ?: conversation.peerLastSeenAt
                                    )
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                },
                                onCreateGroupClick = {
                                    contactsViewModel.loadConnections()
                                    currentRoute = SkeletonRoute.CREATE_GROUP
                                },
                                onContactsClick = { currentRoute = SkeletonRoute.CONTACTS },
                                onProfileClick = { currentRoute = SkeletonRoute.PROFILE_SETTINGS },
                                currentUserAvatarUrl = authState.authenticatedUser?.user?.avatarUrl,
                                currentUserDisplayName = authState.authenticatedUser?.user?.displayName.orEmpty(),
                                avatarBaseUrl = backendBaseUrl,
                                onTogglePinClick = { conversation ->
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        chatListViewModel.togglePinConversation(backendBaseUrl, token, conversation.id)
                                    }
                                },
                                onSearchMessagesClick = { query ->
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        chatListViewModel.searchGlobalMessages(backendBaseUrl, token, query)
                                    }
                                },
                                onSearchMessageResultClick = { matchedMsg ->
                                    val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                                    val conv = listState.conversations.firstOrNull { it.id == matchedMsg.conversationId }
                                    chatListViewModel.setCurrentUserId(currentUserId)
                                    chatListViewModel.markConversationRead(matchedMsg.conversationId)
                                    chatListViewModel.setActiveConversation(matchedMsg.conversationId)
                                    chatRoomViewModel.openConversation(
                                        conversationId = matchedMsg.conversationId,
                                        title = conv?.title ?: "Chat #${matchedMsg.conversationId}",
                                        isGroup = conv?.type == ConversationType.GROUP,
                                        currentUserId = currentUserId,
                                        peerUserId = conv?.peerUserId,
                                        initialPeerOnline = conv?.isOnline ?: false,
                                        initialPeerLastSeenAt = conv?.peerLastSeenAt
                                    )
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                }
                            )
                        }

                        SkeletonRoute.CHAT_ROOM -> {
                            val voiceState by voiceViewModel.uiState.collectAsState()
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
                            var playingVoiceMessageId by remember { mutableStateOf<String?>(null) }
                            var playingVoicePositionMs by remember { mutableStateOf(0L) }
                            var playingVoiceDurationMs by remember { mutableStateOf(0L) }
                            var voiceStatusMessage by remember { mutableStateOf<String?>(null) }
                            val noAudioMessage = stringResource(R.string.chat_voice_no_audio)
                            val playFailedMessage = stringResource(R.string.chat_voice_play_failed)
                            LaunchedEffect(playingVoiceMessageId) {
                                if (playingVoiceMessageId == null) {
                                    playingVoicePositionMs = 0L
                                    playingVoiceDurationMs = 0L
                                    return@LaunchedEffect
                                }
                                while (isActive && playingVoiceMessageId != null) {
                                    playingVoicePositionMs = voiceMessagePlayback.currentPositionMs()
                                    val duration = voiceMessagePlayback.totalDurationMs()
                                    if (duration > 0L) {
                                        playingVoiceDurationMs = duration
                                    }
                                    delay(150L)
                                }
                            }

                            Box(modifier = Modifier.fillMaxSize()) {
                                ChatRoomScreen(
                                    uiState = roomState,
                                    onBackClick = {
                                        voiceViewModel.clear()
                                        voiceMessagePlayback.stop()
                                        playingVoiceMessageId = null
                                        playingVoicePositionMs = 0L
                                        playingVoiceDurationMs = 0L
                                        voiceStatusMessage = null
                                        chatListViewModel.setActiveConversation(null)
                                        chatRoomViewModel.clearActiveConversation()
                                        currentRoute = SkeletonRoute.CHAT_LIST
                                    },
                                    onDraftChange = chatRoomViewModel::onDraftChanged,
                                    onSendClick = {
                                        if (roomState.editingMessage != null) {
                                            settingsScope.launch {
                                                val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                                cachedAccessToken = token
                                                chatRoomViewModel.submitEdit(backendBaseUrl, token)
                                            }
                                        } else {
                                            val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                            chatRoomViewModel.queueOutgoingTextMessage(
                                                currentUserId = currentUserId,
                                                createdAtLabel = nowLabel,
                                                isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                            )
                                        }
                                    },
                                    onAttachMediaClick = {
                                        mediaViewModel.clear()
                                        currentRoute = SkeletonRoute.MEDIA_PICKER
                                    },
                                    voiceState = voiceState,
                                    hasMicrophonePermission = hasMicPermission,
                                    onRequestMicrophonePermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    onStartVoiceRecording = {
                                        voiceStatusMessage = null
                                        voiceMessagePlayback.stop()
                                        playingVoiceMessageId = null
                                        voiceViewModel.startRecording()
                                    },
                                    onStopVoiceRecording = voiceViewModel::stopRecording,
                                    onCancelVoiceRecording = voiceViewModel::interrupt,
                                    onToggleVoicePlayback = voiceViewModel::togglePlayback,
                                    onSendVoice = { voiceViewModel.upload(roomState.conversationId) },
                                    onDiscardVoice = voiceViewModel::clear,
                                    playingVoiceMessageId = playingVoiceMessageId,
                                    playingVoicePositionMs = playingVoicePositionMs,
                                    playingVoiceDurationMs = playingVoiceDurationMs,
                                    voiceStatusMessage = voiceStatusMessage,
                                    onPlayVoiceMessage = { message ->
                                        voiceStatusMessage = null
                                        if (playingVoiceMessageId == message.localId) {
                                            voiceMessagePlayback.stop()
                                            playingVoiceMessageId = null
                                        } else {
                                            val mediaId = message.mediaRef ?: extractVoiceMediaId(message.content)
                                            if (mediaId == null) {
                                                voiceStatusMessage = noAudioMessage
                                            } else {
                                                settingsScope.launch {
                                                    val audioFile = downloadVoiceFileIfNeeded(mediaId)
                                                    if (audioFile == null || audioFile.length() <= 0L) {
                                                        voiceStatusMessage = playFailedMessage
                                                    } else {
                                                        runCatching {
                                                            playingVoiceMessageId = message.localId
                                                            playingVoicePositionMs = 0L
                                                            voiceMessagePlayback.play(audioFile.absolutePath) {
                                                                playingVoiceMessageId = null
                                                                playingVoicePositionMs = 0L
                                                                playingVoiceDurationMs = 0L
                                                            }
                                                        }.onFailure {
                                                            playingVoiceMessageId = null
                                                            voiceStatusMessage = playFailedMessage
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    availableConversations = listState.conversations,
                                    wallpaperColorArgb = activeWallpaper.backgroundColorHex,
                                    onToggleReaction = { messageId, emoji ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.toggleReaction(backendBaseUrl, token, messageId, emoji)
                                        }
                                    },
                                    onStartReply = chatRoomViewModel::setReplyingTo,
                                    onCancelReply = { chatRoomViewModel.setReplyingTo(null) },
                                    onStartEdit = chatRoomViewModel::startEditing,
                                    onCancelEdit = { chatRoomViewModel.startEditing(null) },
                                    onSubmitEdit = {
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.submitEdit(backendBaseUrl, token)
                                        }
                                    },
                                    onForwardMessage = { sourceMsgId, targetConvId ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.forwardMessage(
                                                baseUrl = backendBaseUrl,
                                                accessToken = token,
                                                sourceMessageId = sourceMsgId,
                                                targetConversationId = targetConvId
                                            ) {
                                                Toast.makeText(context, "Đã chuyển tiếp tin nhắn", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onTogglePinMessage = { messageId, currentlyPinned ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            if (currentlyPinned) {
                                                chatRoomViewModel.unpinMessage(backendBaseUrl, token, messageId)
                                            } else {
                                                chatRoomViewModel.pinMessage(backendBaseUrl, token, messageId)
                                            }
                                        }
                                    },
                                    onRecallMessage = { messageId ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.recallMessage(backendBaseUrl, token, messageId)
                                        }
                                    },
                                    onDeleteMessage = { messageId ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.deleteMessage(backendBaseUrl, token, messageId)
                                        }
                                    },
                                    onRetryMessage = chatRoomViewModel::retryMessage,
                                    onCancelFailedMessage = chatRoomViewModel::cancelFailedMessage,
                                    onSearchQueryChange = { query ->
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.searchInConversation(backendBaseUrl, token, query)
                                        }
                                    },
                                    onClearSearch = chatRoomViewModel::clearSearch,
                                    onLoadOlderHistory = {
                                        settingsScope.launch {
                                            val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                            cachedAccessToken = token
                                            chatRoomViewModel.loadOlderHistory(backendBaseUrl, token, currentUserId)
                                        }
                                    },
                                    onOpenMediaClick = { message ->
                                        openAttachmentMessage(message, settingsScope, context) { errorMsg ->
                                            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onOpenLocationClick = { message ->
                                        val attachment = parseLocationAttachment(message)
                                        if (attachment == null) {
                                            Toast.makeText(context, "Không có tọa độ vị trí hợp lệ", Toast.LENGTH_SHORT).show()
                                        } else {
                                            runCatching {
                                                context.startActivity(attachment.toMapIntent())
                                            }.onFailure {
                                                Toast.makeText(context, "Không tìm thấy ứng dụng bản đồ", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    mediaPreviewLoader = { mediaId, mod ->
                                        AuthenticatedImagePreview(mediaId = mediaId, modifier = mod)
                                    },
                                    onAttachBatchMediaClick = {
                                        batchMediaViewModel.clear()
                                        currentRoute = SkeletonRoute.BATCH_MEDIA
                                    },
                                    onAttachLocationClick = { currentRoute = SkeletonRoute.LOCATION_SHARE },
                                    onOpenStickerClick = { showStickerSheet = true },
                                    onVoiceCallClick = {
                                        val resolvedPeerId = roomState.peerUserId
                                            ?: listState.conversations.firstOrNull { it.id == roomState.conversationId }?.peerUserId
                                        launchCallWithPermissions(
                                            PendingCallAction(
                                                conversationId = roomState.conversationId,
                                                isGroup = roomState.isGroup,
                                                media = CallMedia.AUDIO,
                                                peerUserId = resolvedPeerId,
                                                title = roomState.conversationTitle,
                                                initialParticipants = roomState.memberNamesByUserId.keys
                                            )
                                        )
                                    },
                                    onVideoCallClick = {
                                        val resolvedPeerId = roomState.peerUserId
                                            ?: listState.conversations.firstOrNull { it.id == roomState.conversationId }?.peerUserId
                                        launchCallWithPermissions(
                                            PendingCallAction(
                                                conversationId = roomState.conversationId,
                                                isGroup = roomState.isGroup,
                                                media = CallMedia.VIDEO,
                                                peerUserId = resolvedPeerId,
                                                title = roomState.conversationTitle,
                                                initialParticipants = roomState.memberNamesByUserId.keys
                                            )
                                        )
                                    },
                                    onGroupMembersClick = {
                                        if (roomState.isGroup && roomState.conversationId > 0L) {
                                            settingsScope.launch {
                                                val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                                cachedAccessToken = token
                                                groupMembersViewModel.setCurrentUserId(currentUserId)
                                                groupMembersViewModel.loadMembers(backendBaseUrl, token, roomState.conversationId)
                                            }
                                            currentRoute = SkeletonRoute.GROUP_MEMBERS
                                        }
                                    },
                                    onWallpaperClick = { showWallpaperSheet = true }
                                )

                                if (showStickerSheet) {
                                    ModalBottomSheet(onDismissRequest = { showStickerSheet = false }) {
                                        StickerPickerScreen(
                                            onStickerSelected = { stickerAttachment ->
                                                val found = vn.edu.hcmute.androidchat.feature.media.sticker.StickerCatalog
                                                    .find(stickerAttachment.stickerId)
                                                val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                                chatRoomViewModel.queueOutgoingStickerMessage(
                                                    currentUserId = currentUserId,
                                                    stickerId = stickerAttachment.stickerId,
                                                    unicode = found?.unicode ?: stickerAttachment.stickerId,
                                                    label = found?.label.orEmpty(),
                                                    createdAtLabel = nowLabel,
                                                    isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                                )
                                                showStickerSheet = false
                                            },
                                            onClose = { showStickerSheet = false }
                                        )
                                    }
                                }

                                if (showWallpaperSheet) {
                                    ModalBottomSheet(onDismissRequest = { showWallpaperSheet = false }) {
                                        WallpaperPickerScreen(
                                            selected = activeWallpaper,
                                            onSelected = { picked ->
                                                if (roomState.conversationId > 0L) {
                                                    wallpaperPreferences.set(roomState.conversationId, picked)
                                                }
                                                activeWallpaper = picked
                                                showWallpaperSheet = false
                                            },
                                            onDismiss = { showWallpaperSheet = false }
                                        )
                                    }
                                }
                            }
                        }

                        SkeletonRoute.CREATE_GROUP -> {
                            val groupState by createGroupViewModel.uiState.collectAsState()
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
                                            chatListViewModel.setCurrentUserId(currentUserId)
                                            chatListViewModel.loadConversations(backendBaseUrl, token)
                                            chatListViewModel.markConversationRead(created.id)
                                            chatListViewModel.setActiveConversation(created.id)
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

                        SkeletonRoute.GROUP_MEMBERS -> {
                            val membersState by groupMembersViewModel.uiState.collectAsState()
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            LaunchedEffect(membersState.hasLeftGroup) {
                                if (membersState.hasLeftGroup) {
                                    val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken
                                    if (!token.isNullOrBlank()) {
                                        chatListViewModel.loadConversations(backendBaseUrl, token)
                                    }
                                    chatListViewModel.setActiveConversation(null)
                                    chatRoomViewModel.clearActiveConversation()
                                    currentRoute = SkeletonRoute.CHAT_LIST
                                }
                            }
                            GroupMembersScreen(
                                uiState = membersState,
                                groupTitle = roomState.conversationTitle,
                                onBackClick = { currentRoute = SkeletonRoute.CHAT_ROOM },
                                onAddMembersClick = { userIds ->
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        groupMembersViewModel.addMembers(
                                            backendBaseUrl,
                                            token,
                                            roomState.conversationId,
                                            userIds
                                        )
                                    }
                                },
                                onUpdateRoleClick = { targetUserId, newRole ->
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        groupMembersViewModel.updateRole(
                                            backendBaseUrl,
                                            token,
                                            roomState.conversationId,
                                            targetUserId,
                                            newRole
                                        )
                                    }
                                },
                                onRemoveMemberClick = { targetUserId ->
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        groupMembersViewModel.removeMember(
                                            backendBaseUrl,
                                            token,
                                            roomState.conversationId,
                                            targetUserId
                                        )
                                    }
                                },
                                onLeaveGroupClick = {
                                    settingsScope.launch {
                                        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken ?: return@launch
                                        cachedAccessToken = token
                                        groupMembersViewModel.leaveGroup(
                                            baseUrl = backendBaseUrl,
                                            accessToken = token,
                                            conversationId = roomState.conversationId,
                                            currentUserId = currentUserId
                                        ) {
                                            chatListViewModel.loadConversations(backendBaseUrl, token)
                                            chatListViewModel.setActiveConversation(null)
                                            chatRoomViewModel.clearActiveConversation()
                                            currentRoute = SkeletonRoute.CHAT_LIST
                                        }
                                    }
                                },
                                onAddMemberQueryChange = groupMembersViewModel::onAddMemberQueryChanged,
                                onClearError = groupMembersViewModel::clearError
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
                            onLogout = {
                                settingsScope.launch(Dispatchers.IO) {
                                    runCatching { devicePushRegistrar.unregister() }
                                }
                                authViewModel.logout()
                            },
                            onBackClick = { currentRoute = SkeletonRoute.CHAT_LIST },
                            themePreference = themePreference,
                            onThemePreferenceChanged = { preference ->
                                settingsScope.launch { appSettingsStore.setThemePreference(preference) }
                            },
                            languagePreference = languagePreference,
                            onLanguagePreferenceChanged = { preference ->
                                settingsScope.launch { appSettingsStore.setLanguagePreference(preference) }
                            },
                            avatarBaseUrl = backendBaseUrl,
                            onUploadAvatar = authViewModel::uploadAvatar
                        )

                        SkeletonRoute.MEDIA_PICKER -> {
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            MediaPickerScreen(
                                viewModel = mediaViewModel,
                                conversationId = roomState.conversationId,
                                onBackClick = { currentRoute = SkeletonRoute.CHAT_ROOM },
                                onMediaUploaded = { uploaded, selected ->
                                    val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    chatRoomViewModel.queueOutgoingMediaMessage(
                                        currentUserId = currentUserId,
                                        mediaId = uploaded.mediaId,
                                        fileName = uploaded.fileName.ifBlank { selected.name },
                                        mimeType = uploaded.mimeType,
                                        byteSize = uploaded.byteSize,
                                        createdAtLabel = nowLabel,
                                        isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                    )
                                    mediaViewModel.clear()
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                }
                            )
                        }

                        SkeletonRoute.BATCH_MEDIA -> {
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            BatchMediaScreen(
                                viewModel = batchMediaViewModel,
                                conversationId = roomState.conversationId,
                                onBackClick = { currentRoute = SkeletonRoute.CHAT_ROOM },
                                onSendUploaded = { items ->
                                    val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val connected = roomState.connectionState == WsConnectionState.CONNECTED
                                    items.forEach { (uploaded, selected) ->
                                        chatRoomViewModel.queueOutgoingMediaMessage(
                                            currentUserId = currentUserId,
                                            mediaId = uploaded.mediaId,
                                            fileName = uploaded.fileName.ifBlank { selected.name },
                                            mimeType = uploaded.mimeType,
                                            byteSize = uploaded.byteSize,
                                            createdAtLabel = nowLabel,
                                            isTransportConnected = connected
                                        )
                                    }
                                    batchMediaViewModel.clear()
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                }
                            )
                        }

                        SkeletonRoute.LOCATION_SHARE -> {
                            val currentUserId = requireNotNull(authState.authenticatedUser).user.id
                            LocationShareScreen(
                                context = context,
                                onBackClick = { currentRoute = SkeletonRoute.CHAT_ROOM },
                                onLocationSelected = { loc ->
                                    val nowLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    chatRoomViewModel.queueOutgoingLocationMessage(
                                        currentUserId = currentUserId,
                                        latitude = loc.latitude,
                                        longitude = loc.longitude,
                                        label = loc.label,
                                        createdAtLabel = nowLabel,
                                        isTransportConnected = roomState.connectionState == WsConnectionState.CONNECTED
                                    )
                                    currentRoute = SkeletonRoute.CHAT_ROOM
                                }
                            )
                        }

                        SkeletonRoute.CALL -> {
                            CallScreen(
                                viewModel = callViewModel,
                                onCallEnded = {
                                    currentRoute = previousRouteBeforeCall
                                }
                            )
                        }

                        SkeletonRoute.GROUP_CALL -> {
                            GroupCallScreen(
                                viewModel = groupCallViewModel,
                                onBackClick = {
                                    groupCallViewModel.leaveCall()
                                    currentRoute = previousRouteBeforeCall
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            voiceMessagePlayback.stop()
            chatTransport.disconnect()
        }
    }

    @Composable
    private fun AuthenticatedImagePreview(
        mediaId: Long,
        modifier: Modifier = Modifier
    ) {
        val imageBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = mediaId) {
            value = withContext(Dispatchers.IO) {
                val file = downloadMediaFileIfNeeded(
                    mediaId = mediaId,
                    suggestedFileName = "img-$mediaId.jpg",
                    mimeType = "image/jpeg"
                ) ?: return@withContext null
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                    val sample = (maxDim / 800).coerceAtLeast(1)
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)?.asImageBitmap()
                }.getOrNull()
            }
        }
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Hình ảnh",
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        } else {
            Box(
                modifier = modifier.background(Color(0xFFE2E8F0)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }

    private fun openAttachmentMessage(
        message: MessageUiModel,
        scope: kotlinx.coroutines.CoroutineScope,
        context: android.content.Context,
        onError: (String) -> Unit
    ) {
        val meta = runCatching {
            message.metadataJson?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        }.getOrNull()
        val mediaId = message.mediaRef
            ?: meta?.optLong("mediaId", -1L)?.takeIf { it > 0L }
            ?: extractVoiceMediaId(message.content)
        if (mediaId == null) {
            onError("Không tìm thấy mã tệp đính kèm")
            return
        }
        val fileName = meta?.optString("fileName").orEmpty().ifBlank {
            message.content.ifBlank { "attachment-$mediaId" }
        }
        val mimeType = meta?.optString("mimeType").orEmpty().ifBlank {
            when (message.type) {
                MessageType.IMAGE -> "image/jpeg"
                MessageType.VIDEO -> "video/mp4"
                MessageType.AUDIO, MessageType.VOICE -> "audio/mp4"
                else -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase(Locale.US))
                    ?: "application/octet-stream"
            }
        }
        scope.launch {
            val file = downloadMediaFileIfNeeded(mediaId, fileName, mimeType)
            if (file == null || file.length() <= 0L) {
                onError("Không thể tải tệp từ máy chủ")
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(viewIntent) }.onFailure {
                onError("Không có ứng dụng hỗ trợ mở tệp $mimeType")
            }
        }
    }

    private fun parseLocationAttachment(message: MessageUiModel): LocationAttachment? {
        val meta = runCatching {
            message.metadataJson?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        }.getOrNull()
        if (meta != null && meta.has("latitude") && meta.has("longitude")) {
            val lat = meta.optDouble("latitude", Double.NaN)
            val lng = meta.optDouble("longitude", Double.NaN)
            val label = meta.optString("label").takeIf { it.isNotBlank() && it != "null" }
            if (!lat.isNaN() && !lng.isNaN()) {
                return LocationAttachment(lat, lng, label)
            }
        }
        val parts = message.content.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size >= 2 && parts[0] != null && parts[1] != null) {
            return LocationAttachment(parts[0]!!, parts[1]!!, null)
        }
        return null
    }

    private suspend fun downloadVoiceFileIfNeeded(mediaId: Long): File? = withContext(Dispatchers.IO) {
        val voiceDir = File(cacheDir, "voice").apply { mkdirs() }
        val target = File(voiceDir, "msg-$mediaId.m4a")
        downloadToTargetFile(mediaId, target)
    }

    private suspend fun downloadMediaFileIfNeeded(
        mediaId: Long,
        suggestedFileName: String,
        mimeType: String
    ): File? = withContext(Dispatchers.IO) {
        val mediaDir = File(cacheDir, "media_cache").apply { mkdirs() }
        val cleanBaseName = suggestedFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "media-$mediaId" }
        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val finalName = if ('.' in cleanBaseName || extFromMime.isNullOrBlank()) {
            "$mediaId-$cleanBaseName"
        } else {
            "$mediaId-$cleanBaseName.$extFromMime"
        }
        val target = File(mediaDir, finalName)
        downloadToTargetFile(mediaId, target)
    }

    private suspend fun downloadToTargetFile(mediaId: Long, target: File): File? = withContext(Dispatchers.IO) {
        if (target.isFile && target.length() > 0L) return@withContext target

        val token = authFeature.tokenProvider.accessToken() ?: cachedAccessToken.orEmpty()
        if (token.isBlank()) return@withContext null

        val connection = (URL("${backendBaseUrl.trimEnd('/')}/media/$mediaId/download")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val temp = File(target.parentFile, "${target.name}.tmp")
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return@withContext null
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            target
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractNotificationConversationId(intent: Intent?): Long? {
        val value = intent?.getLongExtra(ChatNotificationManager.EXTRA_CONVERSATION_ID, 0L) ?: 0L
        return value.takeIf { it > 0L }
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
