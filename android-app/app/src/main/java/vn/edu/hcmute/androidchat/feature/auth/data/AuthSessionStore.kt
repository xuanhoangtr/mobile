package vn.edu.hcmute.androidchat.feature.auth.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import vn.edu.hcmute.androidchat.feature.auth.model.AuthCredentials
import vn.edu.hcmute.androidchat.feature.auth.model.Session
import vn.edu.hcmute.androidchat.feature.auth.model.User
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AuthSessionStore {
    fun read(): AuthCredentials?
    fun write(credentials: AuthCredentials)
    fun clear()
}

/** Encrypts all persisted credentials with an AES-GCM key that remains in Android Keystore. */
class KeystoreAuthSessionStore(context: Context) : AuthSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): AuthCredentials? {
        val encoded = preferences.getString(ENCRYPTED_SESSION, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_NONCE_BYTES) { "Encrypted session is incomplete" }
            val nonce = packed.copyOfRange(0, GCM_NONCE_BYTES)
            val ciphertext = packed.copyOfRange(GCM_NONCE_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            }
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8)).toCredentials()
        } catch (_: Exception) {
            // A backup restore can copy the encrypted preference without its device-bound key.
            clear()
            null
        }
    }

    @Synchronized
    override fun write(credentials: AuthCredentials) {
        val plaintext = credentials.toJson().toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val packed = cipher.iv + cipher.doFinal(plaintext)
        check(
            preferences.edit()
                .putString(ENCRYPTED_SESSION, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist encrypted session" }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(ENCRYPTED_SESSION).commit()) {
            "Unable to clear encrypted session"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun AuthCredentials.toJson() = JSONObject()
        .put("user", JSONObject()
            .put("id", user.id)
            .put("email", user.email)
            .put("username", user.username)
            .put("displayName", user.displayName)
            .put("normalizedPhone", user.normalizedPhone)
            .put("avatarUrl", user.avatarUrl)
            .put("bio", user.bio))
        .put("session", JSONObject()
            .put("id", session.id)
            .put("accessTokenExpiresAtEpochMillis", session.accessTokenExpiresAtEpochMillis)
            .put("refreshTokenExpiresAtEpochMillis", session.refreshTokenExpiresAtEpochMillis)
            .put("isRevoked", session.isRevoked))
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)

    private fun JSONObject.toCredentials(): AuthCredentials {
        val jsonUser = getJSONObject("user")
        val jsonSession = getJSONObject("session")
        return AuthCredentials(
            user = User(
                id = jsonUser.getLong("id"),
                email = jsonUser.optionalString("email"),
                username = jsonUser.getString("username"),
                displayName = jsonUser.getString("displayName"),
                normalizedPhone = jsonUser.optionalString("normalizedPhone"),
                avatarUrl = jsonUser.optionalString("avatarUrl"),
                bio = jsonUser.optionalString("bio")
            ),
            session = Session(
                id = jsonSession.getString("id"),
                accessTokenExpiresAtEpochMillis = jsonSession.getLong("accessTokenExpiresAtEpochMillis"),
                refreshTokenExpiresAtEpochMillis = jsonSession.getLong("refreshTokenExpiresAtEpochMillis"),
                isRevoked = jsonSession.optBoolean("isRevoked")
            ),
            accessToken = getString("accessToken"),
            refreshToken = getString("refreshToken")
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private companion object {
        const val PREFERENCES_FILE = "auth_session_secure"
        const val ENCRYPTED_SESSION = "encrypted_credentials"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "android_chat_auth_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
