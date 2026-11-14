package vn.edu.hcmute.androidchat.ui.theme

import android.os.Build
import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import vn.edu.hcmute.androidchat.feature.settings.model.AppLanguage
import vn.edu.hcmute.androidchat.feature.settings.model.localeTagOrNull

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun AndroidChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val localeTag = appLanguage.localeTagOrNull()
    val localeConfiguration = remember(baseConfiguration, localeTag) {
        Configuration(baseConfiguration).apply {
            localeTag?.let { setLocale(Locale.forLanguageTag(it)) }
        }
    }
    val localizedContext = remember(baseContext, localeConfiguration, localeTag) {
        if (localeTag == null) baseContext
        else baseContext.createConfigurationContext(localeConfiguration)
    }
    val providedValues = buildList<ProvidedValue<*>> {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localeConfiguration)
        activityResultRegistryOwner?.let { add(LocalActivityResultRegistryOwner provides it) }
        add(LocalLifecycleOwner provides lifecycleOwner)
        backPressedDispatcherOwner?.let { add(LocalOnBackPressedDispatcherOwner provides it) }
    }

    CompositionLocalProvider(*providedValues.toTypedArray()) {
        val colorScheme = when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(localizedContext)
                else dynamicLightColorScheme(localizedContext)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
