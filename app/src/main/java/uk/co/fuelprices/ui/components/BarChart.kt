package uk.co.fuelprices.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * A simple bar chart drawn on a Compose Canvas.
 * Each bar's height is proportional to its value within [minValue, maxValue].
 * Opacity increases from left to right to emphasise recency.
 */
@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    minValue: Double = values.minOrNull() ?: 0.0,
    maxValue: Double = values.maxOrNull() ?: 1.0,
) {
    val range = (maxValue - minValue).coerceAtLeast(1.0)

    Box(modifier.drawBehind {
        if (values.isEmpty()) return@drawBehind
        val barWidth = size.width / values.size
        val padding = barWidth * 0.15f

        values.forEachIndexed { i, v ->
            val fraction = ((v - minValue) / range).toFloat()
            val barHeight = (0.1f + 0.9f * fraction) * size.height
            val alpha = 0.3f + 0.7f * (i.toFloat() / values.size)

            drawRect(
                color = barColor.copy(alpha = alpha),
                topLeft = Offset(i * barWidth + padding, size.height - barHeight),
                size = Size(barWidth - 2 * padding, barHeight),
            )
        }
    })
}
