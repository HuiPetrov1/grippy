// SPDX-License-Identifier: MPL-2.0
// Grippy: the Measure section's charts, drawn on a Canvas. Not part of upstream.

package app.grippy.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun DrawScope.curvePath(points: List<ForcePoint>, x: (Double) -> Float, y: (Double) -> Float): Path {
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.t), y(p.kg)) else path.lineTo(x(p.t), y(p.kg)) }
    return path
}

/** The force so far, during a recording. */
@Composable
fun LiveCurve(points: List<ForcePoint>, windowSeconds: Int, color: Color, grid: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val top = max(5.0, (points.maxOfOrNull { it.kg } ?: 0.0) * 1.2)
        val x = { t: Double -> (t / windowSeconds * size.width).toFloat() }
        val y = { kg: Double -> (size.height - (kg.coerceAtLeast(0.0) / top) * size.height).toFloat() }
        for (i in 0..3) {
            val gy = size.height * i / 4f
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        if (points.size < 2) return@Canvas
        val line = curvePath(points, x, y)
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.last().t), size.height)
            lineTo(x(points.first().t), size.height)
            close()
        }
        drawPath(area, color.copy(alpha = .22f))
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The share card's curve: area, line, avg dashed, max marked, min noted. White on the blue. */
@Composable
fun ResultCurve(
    points: List<ForcePoint>, windowSeconds: Int, avg: Double, max: Double, min: Double, modifier: Modifier = Modifier,
    /** False for a camera-counted set: the curve is a movement, with no kilograms to label. */
    labelled: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val labelRoom = 28.dp.toPx()
        val w = size.width - labelRoom
        val topPad = 18.dp.toPx()
        val h = size.height - topPad
        val top = if (max > 0) max * 1.05 else (points.maxOf { it.kg } * 1.05).coerceAtLeast(1.0)
        val span = max(windowSeconds.toDouble(), points.last().t).coerceAtLeast(0.1)
        val x = { t: Double -> (t / span * w).toFloat() }
        val y = { kg: Double -> (topPad + h - (kg.coerceAtLeast(0.0) / top) * h).toFloat() }
        val line = curvePath(points, x, y)
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.last().t), size.height)
            lineTo(x(points.first().t), size.height)
            close()
        }
        drawPath(area, Color.White.copy(alpha = .28f))
        drawPath(line, Color.White, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (!labelled) return@Canvas
        // Average, dashed across, labelled at the right edge.
        val ay = y(avg)
        drawLine(Color.White, Offset(0f, ay), Offset(w, ay), 1.4.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        label(measurer, "avg", Offset(w + 5.dp.toPx(), ay), Color.White, 12, center = true)
        // The peak.
        val peak = points.maxBy { it.kg }
        val px = x(peak.t)
        val py = y(peak.kg)
        drawCircle(Color.White, 5.dp.toPx(), Offset(px, py))
        drawCircle(Color(0xFF0A84FF), 3.4.dp.toPx(), Offset(px, py))
        drawCircle(Color.White, 2.4.dp.toPx(), Offset(px, py))
        label(measurer, "Max ${formatKg(max)}", Offset(px + 4.dp.toPx(), py - 15.dp.toPx()), Color.White, 12, bold = true)
        val minText = "Min ${formatKg(min)}"
        val minLayout = measurer.measure(minText, TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = .8f)))
        drawText(minLayout, topLeft = Offset(w - minLayout.size.width - 5.dp.toPx(), size.height - minLayout.size.height - 2.dp.toPx()))
    }
}

private fun DrawScope.label(
    measurer: TextMeasurer, text: String, at: Offset, color: Color, sp: Int,
    bold: Boolean = false, center: Boolean = false,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = sp.sp, color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, fontFamily = run.nuri.getagrip.ui.theme.GripFonts.text))
    val y = if (center) at.y - layout.size.height / 2f else at.y
    drawText(layout, topLeft = Offset(at.x, y))
}

/** One point on a history chart. */
data class HistoryPoint(val at: Long, val value: Double)

/** The tiny chart on a group card: each hand's history as open circles joined by a line. */
@Composable
fun Sparkline(left: List<HistoryPoint>, right: List<HistoryPoint>, leftColor: Color, rightColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val all = left + right
        if (all.isEmpty()) return@Canvas
        val lo = all.minOf { it.value }
        val hi = all.maxOf { it.value }
        val t0 = all.minOf { it.at }
        val t1 = all.maxOf { it.at }
        val pad = 6.dp.toPx()
        val x = { at: Long -> if (t1 == t0) size.width / 2 else pad + ((at - t0).toFloat() / (t1 - t0)) * (size.width - 2 * pad) }
        val y = { v: Double -> if (hi == lo) size.height / 2 else (pad + (1 - (v - lo) / (hi - lo)) * (size.height - 2 * pad)).toFloat() }
        series(left, leftColor, x, y, 1.6.dp, 5.dp)
        series(right, rightColor, x, y, 1.6.dp, 5.dp)
    }
}

private fun DrawScope.series(
    points: List<HistoryPoint>, color: Color, x: (Long) -> Float, y: (Double) -> Float, width: Dp, radius: Dp,
    fill: Color? = null,
) {
    val sorted = points.sortedBy { it.at }
    for (i in 1 until sorted.size) {
        drawLine(color, Offset(x(sorted[i - 1].at), y(sorted[i - 1].value)), Offset(x(sorted[i].at), y(sorted[i].value)), width.toPx(), cap = StrokeCap.Round)
    }
    sorted.forEach { p ->
        val o = Offset(x(p.at), y(p.value))
        if (fill != null) drawCircle(fill, radius.toPx(), o)
        drawCircle(color, radius.toPx(), o, style = Stroke(width.toPx() * 1.3f))
    }
}

/** Round, readable gridlines for a value range. */
internal fun niceTicks(lo: Double, hi: Double, count: Int = 4): List<Double> {
    val span = (hi - lo).takeIf { it > 0 } ?: max(1.0, hi)
    val raw = span / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val out = ArrayList<Double>()
    var v = start
    while (v <= end + step / 2) { out += v; v += step }
    return out
}

/** The group screen's chart: dashed grid, y labels, date labels, each hand's line. */
@Composable
fun HistoryChart(
    left: List<HistoryPoint>, right: List<HistoryPoint>, from: Long, to: Long,
    leftColor: Color, rightColor: Color, ink: Color, grid: Color, background: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(213.dp),
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(ink) { TextStyle(fontSize = 13.5.sp, color = ink, fontFamily = run.nuri.getagrip.ui.theme.GripFonts.text) }
    Canvas(modifier) {
        val all = left + right
        val lo0 = all.minOfOrNull { it.value } ?: 0.0
        val hi0 = all.maxOfOrNull { it.value } ?: 1.0
        val pad = max((hi0 - lo0) * .25, max(hi0 * .05, .5))
        val ticks = niceTicks(max(0.0, lo0 - pad), hi0 + pad)
        val lo = ticks.first()
        val hi = ticks.last()
        val yAxis = 33.dp.toPx()
        val bottomRoom = 30.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotH = size.height - bottomRoom - plotTop
        val inset = 16.dp.toPx()
        val plotL = yAxis + inset
        val plotR = size.width - inset
        val y = { v: Double -> (plotTop + (1 - (v - lo) / (hi - lo)) * plotH).toFloat() }
        val span = (to - from).coerceAtLeast(1)
        val x = { at: Long -> plotL + ((at - from).toFloat() / span) * (plotR - plotL) }
        // Grid: dashed, every half tick; labels on the whole ticks.
        val halfSteps = ticks.size * 2 - 1
        for (i in 0 until halfSteps) {
            val v = lo + (hi - lo) * i / (halfSteps - 1)
            val gy = y(v)
            drawLine(grid, Offset(yAxis, gy), Offset(size.width, gy), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))
            if (i % 2 == 0) {
                val text = if (hi - lo >= 6) v.toInt().toString() else formatKg(v).removeSuffix(".0")
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(yAxis / 2 - layout.size.width / 2f, gy - layout.size.height / 2f))
            }
        }
        // Date labels: up to four, at the distinct days with data (or the range ends).
        val days = all.map { it.at }.distinctBy { shortDate(it) }.sorted()
        val dateMarks = if (days.isEmpty()) listOf(from, to) else days.let { d ->
            if (d.size <= 4) d else (0 until 4).map { d[it * (d.size - 1) / 3] }
        }
        dateMarks.forEach { at ->
            val layout = measurer.measure(shortDate(at), labelStyle)
            val cx = x(at).coerceIn(yAxis + layout.size.width / 2f, size.width - layout.size.width / 2f)
            drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, size.height - layout.size.height))
        }
        series(left, leftColor, x, y, 2.dp, 7.dp, fill = background)
        series(right, rightColor, x, y, 2.dp, 7.dp, fill = background)
    }
}

/** Keeps the latest value per day per hand so one day's retries read as one point. */
fun dailyPoints(measurements: List<MeasurementEntity>, metric: Metric, bodyWeight: Boolean): List<HistoryPoint> =
    measurements.sortedBy { it.takenAt }.map { m ->
        val v = metric.of(m)
        HistoryPoint(m.takenAt, if (bodyWeight && m.bodyWeightKg != null && m.bodyWeightKg > 0) v / m.bodyWeightKg * 100 else v)
    }

internal fun clampRange(from: Long, to: Long): Pair<Long, Long> = min(from, to) to max(from, to)
