package vn.edu.hcmute.androidchat.feature.auth.validation

import java.util.Locale

object AuthValidation {
    private val emailPattern = Regex("^[^\\s@]{1,64}@[^\\s@.]+(?:\\.[^\\s@.]+)+$")
    private val usernamePattern = Regex("^[a-z0-9_]{3,24}$")

    fun emailError(email: String): String? {
        val value = email.trim()
        return if (value.length <= 254 && emailPattern.matches(value)) null else "invalid_email"
    }

    fun passwordError(password: String): String? {
        val byteCount = password.toByteArray(Charsets.UTF_8).size
        return when {
            byteCount < 10 -> "password_too_short"
            byteCount > 72 -> "password_too_long"
            else -> null
        }
    }

    fun displayNameError(displayName: String): String? =
        if (displayName.trim().length in 1..80) null else "invalid_display_name"

    fun bioError(bio: String): String? = if (bio.length <= 160) null else "bio_too_long"

    fun usernameError(username: String): String? =
        if (username.lowercase(Locale.ROOT).matches(usernamePattern)) null else "invalid_username"
}
