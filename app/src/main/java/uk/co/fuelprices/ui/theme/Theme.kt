package uk.co.fuelprices.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The user's persisted appearance choice. Stored as its [name] string in UserPreferences. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Whether the resolved theme is dark. Provided by [FuelPricesTheme] so foreground helpers like
 * `fuelColor()` can pick readable colors that follow the actual applied theme (including the
 * explicit light/dark selector), not just the system setting.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val FuelGreen = Color(0xFF2E7D32)
private val FuelGreenLight = Color(0xFF60AD5E)

private val LightColors = lightColorScheme(
    primary = FuelGreen,
    secondary = FuelGreenLight,
)

private val DarkColors = darkColorScheme(
    primary = FuelGreenLight,
    secondary = FuelGreen,
)

@Composable
fun FuelPricesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
