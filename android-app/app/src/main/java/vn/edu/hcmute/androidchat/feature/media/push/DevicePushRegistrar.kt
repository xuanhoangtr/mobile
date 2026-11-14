package vn.edu.hcmute.androidchat.feature.media.push

import android.content.Context
import android.content.SharedPreferences
import vn.edu.hcmute.androidchat.feature.media.data.PushTokenClient
import java.util.UUID

class DevicePushRegistrar private constructor(
    private val prefs: SharedPreferences,
    private val pushClientFactory: () -> PushTokenClient
) {
    constructor(
        context: Context,
        pushClientFactory: () -> PushTokenClient
    ) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        pushClientFactory = pushClientFactory
    )

    val deviceId: String
        get() = getOrCreateDeviceId()

    fun currentToken(): String = buildLocalToken(deviceId)

    suspend fun register(enabled: Boolean = true): String {
        val id = deviceId
        val token = buildLocalToken(id)
        pushClientFactory().register(deviceId = id, token = token, enabled = enabled)
        prefs.edit()
            .putString(KEY_LAST_TOKEN, token)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        return token
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

        internal fun createForTest(
            prefs: SharedPreferences,
            pushClientFactory: () -> PushTokenClient
        ): DevicePushRegistrar = DevicePushRegistrar(prefs, pushClientFactory)

        fun buildLocalToken(deviceId: String): String {
            require(deviceId.isNotBlank()) { "deviceId must not be blank" }
            return "android-local-$deviceId"
        }
    }
}
