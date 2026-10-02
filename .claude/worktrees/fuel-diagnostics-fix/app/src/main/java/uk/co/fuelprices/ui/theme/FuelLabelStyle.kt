package uk.co.fuelprices.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import uk.co.fuelprices.data.api.FuelTypes

/** Set once at the app root from the user's saved preference; defaults to short names. */
val LocalUseLongFuelNames = compositionLocalOf { false }

/** Fuel type label respecting the user's short/long name preference. */
@Composable
@ReadOnlyComposable
fun fuelLabel(code: String): String =
    if (LocalUseLongFuelNames.current) FuelTypes.longLabel(code) else FuelTypes.shortLabel(code)

/**
 * Dark-mode overrides for fuel colors that are too dark to read as foreground text/icons against
 * a dark surface. FuelTypes' canonical palette has Diesel near-black (0xFF111827) and Super Diesel
 * dark-grey (0xFF4B5563); on dark backgrounds these vanish, so we swap in lighter, still-distinct
 * slate tones. All other fuel colors are bright enough and pass through unchanged.
 */
private val DarkFuelColors = mapOf(
    "B7_STANDARD" to Color(0xFFCBD5E1), // Diesel — light slate
    "B7_PREMIUM" to Color(0xFF94A3B8),  // Super Diesel — mid slate
)

/**
 * Fuel type color adjusted for the active theme. Use this at foreground draw sites (text, icons,
 * chart lines). For filled chips/pills with white labels, keep [FuelTypes.color] directly.
 */
@Composable
@ReadOnlyComposable
fun fuelColor(code: String): Color =
    if (LocalIsDarkTheme.current) DarkFuelColors[code] ?: FuelTypes.color(code)
    else FuelTypes.color(code)
