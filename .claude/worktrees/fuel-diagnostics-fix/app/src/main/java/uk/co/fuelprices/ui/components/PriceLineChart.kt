package uk.co.fuelprices.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM")

/** "2026-07-24T…" → "24 Jul"; falls back to the first 10 chars if it can't be parsed. */
private fun shortDate(iso: String): String =
    try { LocalDate.parse(iso.take(10)).format(DATE_FMT) } catch (_: Exception) { iso.take(10) }

/**
 * A price line chart matching the web app's trend chart: auto-scaled to the plotted series' own
 * range (so the line uses the full height rather than being squashed), with Y-axis price labels,
 * gridlines, a filled area, per-point dots, start/mid/end date labels, and a Low/High/Δ summary.
 * Used for both the national price trend and a station's price history.
 */
@Composable
fun PriceLineChart(
    values: List<Double>,
    dates: List<String>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return

    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(color = axisColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

    val minV = values.min()
    val maxV = values.max()
    // Pad the range so the line/area don't touch the top and bottom edges (mirrors the web's ±0.5).
    val pad = ((maxV - minV) * 0.1).coerceAtLeast(0.5)
    val lo = minV - pad
    val hi = maxV + pad
    val span = (hi - lo).coerceAtLeast(0.1)
    val ticks = 5

    OutlinedCard(modifier) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val padLeft = 46.dp.toPx()
                val padTop = 8.dp.toPx()
                val padRight = 8.dp.toPx()
                val padBottom = 20.dp.toPx()
                val chartW = size.width - padLeft - padRight
                val chartH = size.height - padTop - padBottom

                fun xOf(i: Int) =
                    padLeft + if (values.size == 1) chartW / 2f else chartW * i / (values.size - 1).toFloat()
                fun yOf(v: Double) = padTop + chartH * (1f - ((v - lo) / span).toFloat())

                // Gridlines + Y-axis value labels (top = hi, bottom = lo).
                for (t in 0 until ticks) {
                    val frac = t / (ticks - 1f)
                    val y = padTop + chartH * frac
                    drawLine(gridColor, Offset(padLeft, y), Offset(padLeft + chartW, y), strokeWidth = 1f)
                    val label = "%.1f".format(hi - (hi - lo) * frac)
                    val m = measurer.measure(label, labelStyle)
                    drawText(m, topLeft = Offset(padLeft - m.size.width - 6.dp.toPx(), y - m.size.height / 2f))
                }

                if (values.size >= 2) {
                    val area = Path().apply {
                        moveTo(xOf(0), padTop + chartH)
                        values.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
                        lineTo(xOf(values.size - 1), padTop + chartH)
                        close()
                    }
                    drawPath(area, lineColor.copy(alpha = 0.12f))

                    val line = Path().apply {
                        values.forEachIndexed { i, v ->
                            if (i == 0) moveTo(xOf(i), yOf(v)) else lineTo(xOf(i), yOf(v))
                        }
                    }
                    drawPath(
                        line,
                        lineColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // Dots — the only visible mark when there's a single point (common, as history only
                // records actual changes).
                values.forEachIndexed { i, v ->
                    drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(xOf(i), yOf(v)))
                }

                // X-axis date labels: first, middle, last (kept within bounds).
                listOf(0, values.size / 2, values.size - 1).distinct().forEach { i ->
                    val m = measurer.measure(shortDate(dates.getOrElse(i) { "" }), labelStyle)
                    val x = (xOf(i) - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
                    drawText(m, topLeft = Offset(x, size.height - m.size.height.toFloat()))
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            ) {
                val style = MaterialTheme.typography.labelSmall
                Text("Low: %.1fp".format(minV), style = style, color = axisColor)
                Text("High: %.1fp".format(maxV), style = style, color = axisColor)
                Text("Δ %.1fp".format(maxV - minV), style = style, color = axisColor)
            }
        }
    }
}
