package uk.co.fuelprices.ui.screens.heatmap

import androidx.compose.ui.graphics.Color

/**
 * Diverging colour scale for price deviation from the national average — cheaper → green,
 * about-average → amber, pricier → red. Mirrors the web app's lib/heatColor.ts so both platforms
 * colour the heat map identically. [delta] is pence vs the national mean; [maxAbs] is the deviation
 * that saturates the scale.
 */

private val CHEAP = Triple(22, 163, 74) // #16a34a green
private val MID = Triple(234, 179, 8) // #eab308 amber
private val PRICEY = Triple(220, 38, 38) // #dc2626 red

private fun lerp(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, t: Float): Color {
    val r = (a.first + (b.first - a.first) * t).toInt()
    val g = (a.second + (b.second - a.second) * t).toInt()
    val bl = (a.third + (b.third - a.third) * t).toInt()
    return Color(r, g, bl)
}

fun heatColor(delta: Double, maxAbs: Double): Color {
    val span = maxOf(maxAbs, 0.1)
    val t = (delta / span).coerceIn(-1.0, 1.0).toFloat()
    return if (t < 0) lerp(MID, CHEAP, -t) else lerp(MID, PRICEY, t)
}
