package vn.edu.hcmute.androidchat.feature.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference

private val Context.authAppSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_app_settings"
)

/** Stores non-sensitive AUTH-owned app preferences. Theme defaults to following the device. */
class AuthAppSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.authAppSettingsDataStore)

    val themePreference: Flow<AppThemePreference> = dataStore.data.map { preferences ->
        preferences[THEME_PREFERENCE_KEY]?.let(::parseThemePreference) ?: AppThemePreference.SYSTEM
    }

    suspend fun setThemePreference(preference: AppThemePreference) {
        dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE_KEY] = preference.name
        }
    }

    private companion object {
        val THEME_PREFERENCE_KEY = stringPreferencesKey("theme_preference")

        fun parseThemePreference(value: String): AppThemePreference =
            AppThemePreference.entries.firstOrNull { it.name == value } ?: AppThemePreference.SYSTEM
    }
}
