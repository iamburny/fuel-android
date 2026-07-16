package uk.co.fuelprices.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import uk.co.fuelprices.data.api.FuelTypes

/** Set once at the app root from the user's saved preference; defaults to short names. */
val LocalUseLongFuelNames = compositionLocalOf { false }

/** Fuel type label respecting the user's short/long name preference. */
@Composable
@ReadOnlyComposable
fun fuelLabel(code: String): String =
    if (LocalUseLongFuelNames.current) FuelTypes.longLabel(code) else FuelTypes.shortLabel(code)
