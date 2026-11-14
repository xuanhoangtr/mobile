package vn.edu.hcmute.androidchat.feature.media.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class ChatNotificationManager(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Tin nhắn trò chuyện",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo tin nhắn mới"
            enableVibration(true)
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Cuộc gọi đến",
            NotificationManager.IMPORTANCE_MAX
        ).apply {
            description = "Thông báo cuộc gọi thoại và video"
            enableVibration(true)
        }

        manager.createNotificationChannel(messagesChannel)
        manager.createNotificationChannel(callsChannel)
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        conversationId: Long,
        senderName: String,
        messagePreview: String,
        notificationId: Int = conversationId.toInt()
    ): Boolean {
        if (!hasNotificationPermission()) return false
        ensureChannels()

        val pendingIntent = createLaunchPendingIntent(
            requestCode = notificationId,
            conversationId = conversationId,
            callId = null
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderName.ifBlank { "Tin nhắn mới" })
            .setContentText(messagePreview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        conversationId: Long? = null,
        isVideo: Boolean = false,
        notificationId: Int = callId.hashCode()
    ): Boolean {
        if (!hasNotificationPermission()) return false
        ensureChannels()

        val pendingIntent = createLaunchPendingIntent(
            requestCode = notificationId,
            conversationId = conversationId,
            callId = callId
        )

        val callTypeLabel = if (isVideo) "Cuộc gọi video đến" else "Cuộc gọi thoại đến"
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName.ifBlank { callTypeLabel })
            .setContentText(callTypeLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    fun cancelNotification(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun cancelConversationNotification(conversationId: Long) {
        cancelNotification(conversationId.toInt())
    }

    private fun createLaunchPendingIntent(
        requestCode: Int,
        conversationId: Long?,
        callId: String?
    ): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null && conversationId > 0L) {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            if (!callId.isNullOrBlank()) {
                putExtra(EXTRA_CALL_ID, callId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "chat_messages"
        const val CHANNEL_CALLS = "chat_calls"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CALL_ID = "extra_call_id"
    }
}
