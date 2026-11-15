package vn.edu.hcmute.androidchat.feature.media.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Handles Firebase Cloud Messaging token refreshes and background/terminated push payloads.
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val trimmed = token.trim()
        if (trimmed.isBlank()) return

        getSharedPreferences("device_push_registrar", Context.MODE_PRIVATE)
            .edit()
            .putString("last_token", trimmed)
            .apply()

        val activeRegistrar = DevicePushRegistrar.activeInstance
        if (activeRegistrar != null) {
            activeRegistrar.cacheTokenLocally(trimmed)
            serviceScope.launch {
                runCatching { activeRegistrar.registerToken(trimmed, enabled = true) }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val payloadType = data["type"].orEmpty()
        val notifier = ChatNotificationManager(applicationContext)

        if (payloadType == "call.invite" || payloadType == "call.group.invite") {
            val callId = data["callId"].orEmpty()
            if (callId.isBlank()) return
            val callerName = data["title"] ?: message.notification?.title ?: "Cuộc gọi đến"
            val conversationId = data["conversationId"]?.toLongOrNull()
            val isVideo = data["media"].equals("video", ignoreCase = true)
            notifier.showIncomingCallNotification(
                callId = callId,
                callerName = callerName,
                conversationId = conversationId,
                isVideo = isVideo
            )
            return
        }

        val conversationId = data["conversationId"]?.toLongOrNull() ?: 0L
        val title = data["title"] ?: message.notification?.title ?: "Tin nhắn mới"
        val body = data["body"] ?: message.notification?.body ?: "Bạn có tin nhắn mới"
        if (conversationId > 0L) {
            notifier.showMessageNotification(
                conversationId = conversationId,
                senderName = title,
                messagePreview = body
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
