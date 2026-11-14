package vn.edu.hcmute.androidchat.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.settings.data.AuthAppSettingsStore
import vn.edu.hcmute.androidchat.feature.settings.model.AppThemePreference
import vn.edu.hcmute.androidchat.feature.settings.model.resolveDarkTheme

class AuthAppSettingsStoreTest {
    @Test
    fun themePreferenceDefaultsToSystemAndPersistsAcrossStoreInstances() = runBlocking {
        val directory = Files.createTempDirectory("auth-app-settings").toFile()
        val file = File(directory, "settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file }
            )
            val firstStore = AuthAppSettingsStore(dataStore)

            assertEquals(AppThemePreference.SYSTEM, firstStore.themePreference.first())
            firstStore.setThemePreference(AppThemePreference.DARK)

            val reopenedStore = AuthAppSettingsStore(dataStore)
            assertEquals(AppThemePreference.DARK, reopenedStore.themePreference.first())
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun themeChoiceResolvesSystemLightAndDarkModes() {
        assertEquals(true, AppThemePreference.SYSTEM.resolveDarkTheme(systemDarkTheme = true))
        assertEquals(false, AppThemePreference.SYSTEM.resolveDarkTheme(systemDarkTheme = false))
        assertEquals(false, AppThemePreference.LIGHT.resolveDarkTheme(systemDarkTheme = true))
        assertEquals(true, AppThemePreference.DARK.resolveDarkTheme(systemDarkTheme = false))
    }
}
