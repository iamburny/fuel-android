package uk.co.fuelprices.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

    MaterialTheme(colorScheme = colorScheme, content = content)
}
