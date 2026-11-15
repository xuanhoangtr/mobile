package vn.edu.hcmute.androidchat.backend.media

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.File

interface PushNotificationSender {
    suspend fun sendToUsers(
        userIds: Set<Long>,
        title: String,
        body: String,
        data: Map<String, String>
    )
}

object NoopPushNotificationSender : PushNotificationSender {
    override suspend fun sendToUsers(
        userIds: Set<Long>,
        title: String,
        body: String,
        data: Map<String, String>
    ) = Unit
}

/**
 * Dispatches FCM data notifications via Firebase Admin SDK when credentials exist.
 * Safe no-op in unit tests or when `FIREBASE_CREDENTIALS_PATH` / `GOOGLE_APPLICATION_CREDENTIALS` is unset.
 */
class FirebaseAdminPushSender(
    private val registry: PushTokenRegistry,
    private val credentialsPath: String?
) : PushNotificationSender {

    private val logger = LoggerFactory.getLogger("FcmPushService")

    @Volatile
    private var initializedApp: FirebaseApp? = null

    @Volatile
    private var initAttempted: Boolean = false

    fun isConfigured(): Boolean = getOrInitMessaging() != null

    override suspend fun sendToUsers(
        userIds: Set<Long>,
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        if (userIds.isEmpty()) return
        val messaging = getOrInitMessaging() ?: return
        val deviceTokens = registry.activeTokensForUsers(userIds)
            .map { it.token }
            .filter { it.isNotBlank() && !it.startsWith("android-local-") }
            .distinct()
        if (deviceTokens.isEmpty()) return

        val payloadData = buildMap {
            putAll(data)
            put("title", title)
            put("body", body)
        }

        withContext(Dispatchers.IO) {
            deviceTokens.chunked(500).forEach { batch ->
                val message = MulticastMessage.builder()
                    .addAllTokens(batch)
                    .putAllData(payloadData)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build()
                    )
                    .build()

                val response = runCatching { messaging.sendEachForMulticast(message) }
                    .onFailure { logger.warn("FCM multicast send failed: {}", it.message) }
                    .getOrNull() ?: return@forEach

                response.responses.forEachIndexed { idx, sendResp ->
                    if (!sendResp.isSuccessful) {
                        val code = sendResp.exception?.messagingErrorCode
                        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                            registry.removeTokenByValue(batch[idx])
                        }
                    }
                }
            }
        }
    }

    private fun getOrInitMessaging(): FirebaseMessaging? {
        val existing = initializedApp
        if (existing != null) {
            return runCatching { FirebaseMessaging.getInstance(existing) }.getOrNull()
        }
        if (initAttempted) return null
        synchronized(this) {
            initializedApp?.let { app ->
                return runCatching { FirebaseMessaging.getInstance(app) }.getOrNull()
            }
            if (initAttempted) return null
            initAttempted = true

            val path = credentialsPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val credFile = File(path)
            if (!credFile.isFile || !credFile.canRead()) {
                logger.info("FCM disabled: credentials file not found at {}", path)
                return null
            }

            return runCatching {
                val appName = "android-chat-fcm"
                val app = FirebaseApp.getApps().firstOrNull { it.name == appName }
                    ?: credFile.inputStream().use { stream ->
                        val options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(stream))
                            .build()
                        FirebaseApp.initializeApp(options, appName)
                    }
                initializedApp = app
                logger.info("FCM Admin SDK initialized from {}", credFile.name)
                FirebaseMessaging.getInstance(app)
            }.onFailure { err ->
                logger.warn("Failed to initialize Firebase Admin SDK: {}", err.message)
            }.getOrNull()
        }
    }
}

/**
 * Translates offline chat/call server events into FCM push notifications.
 */
suspend fun dispatchOfflinePushEvent(
    sender: PushNotificationSender,
    offlineUserIds: Set<Long>,
    eventType: String,
    payload: JsonElement
) {
    if (offlineUserIds.isEmpty()) return
    val obj = payload as? JsonObject ?: return

    when (eventType) {
        "message.created" -> {
            val senderId = obj["senderId"]?.jsonPrimitive?.longOrNull
            val targets = if (senderId != null) offlineUserIds - senderId else offlineUserIds
            if (targets.isEmpty()) return

            val conversationId = obj["conversationId"]?.jsonPrimitive?.longOrNull ?: return
            val messageType = obj["type"]?.jsonPrimitive?.content.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val preview = when (messageType.uppercase()) {
                "IMAGE" -> "Đã gửi một hình ảnh"
                "VIDEO" -> "Đã gửi một video"
                "VOICE" -> "Đã gửi tin nhắn thoại"
                "AUDIO" -> "Đã gửi tệp âm thanh"
                "FILE" -> "Đã gửi một tệp"
                "STICKER" -> "Đã gửi nhãn dán $content".trim()
                "LOCATION" -> "Đã chia sẻ vị trí"
                else -> content.ifBlank { "Bạn có tin nhắn mới" }
            }
            sender.sendToUsers(
                userIds = targets,
                title = "Tin nhắn mới",
                body = preview,
                data = mapOf(
                    "type" to "message.created",
                    "conversationId" to conversationId.toString(),
                    "messageType" to messageType
                )
            )
        }

        "call.invite", "call.group.invite" -> {
            val callerId = obj["callerId"]?.jsonPrimitive?.longOrNull
            val targets = if (callerId != null) offlineUserIds - callerId else offlineUserIds
            if (targets.isEmpty()) return

            val callId = obj["callId"]?.jsonPrimitive?.content.orEmpty()
            if (callId.isBlank()) return
            val conversationId = obj["conversationId"]?.jsonPrimitive?.longOrNull
            val media = obj["media"]?.jsonPrimitive?.content ?: "audio"
            val callLabel = if (media.equals("video", ignoreCase = true)) {
                "Cuộc gọi video đến"
            } else {
                "Cuộc gọi thoại đến"
            }
            val data = buildMap {
                put("type", eventType)
                put("callId", callId)
                put("media", media)
                if (conversationId != null && conversationId > 0L) {
                    put("conversationId", conversationId.toString())
                }
            }
            sender.sendToUsers(
                userIds = targets,
                title = callLabel,
                body = "Nhấn để trả lời",
                data = data
            )
        }
    }
}
