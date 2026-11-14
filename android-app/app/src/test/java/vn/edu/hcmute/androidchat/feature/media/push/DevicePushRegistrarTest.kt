package vn.edu.hcmute.androidchat.feature.media.push

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.media.data.PushTokenClient

class DevicePushRegistrarTest {
    @Test
    fun registrarPersistsStableDeviceIdAndRegistersDeterministicToken() = runTest {
        val prefs = InMemorySharedPreferences()
        val registered = mutableListOf<Triple<String, String, Boolean>>()
        val unregistered = mutableListOf<String>()

        val client = object : PushTokenClient {
            override suspend fun register(deviceId: String, token: String, enabled: Boolean) {
                registered.add(Triple(deviceId, token, enabled))
            }

            override suspend fun unregister(deviceId: String) {
                unregistered.add(deviceId)
            }
        }

        val first = DevicePushRegistrar.createForTest(prefs) { client }
        val second = DevicePushRegistrar.createForTest(prefs) { client }

        assertEquals(first.deviceId, second.deviceId)
        assertTrue(first.currentToken().startsWith("android-local-"))
        assertEquals("android-local-${first.deviceId}", first.currentToken())

        val token = first.register(enabled = true)
        assertEquals("android-local-${first.deviceId}", token)
        assertEquals(listOf(Triple(first.deviceId, token, true)), registered)

        second.unregister()
        assertEquals(listOf(first.deviceId), unregistered)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }
}
