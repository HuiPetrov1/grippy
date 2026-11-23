// SPDX-License-Identifier: MPL-2.0
// Grippy: the Measure section's vocabulary and maths. Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Critical Force protocol: 24 reps of 7 s pulling, 3 s resting. */
const val CF_REPS = 24
const val CF_WORK_SECONDS = 7
const val CF_REST_SECONDS = 3
const val CF_TOTAL_SECONDS = CF_REPS * (CF_WORK_SECONDS + CF_REST_SECONDS)

/** Peak: as many pulls as you like until Stop; the highest force of any of them counts. */
const val PEAK_MAX_SECONDS = 600
const val PEAK_TRIGGER_KG = 1.5
const val PEAK_RELEASE_FRACTION = 0.25
const val PEAK_RELEASE_SECONDS = 0.3

/** How a measurement is taken and what its headline number means. */
enum class MeasureType(
    val key: String,
    val label: String,
    val blurb: String,
    val dot: Color,
    /** Seconds the Duration field starts at; 0 = no Duration field (a fixed protocol, or Peak). */
    val defaultSeconds: Int,
) {
    Peak("peak", "Peak", "Maximum isometric force.", Color(0xFF0A84FF), 0),
    Endurance("endurance", "Endurance", "How long you can hold force", Color(0xFFFCC33E), 10),
    CriticalForce("cf", "Critical Force", "Sustainable force over repeated efforts", Color(0xFFBF5AF2), 0),
    Rfd("rfd", "RFD", "Measure how quickly you can generate force.", Color(0xFF3FC8E4), 3),
    /** Counted by the camera phone, not the scale. */
    Reps("reps", "Reps · camera", "Your second phone counts the reps.", Color(0xFF30D158), 0);

    /** Everything else is measured with the scale. */
    val usesCamera: Boolean get() = this == Reps

    /** The radio row's title. */
    val menuLabel: String get() = if (this == CriticalForce) "$label · $CF_REPS reps" else label

    /** The metrics its chart can show, the first being the headline. */
    val metrics: List<Metric>
        get() = when (this) {
            Peak -> listOf(Metric.Max, Metric.Average, Metric.TotalLoad)
            Endurance -> listOf(Metric.Average, Metric.Max, Metric.TotalLoad)
            CriticalForce -> listOf(Metric.CriticalForce, Metric.Max, Metric.TotalLoad)
            Rfd -> listOf(Metric.Rfd, Metric.Max, Metric.TotalLoad)
            Reps -> listOf(Metric.Reps, Metric.RepTime, Metric.Tempo)
        }

    val headline: Metric get() = metrics.first()

    companion object {
        fun of(key: String): MeasureType = entries.firstOrNull { it.key == key } ?: Peak
    }
}

enum class Hand(val key: String, val label: String) {
    Left("left", "Left"), Right("right", "Right");

    companion object {
        fun of(key: String): Hand = if (key == Right.key) Right else Left
    }
}

/** A number a measurement can be charted by. */
enum class Metric(val label: String, val unit: String) {
    Average("Average", "kg"),
    Max("Max", "kg"),
    TotalLoad("Total Load", "kg·s"),
    CriticalForce("Critical Force", "kg"),
    Rfd("RFD", "kg/s"),
    Reps("Reps", "reps"),
    RepTime("Time", "s"),
    Tempo("Tempo", "s/rep");

    /** Only force-shaped numbers have a meaningful % of bodyweight. */
    val isForce: Boolean get() = unit == "kg"

    fun of(m: MeasurementEntity): Double = when (this) {
        Average -> m.avgKg
        Max -> m.maxKg
        TotalLoad -> m.impulse
        CriticalForce -> m.cfKg
        Rfd -> m.rfd
        Reps -> m.reps.toDouble()
        RepTime -> m.durationSec.toDouble()
        Tempo -> m.tempoSec
    }
}

/** One reading: seconds since the recording began, and force. */
data class ForcePoint(val t: Double, val kg: Double)

/** Which measurements share a card and a chart: same type, and for Endurance the same window. */
data class GroupKey(val type: MeasureType, val seconds: Int) {
    val title: String
        get() = when (type) {
            MeasureType.Endurance -> "Endurance $seconds s"
            MeasureType.Peak -> "Peak"
            MeasureType.CriticalForce -> "Critical Force"
            MeasureType.Rfd -> "RFD"
            MeasureType.Reps -> "Reps"
        }

    /** Stable string for saved navigation. */
    val id: String get() = "${type.key}:$seconds"

    companion object {
        fun of(m: MeasurementEntity): GroupKey {
            val type = MeasureType.of(m.type)
            return GroupKey(type, if (type == MeasureType.Endurance) m.durationSec else 0)
        }

        fun parse(id: String): GroupKey {
            val (type, seconds) = id.split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            return GroupKey(MeasureType.of(type), seconds)
        }
    }
}

/** The pure maths: everything a result card shows comes from the recorded points. */
object MeasureMath {
    fun encode(points: List<ForcePoint>): String =
        points.joinToString(";") { "%.2f,%.2f".format(Locale.US, it.t, it.kg) }

    fun decode(text: String): List<ForcePoint> =
        if (text.isBlank()) emptyList() else text.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            val t = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val kg = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            ForcePoint(t, kg)
        }

    /** Keeps at most [hz] points per second, so a four-minute test stays a few kilobytes. */
    fun thin(points: List<ForcePoint>, hz: Int = 25): List<ForcePoint> {
        if (points.size < 2) return points
        val step = 1.0 / hz
        val out = ArrayList<ForcePoint>(points.size)
        var last = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.t - last >= step - 1e-9) { out += p; last = p.t }
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    fun maxKg(points: List<ForcePoint>): Double = points.maxOfOrNull { it.kg }?.coerceAtLeast(0.0) ?: 0.0

    fun minKg(points: List<ForcePoint>): Double = points.minOfOrNull { it.kg }?.coerceAtLeast(0.0) ?: 0.0

    /** Force × time: the area under the curve, negatives (a tare drifting) counted as zero. */
    fun impulse(points: List<ForcePoint>): Double {
        var area = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = b.t - a.t
            if (dt > 0) area += dt * (max(a.kg, 0.0) + max(b.kg, 0.0)) / 2
        }
        return area
    }

    /** The average over the window: total load divided by the seconds asked for. */
    fun average(points: List<ForcePoint>, seconds: Double): Double {
        if (points.isEmpty()) return 0.0
        val span = if (seconds > 0) seconds else (points.last().t - points.first().t)
        return if (span > 0) impulse(points) / span else points.map { it.kg }.average()
    }

    /** Steepest rise over any span of at least 100 ms, in kg per second. */
    fun rfd(points: List<ForcePoint>): Double {
        var best = 0.0
        var j = 0
        for (i in points.indices) {
            if (j < i) j = i
            while (j < points.size && points[j].t - points[i].t < 0.1) j++
            if (j >= points.size) break
            val slope = (points[j].kg - points[i].kg) / (points[j].t - points[i].t)
            if (slope > best) best = slope
        }
        return best
    }

    /** Seconds from the first reading over 10 % of peak to the peak. */
    fun timeToPeak(points: List<ForcePoint>): Double? {
        val peak = points.maxByOrNull { it.kg } ?: return null
        if (peak.kg <= 0) return null
        val start = points.firstOrNull { it.kg >= peak.kg * .1 } ?: return null
        return peak.t - start.t
    }

    /** Each Critical Force rep's mean force over the steady middle of its pull (1 s to 6 s). */
    fun cfReps(points: List<ForcePoint>): List<Double> {
        val period = CF_WORK_SECONDS + CF_REST_SECONDS
        return (0 until CF_REPS).mapNotNull { rep ->
            val from = rep * period + 1.0
            val to = rep * period + CF_WORK_SECONDS - 1.0
            val inRep = points.filter { it.t in from..to }
            if (inRep.isEmpty()) null else inRep.map { max(it.kg, 0.0) }.average()
        }
    }

    /** Critical force: the mean of the last six reps' means, the plateau the test is built to find. */
    fun criticalForce(points: List<ForcePoint>): Double {
        val reps = cfReps(points)
        if (reps.isEmpty()) return 0.0
        return reps.takeLast(6).average()
    }
}

fun formatKg(value: Double): String = "%.1f".format(Locale.US, value)

fun formatNumber(value: Double, unit: String): String = when (unit) {
    "kg·s", "kg/s", "reps", "s" -> value.roundToInt().toString()
    else -> formatKg(value)
}

private val shortDate = SimpleDateFormat("MMM d", Locale.US)
private val longDate = SimpleDateFormat("MMM d, yyyy", Locale.US)
private val capsDate = SimpleDateFormat("MMMM d, yyyy", Locale.US)

fun shortDate(millis: Long): String = shortDate.format(Date(millis))
fun longDate(millis: Long): String = longDate.format(Date(millis))
fun capsDate(millis: Long): String = capsDate.format(Date(millis)).uppercase(Locale.US)

/** "SF" for "supinated front raise": the avatar's two letters. */
fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(Locale.getDefault())
        else -> (words[0].take(1) + words[1].take(1)).uppercase(Locale.getDefault())
    }
}

/** Left/right difference as a share of the stronger side. */
fun imbalance(left: Double?, right: Double?): Double? {
    if (left == null || right == null) return null
    val strong = max(left, right)
    return if (strong <= 0) null else abs(left - right) / strong
}
