package uk.co.fuelprices.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * A line chart with optional filled area drawn on a Compose Canvas.
 */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillAlpha: Float = 0.1f,
    strokeWidth: Float = 3f,
    minValue: Double = values.minOrNull() ?: 0.0,
    maxValue: Double = values.maxOrNull() ?: 1.0,
    showGrid: Boolean = true,
) {
    val range = (maxValue - minValue).coerceAtLeast(1.0)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier.drawBehind {
        val w = size.width
        val h = size.height

        if (showGrid) {
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
        }

        if (values.size < 2) return@drawBehind

        fun xOf(i: Int) = w * i / (values.size - 1).toFloat()
        fun yOf(v: Double) = h * (1f - ((v - minValue) / range).toFloat())

        // Area fill
        val areaPath = Path().apply {
            moveTo(0f, h)
            values.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
            lineTo(w, h)
            close()
        }
        drawPath(areaPath, lineColor.copy(alpha = fillAlpha))

        // Line
        val linePath = Path().apply {
            values.forEachIndexed { i, v ->
                val x = xOf(i); val y = yOf(v)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(linePath, lineColor, style = Stroke(width = strokeWidth))
    })
}
