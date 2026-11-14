package vn.edu.hcmute.androidchat.feature.settings.model

/** Preference values reserved for the AUTH-owned settings screen and persisted by its host. */
enum class AppLanguage { SYSTEM, VIETNAMESE, ENGLISH }
enum class AppThemePreference { SYSTEM, LIGHT, DARK }

internal fun AppThemePreference.resolveDarkTheme(systemDarkTheme: Boolean): Boolean = when (this) {
    AppThemePreference.SYSTEM -> systemDarkTheme
    AppThemePreference.LIGHT -> false
    AppThemePreference.DARK -> true
}

data class AuthAppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: AppThemePreference = AppThemePreference.SYSTEM,
    val phoneDiscoveryEnabled: Boolean = false
)
