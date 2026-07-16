package uk.co.fuelprices.car

import androidx.car.app.model.CarColor
import androidx.compose.ui.graphics.toArgb
import uk.co.fuelprices.data.api.FuelTypes

/**
 * Converts the app's unified per-fuel-type colour (phone UI) into a car-safe [CarColor] for map
 * pin markers, mirroring the phone's colour-coded pins. [CarColor.createCustom] is a documented,
 * host-supported way to colour a [androidx.car.app.model.PlaceMarker]'s pin (unlike row/pane text,
 * where custom colours are explicitly unsupported per the Car App Library's own docs) — the host
 * may still fall back to a default colour if the custom value fails its contrast check.
 */
fun fuelCarColor(fuelType: String): CarColor {
    val argb = FuelTypes.color(fuelType).toArgb()
    return CarColor.createCustom(argb, argb)
}
