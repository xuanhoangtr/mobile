package vn.edu.hcmute.androidchat.feature.media.push

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import vn.edu.hcmute.androidchat.feature.media.data.PushTokenClient
import java.util.UUID
import kotlin.coroutines.resume

class DevicePushRegistrar private constructor(
    private val prefs: SharedPreferences,
    private val pushClientFactory: () -> PushTokenClient,
    private val fcmTokenProvider: suspend () -> String?
) {
    constructor(
        context: Context,
        pushClientFactory: () -> PushTokenClient
    ) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        pushClientFactory = pushClientFactory,
        fcmTokenProvider = { fetchFirebaseToken(context.applicationContext) }
    ) {
        activeInstance = this
    }

    val deviceId: String
        get() = getOrCreateDeviceId()

    fun currentToken(): String =
        prefs.getString(KEY_LAST_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: buildLocalToken(deviceId)

    suspend fun register(enabled: Boolean = true): String {
        val id = deviceId
        val fcmToken = runCatching { fcmTokenProvider() }.getOrNull()?.takeIf { it.isNotBlank() }
        val resolvedToken = fcmToken
            ?: prefs.getString(KEY_LAST_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: buildLocalToken(id)
        pushClientFactory().register(deviceId = id, token = resolvedToken, enabled = enabled)
        prefs.edit()
            .putString(KEY_LAST_TOKEN, resolvedToken)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        return resolvedToken
    }

    suspend fun registerToken(token: String, enabled: Boolean = true): String {
        val cleaned = token.trim()
        require(cleaned.isNotBlank()) { "Push token must not be blank" }
        prefs.edit()
            .putString(KEY_LAST_TOKEN, cleaned)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        val id = deviceId
        pushClientFactory().register(deviceId = id, token = cleaned, enabled = enabled)
        return cleaned
    }

    fun cacheTokenLocally(token: String) {
        val cleaned = token.trim()
        if (cleaned.isNotBlank()) {
            prefs.edit().putString(KEY_LAST_TOKEN, cleaned).apply()
        }
    }

    suspend fun unregister() {
        val id = deviceId
        pushClientFactory().unregister(deviceId = id)
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    private fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    companion object {
        private const val PREFS_NAME = "device_push_registrar"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_ENABLED = "push_enabled"

        @Volatile
        internal var activeInstance: DevicePushRegistrar? = null

        internal fun createForTest(
            prefs: SharedPreferences,
            fcmTokenProvider: suspend () -> String? = { null },
            pushClientFactory: () -> PushTokenClient
        ): DevicePushRegistrar = DevicePushRegistrar(prefs, pushClientFactory, fcmTokenProvider)

        fun buildLocalToken(deviceId: String): String {
            require(deviceId.isNotBlank()) { "deviceId must not be blank" }
            return "android-local-$deviceId"
        }

        private suspend fun fetchFirebaseToken(context: Context): String? {
            val initialized = runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context) != null
                } else {
                    true
                }
            }.getOrDefault(false)
            if (!initialized) return null

            return suspendCancellableCoroutine { cont ->
                runCatching {
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            if (cont.isActive) cont.resume(token)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
