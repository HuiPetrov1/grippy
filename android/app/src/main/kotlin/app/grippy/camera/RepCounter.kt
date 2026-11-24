// SPDX-License-Identifier: MPL-2.0
// Grippy: counting reps from what the camera sees. Pure maths, no Android. Not part of upstream.
//
// A line-for-line port of the reference counter (repcounter.py) that was measured on 21 real exercise
// clips: wrist curls and extensions, radial/ulnar deviation, pronation/supination, grippers, plus clips
// with no reps. Every constant below carries the reason for its value from those measurements; change
// one only with new measurements. `RepCounterReferenceTests` replays the clips' features through this
// file and checks it against the reference's own output.

package app.grippy.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One counted rep: when it finished (ms into the set), how long it took, and its range (% of the set's usual). */
data class RepMark(val tMs: Int, val durationMs: Int, val amplitude: Int)

/** Every number that decides what counts. "Sweep" = changed one at a time on the test clips. */
object RepTuning {
    const val GRID_W = 32
    const val GRID_H = 24
    /** 768. 64×48 was measured: no gain. */
    const val GRID_DIM = GRID_W * GRID_H
    /** x, y of landmarks 1…20 relative to the wrist (landmark 0). */
    const val HAND_DIM = 40

    /** History kept for counting the first reps afterwards (3 slow reps must fit). Sweep: 12 s lost reps on slow sets. */
    const val BUFFER_SEC = 20.0
    /** History the direction, middle and range are learned from. Sweep: 4 s and 8 s each broke 2–4 clips. */
    const val WINDOW_SEC = 6.0
    /** How often the direction is re-learned; also the longest wait before the first 3 reps are confirmed. */
    const val AXIS_EVERY_SEC = 0.4
    /** Fewer buffered frames than this: do not learn yet. */
    const val MIN_FRAMES = 12
    /** Power-iteration rounds per learning step, warm-started from the last direction. */
    const val POWER_ITERATIONS = 6
    /** Percentiles that define the range: ignores 8 % outliers per side. */
    const val LOW_SHARE = 0.08
    const val HIGH_SHARE = 0.92
    /** Time constant of the signal's smoothing. Sweep: 0.06 over-counted a gripper set, 0.2 lost reps. */
    const val SMOOTH_SEC = 0.12
    /** When the direction changes, the smoothed signal is rebuilt from this many recent frames. */
    const val RESMOOTH_FRAMES = 8
    /** Half-width of the middle band, as a share of the range. Sweep: 0.16 and 0.28 each put clips off. */
    const val SWING = 0.22
    /** A rep whose active part (band to band) is shorter is jitter. */
    const val MIN_REP_SEC = 0.35
    /** A rep longer than this is two unrelated movements. */
    const val MAX_REP_SEC = 12.0
    /** Alike reps in a row needed before anything counts. Sweep: 2 let a no-rep clip count 2; 4 lost every short set. */
    const val CONFIRM_REPS = 3
    /** "Alike": active durations within this ratio. Sweep: 1.5 lost a clip, 3.0 over-counted a gripper set. */
    const val ALIKE_DUR = 2.0
    /** "Alike": swing sizes within this ratio. */
    const val ALIKE_AMP = 2.0
    /** Longest rest between the first reps; the 3rd must also have ended this recently. */
    const val MAX_GAP_SEC = 10.0
    /**
     * While counting, direction/middle/range follow the window only when it moves at least this share of the
     * usual rep; otherwise they are HELD, so a pause or a long hold cannot shrink the range until noise counts.
     */
    const val HOLD_SHARE = 0.5
    /** While counting, a new direction is followed only if it is this close (|cos|) to the counted one. */
    const val AXIS_KEEP_COS = 0.7
    /** No rep for this long and a different movement: start learning again (the count is kept). */
    const val RELEARN_AFTER_SEC = 12.0
    /** …and that must hold for this long. Sweep: 0 lost the rep after a pause on 2 clips. */
    const val RELEARN_CONFIRM_SEC = 6.0
    /** While counting, a rep must swing 0.4…2.5× the usual rep. */
    const val MIN_REL_AMP = 0.4
    const val MAX_REL_AMP = 2.5
    /** While counting, a rep must last at least 0.4× the usual. Sweep: 0.3 counted re-grips. */
    const val MIN_REL_DUR = 0.4
    /** "Usual" = median of the last 5 counted reps. */
    const val TYPICAL_OF_LAST = 5
    /** Reported duration = active (band-to-band) time × 1.55, capped by the time since the last rep. */
    const val ACTIVE_TO_WHOLE = 1.55
    /** At the end: parked on the far side for a whole usual rep means the last return was cut off: a rep. */
    const val PARK_FACTOR = 1.0
    /** Picture: range needed to be a movement at all (1 = the picture's whole contrast). */
    const val PICTURE_MIN_RANGE = 0.05
    /** Hand: range needed, in hand lengths of RMS landmark travel. Keeps a still helper hand from counting. */
    const val HAND_MIN_RANGE = 0.15
    /** Two detections whose wrists are closer than this many hand lengths are one hand seen twice. */
    const val DUPLICATE_HAND = 1.0
    /** Picture normalisation: contrast below 4 grey levels counts as 4, so a dark picture's noise stays small. */
    const val GRID_SD_FLOOR = 4.0
    /** Another signal takes the lead when it has this many more reps. */
    const val LEAD_MARGIN = 1
    const val EPS = 1e-12

    // Fast counters (run 4): small quick reps riding on slower, bigger movement (arm, body, the hanging
    // weight). Each signal is also counted from its features minus their straight-line trend over the last
    // HIGH_PASS_SEC, keeping only short reps. Removing the trend turns each turn of a slow rep into a short bump
    // too, so a fast counter may lead only when it clearly sees something else (FAST_MIN_LEAD, FAST_RATIO,
    // FAST_MAX_CV). Burnout set of ~83 tiny 2-per-second partials: 13 counted before, 64 after; a slow sway
    // 1.7× the size of 40 reps: 0 before, 40 after; every result of the 21 clips × 6 variants is unchanged.
    /** Trend window. 1.5 counted 47 of the burnout and 26 of 40 under a big sway; the plain mean instead of the trend, 0. */
    const val HIGH_PASS_SEC = 1.0
    /** The away part of a 2-per-second rep lasts ~0.3 s: 0.35 rejected half of them. */
    const val FAST_MIN_REP_SEC = 0.15
    /** Longer away parts are left to the slow counters. */
    const val FAST_MAX_REP_SEC = 1.0
    /** 0.12 damps a 2 Hz swing by half. */
    const val FAST_SMOOTH_SEC = 0.06
    /** Burnout 54 (0.15) / 64 (0.10) / 69 (0.07). */
    const val FAST_SWING = 0.10
    /** Reps a fast counter needs before it may lead (and the rhythm is judged over). */
    const val FAST_MIN_LEAD = 8
    /** …and this many times the best slow count: slow reps split in two reach 1.6–2.3×. */
    const val FAST_RATIO = 3.0
    /** …and its last reps evenly spaced: gap spread ≤ 0.3 of the mean gap (burnout 0.07–0.14, a gripper's squeeze/release 0.37+). */
    const val FAST_MAX_CV = 0.3
}

/** The limits one counter works with: slow (every rep) or fast (short reps on high-passed features). */
class RepKind(val minRep: Double, val maxRep: Double, val smoothSec: Double, val swing: Double) {
    companion object {
        val Slow = RepKind(RepTuning.MIN_REP_SEC, RepTuning.MAX_REP_SEC, RepTuning.SMOOTH_SEC, RepTuning.SWING)
        val Fast = RepKind(RepTuning.FAST_MIN_REP_SEC, RepTuning.FAST_MAX_REP_SEC, RepTuning.FAST_SMOOTH_SEC, RepTuning.FAST_SWING)
    }
}

/**
 * A feature vector minus the straight line that best fits those pushed over the last
 * [RepTuning.HIGH_PASS_SEC] (this one included), taken at its time: slow movement, even a steady drift, is removed.
 */
class HighPass {
    private val times = ArrayDeque<Double>()
    private val xs = ArrayDeque<DoubleArray>()
    private var sum: DoubleArray? = null

    fun push(t: Double, x: DoubleArray): DoubleArray {
        times.addLast(t); xs.addLast(x)
        val s = sum?.also { for (i in it.indices) it[i] += x[i] } ?: x.copyOf().also { sum = it }
        while (times.size > 1 && t - times.first() > RepTuning.HIGH_PASS_SEC) {
            val old = xs.removeFirst(); times.removeFirst()
            for (i in s.indices) s[i] -= old[i]
        }
        val n = times.size
        var tSum = 0.0
        for (tt in times) tSum += tt
        val tm = tSum / n
        val xm = DoubleArray(x.size) { s[it] / n }
        var stt = 0.0
        val stx = DoubleArray(x.size)
        for (k in 0 until n) {
            val d = times[k] - tm
            stt += d * d
            val xx = xs[k]
            for (i in stx.indices) stx[i] = stx[i] + (xx[i] - xm[i]) * d
        }
        if (stt <= 0) return DoubleArray(x.size) { x[it] - xm[it] }
        val k = (t - tm) / stt
        return DoubleArray(x.size) { x[it] - (xm[it] + stx[it] * k) }
    }
}

/** The last [RepTuning.FAST_MIN_LEAD] reps came at an even rhythm. */
internal fun steady(reps: List<CountedRep>): Boolean {
    val n = RepTuning.FAST_MIN_LEAD
    if (reps.size < n) return false
    val t = reps.subList(reps.size - n, reps.size).map { it.t }
    val gaps = DoubleArray(t.size - 1) { t[it + 1] - t[it] }
    var sum = 0.0
    for (g in gaps) sum += g
    val m = sum / gaps.size
    if (m <= 0) return false
    var sq = 0.0
    for (g in gaps) sq += (g - m) * (g - m)
    return sqrt(sq / gaps.size) <= RepTuning.FAST_MAX_CV * m
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
}

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** (x − mean) · axis. */
private fun centredDot(x: DoubleArray, mean: DoubleArray, axis: DoubleArray): Double {
    var s = 0.0
    for (i in x.indices) s += (x[i] - mean[i]) * axis[i]
    return s
}

/** Turns what the camera gives into the numbers the counters work on. */
object RepFeatures {
    const val GRID_W = RepTuning.GRID_W
    const val GRID_H = RepTuning.GRID_H
    const val GRID_DIM = RepTuning.GRID_DIM

    /**
     * 768 brightness bytes → 768 numbers with mean 0 and unit length at normal contrast:
     * (v − mean) / (max(sd, 4) × √768). Exposure changes cancel out.
     */
    fun grid(luma: ByteArray): DoubleArray {
        val n = RepTuning.GRID_DIM
        val g = DoubleArray(n) { (luma[it].toInt() and 0xFF).toDouble() }
        var sum = 0.0
        for (v in g) sum += v
        val m = sum / n
        var sq = 0.0
        for (i in 0 until n) { g[i] -= m; sq += g[i] * g[i] }
        val sd = max(sqrt(sq / n), RepTuning.GRID_SD_FLOOR)
        val k = sd * sqrt(n.toDouble())
        for (i in 0 until n) g[i] /= k
        return g
    }

    /**
     * 42 landmark numbers → 40: landmarks 1…20 minus the wrist, x times [aspect] so both are in picture heights,
     * divided by √20 so a range reads as RMS landmark travel. Not divided by hand size: a still hand stays still.
     */
    fun hand(points: FloatArray, aspect: Double): DoubleArray {
        val out = DoubleArray(RepTuning.HAND_DIM)
        val wx = points[0].toDouble() * aspect
        val wy = points[1].toDouble()
        val k = 1.0 / sqrt(20.0)
        for (i in 1..20) {
            out[(i - 1) * 2] = (points[i * 2].toDouble() * aspect - wx) * k
            out[(i - 1) * 2 + 1] = (points[i * 2 + 1].toDouble() - wy) * k
        }
        return out
    }

    /** Wrist (0) to the middle finger's knuckle (9), in picture heights: how big the hand looks. */
    fun handLength(points: FloatArray, aspect: Double): Double =
        hypot((points[18].toDouble() - points[0]) * aspect, points[19].toDouble() - points[1])
}

/** Which side of the middle band the signal is on; a change reports the extreme reached on the side left. */
private class Sides(private val swing: Double) {
    var side = 0
    var ext = 0.0

    /** (new side, extreme) when the side changes, else null. */
    fun step(u: Double): Pair<Int, Double>? {
        val now = if (u > swing) 1 else if (u < -swing) -1 else 0
        if (side == 0) {
            if (now != 0) { side = now; ext = u }
            return null
        }
        ext = if (side > 0) max(ext, u) else min(ext, u)
        if (now == 0 || now == side) return null
        val ev = now to ext
        side = now
        ext = u
        return ev
    }
}

/** A rep as the counter holds it, in seconds; the amplitude relative to the usual rep. */
class CountedRep(val t: Double, val dur: Double, val active: Double, val amp: Double)

/**
 * Counts reps in one stream of feature vectors: learns the movement, waits for 3 alike reps, then counts
 * each return home. [push] may be skipped on frames where this signal is missing; time gaps are fine.
 */
class SignalCounter(private val dim: Int, private val minRange: Double, val kind: RepKind = RepKind.Slow) {
    private val times = ArrayList<Double>()
    private val xs = ArrayList<DoubleArray>()
    private val units = ArrayList<Double>()
    private var lastLearnAt = Double.NEGATIVE_INFINITY
    private var freeAxis: DoubleArray? = null

    // Preview before anything is counted: the latest movement, for the screen only.
    private var pvMean: DoubleArray? = null
    var pvAxis: DoubleArray? = null
        private set
    private var pvMid = 0.0
    private var pvRange = 0.0
    private var pvSmooth: Double? = null
    /** Last window range ÷ the range needed to be a movement. */
    var movingRatio = 0.0
        private set

    var locked = false
        private set
    private var axis = DoubleArray(0)
    private var mean = DoubleArray(0)
    private var mid = 0.0
    private var range = 0.0
    private var home = 0
    private var smooth: Double? = null
    private var lastT: Double? = null
    private var sides = Sides(kind.swing)
    private var wasHome = false
    private var leftHomeAt: Double? = null
    private var prevHomeExt: Double? = null
    private var awayAt: Double? = null
    private val typAmp = ArrayList<Double>()
    private val typDur = ArrayList<Double>()
    val reps = ArrayList<CountedRep>()
    private var lastRepAt = Double.NEGATIVE_INFINITY
    private var otherSince: Double? = null

    val count: Int get() = reps.size

    /** −1 = home (where reps start and end) … +1 = the far side. Before counting the sign is arbitrary. */
    fun signal(): Double {
        val s = smooth
        val p = pvSmooth
        val v = when {
            locked && s != null && range > 0 -> -home * (s - mid) / (range / 2.0)
            p != null && pvRange > 0 -> (p - pvMid) / (pvRange / 2.0)
            else -> return 0.0
        }
        return v.coerceIn(-1.0, 1.0)
    }

    fun push(t: Double, x: DoubleArray, unit: Double): List<CountedRep> {
        val out = ArrayList<CountedRep>()
        val dt = lastT?.let { max(t - it, 0.0) } ?: 0.0
        val k = 1.0 - exp(-dt / kind.smoothSec)
        times.add(t); xs.add(x); units.add(unit)
        while (times.size > 2 && t - times[0] > RepTuning.BUFFER_SEC) { times.removeAt(0); xs.removeAt(0); units.removeAt(0) }
        if (locked) {
            val raw = centredDot(x, mean, axis)
            val s = smooth
            smooth = if (s == null) raw else s + (raw - s) * k
            advance(t, (smooth!! - mid) / range)?.let { out.add(it) }
        } else {
            val a = pvAxis
            val m = pvMean
            if (a != null && m != null) {
                val raw = centredDot(x, m, a)
                val p = pvSmooth
                pvSmooth = if (p == null) raw else p + (raw - p) * k
            }
        }
        lastT = t
        if (t - lastLearnAt >= RepTuning.AXIS_EVERY_SEC && times.size >= RepTuning.MIN_FRAMES) {
            lastLearnAt = t
            out.addAll(learn(t))
        }
        return out
    }

    // ---- learning ----

    private fun powerAxis(d: List<DoubleArray>, warm: DoubleArray?): DoubleArray? {
        var reseeded = warm == null
        var v = warm ?: seed(d) ?: return null
        var round = 0
        val proj = DoubleArray(d.size)
        while (round < RepTuning.POWER_ITERATIONS) {
            for (i in d.indices) proj[i] = dot(d[i], v)
            val next = DoubleArray(dim)
            for (i in d.indices) { val p = proj[i]; val row = d[i]; for (j in 0 until dim) next[j] += row[j] * p }
            val norm = sqrt(dot(next, next))
            if (norm < RepTuning.EPS) {
                if (reseeded) return null
                v = seed(d) ?: return null
                reseeded = true
                round = 0
                continue
            }
            for (j in 0 until dim) next[j] /= norm
            v = next
            round++
        }
        return v
    }

    /** From the mean to the frame furthest from it. */
    private fun seed(d: List<DoubleArray>): DoubleArray? {
        var best = -1
        var bd = 0.0
        for (i in d.indices) { val dd = dot(d[i], d[i]); if (dd > bd) { bd = dd; best = i } }
        if (best < 0 || bd < 1e-18) return null
        val r = sqrt(bd)
        return DoubleArray(dim) { d[best][it] / r }
    }

    /** The buffered frames after [tFrom], projected, freshly smoothed, centred and scaled. */
    private fun series(mean: DoubleArray, axis: DoubleArray, mid: Double, rng: Double, tFrom: Double): Pair<List<Pair<Double, Double>>, Double?> {
        val out = ArrayList<Pair<Double, Double>>()
        var sm: Double? = null
        var lt = 0.0
        for (i in times.indices) {
            val t = times[i]
            if (t <= tFrom) continue
            val raw = centredDot(xs[i], mean, axis)
            sm = if (sm == null) raw else sm + (raw - sm) * (1.0 - exp(-(t - lt) / kind.smoothSec))
            lt = t
            out.add(t to (sm - mid) / rng)
        }
        return out to sm
    }

    private class Cycle(val t: Double, val start: Double, val active: Double, val amp: Double)

    /** Reps in a series: leave home, reach the other side, come back (a neutral start counts its first return). */
    private fun cycles(series: List<Pair<Double, Double>>, home: Int): List<Cycle> {
        val sides = Sides(kind.swing)
        val reps = ArrayList<Cycle>()
        var left: Double? = null
        var prevExt: Double? = null
        var wasHome = false
        var neutralStart: Boolean? = null
        for ((t, u) in series) {
            if (neutralStart == null) neutralStart = abs(u) <= kind.swing
            val atHome = u * home > kind.swing
            if (wasHome && !atHome) left = t
            wasHome = atHome
            val hadSide = sides.side
            val ev = sides.step(u)
            if (hadSide == 0 && sides.side == -home && neutralStart) { left = t; prevExt = null }
            if (ev == null) continue
            val (now, ext) = ev
            if (now != home) {
                prevExt = ext
            } else if (left != null) {
                val amp = if (prevExt != null) abs(ext - prevExt) else 2.0 * abs(ext)
                reps.add(Cycle(t, left, t - left, amp))
                left = null
            }
        }
        return reps
    }

    /** Longest run of mutually alike reps ending with the last one. */
    private fun alikeRun(reps: List<Cycle>): List<Cycle> {
        if (reps.isEmpty()) return emptyList()
        val run = ArrayList<Cycle>()
        run.add(reps.last())
        for (idx in reps.size - 2 downTo 0) {
            val r = reps[idx]
            var ok = run[0].start - r.t <= RepTuning.MAX_GAP_SEC
            for (q in run) {
                if (max(r.active, q.active) / max(min(r.active, q.active), 1e-3) > RepTuning.ALIKE_DUR) ok = false
                if (max(r.amp, q.amp) / max(min(r.amp, q.amp), 1e-6) > RepTuning.ALIKE_AMP) ok = false
            }
            if (!ok) break
            run.add(0, r)
        }
        return run
    }

    private fun rangeOf(d: List<DoubleArray>, v: DoubleArray): Pair<Double, Double> {
        val n = d.size
        val proj = DoubleArray(n) { dot(d[it], v) }
        proj.sort()
        val lo = proj[min(n - 1, (n * RepTuning.LOW_SHARE).toInt())]
        val hi = proj[min(n - 1, (n * RepTuning.HIGH_SHARE).toInt())]
        return lo to hi
    }

    private fun learn(t: Double): List<CountedRep> {
        var k0 = 0
        while (times[k0] < t - RepTuning.WINDOW_SEC) k0++
        val n = times.size - k0
        if (n < RepTuning.MIN_FRAMES) return emptyList()
        val mean = DoubleArray(dim)
        for (i in k0 until times.size) { val x = xs[i]; for (j in 0 until dim) mean[j] += x[j] }
        for (j in 0 until dim) mean[j] /= n
        val d = (k0 until times.size).map { i -> DoubleArray(dim) { xs[i][it] - mean[it] } }
        var v = powerAxis(d, freeAxis) ?: return emptyList()
        freeAxis?.let { if (dot(it, v) < 0) v = DoubleArray(dim) { j -> -v[j] } }
        freeAxis = v
        val (lo, hi) = rangeOf(d, v)
        val rng = hi - lo
        var mid = (hi + lo) / 2.0
        val unit = median(units.subList(k0, units.size))
        val need = minRange * unit
        movingRatio = if (need > 0) rng / need else 0.0
        if (!locked) return tryLock(t, mean, v, mid, rng, need)
        // Counting: follow the same movement, only while it is really moving.
        val typ = median(typAmp.takeLast(RepTuning.TYPICAL_OF_LAST))
        val cos = dot(v, axis)
        if (abs(cos) >= RepTuning.AXIS_KEEP_COS && rng >= RepTuning.HOLD_SHARE * typ) {
            if (cos < 0) { v = DoubleArray(dim) { j -> -v[j] }; mid = -mid }
            axis = v; this.mean = mean; this.mid = mid; range = rng
            smooth = resmooth(mean, v)
        } else if (t - lastRepAt > RepTuning.RELEARN_AFTER_SEC && rng >= need && abs(cos) < RepTuning.AXIS_KEEP_COS) {
            // Something else moves and no rep for a while: relearn only if the counted movement is absent.
            val (oLo, oHi) = rangeOf(d, axis)
            if (oHi - oLo < RepTuning.HOLD_SHARE * typ) {
                val since = otherSince ?: t.also { otherSince = it }
                if (t - since >= RepTuning.RELEARN_CONFIRM_SEC) {
                    locked = false
                    pvAxis = null
                    otherSince = null
                }
                return emptyList()
            }
        }
        otherSince = null
        return emptyList()
    }

    /** The last few frames' smoothed projection on a new direction, so the signal does not jump. */
    private fun resmooth(mean: DoubleArray, axis: DoubleArray): Double? {
        var sm: Double? = null
        var lt = 0.0
        val from = max(0, times.size - RepTuning.RESMOOTH_FRAMES)
        for (i in from until times.size) {
            val raw = centredDot(xs[i], mean, axis)
            sm = if (sm == null) raw else sm + (raw - sm) * (1.0 - exp(-(times[i] - lt) / kind.smoothSec))
            lt = times[i]
        }
        return sm
    }

    private fun tryLock(t: Double, mean: DoubleArray, v: DoubleArray, mid: Double, rng: Double, need: Double): List<CountedRep> {
        if (rng < need || rng <= 0) { pvAxis = null; return emptyList() }
        pvMean = mean; pvAxis = v; pvMid = mid; pvRange = rng
        pvSmooth = resmooth(mean, v)
        val (series, sm) = series(mean, v, mid, rng, lastRepAt)
        var bestHome = 0
        var best: List<Cycle>? = null
        for (h in intArrayOf(1, -1)) {
            val reps = cycles(series, h).filter { it.active >= kind.minRep && it.active <= kind.maxRep }
            val run = alikeRun(reps)
            if (run.size >= RepTuning.CONFIRM_REPS && t - run.last().t <= RepTuning.MAX_GAP_SEC) {
                if (best == null || run[0].start < best[0].start) { best = run; bestHome = h }
            }
        }
        val run = best ?: return emptyList()
        locked = true
        home = bestHome; axis = v; this.mean = mean; this.mid = mid; range = rng
        for (r in run) { typAmp.add(r.amp * rng); typDur.add(r.active) }
        val out = ArrayList<CountedRep>()
        for (r in run) out.add(emit(r.t, r.active, r.amp * rng))
        // Bring the live state machine to where the replay is now.
        sides = Sides(kind.swing); smooth = sm; leftHomeAt = null; wasHome = false; prevHomeExt = null; awayAt = null
        val lastT = run.last().t
        for ((tt, u) in series) if (tt > lastT) advance(tt, u)?.let { out.add(it) }
        if (sides.side == 0) sides.side = home
        return out
    }

    // ---- counting ----

    private fun emit(t: Double, active: Double, ampAbs: Double): CountedRep {
        val typ = if (typAmp.isNotEmpty()) median(typAmp.takeLast(RepTuning.TYPICAL_OF_LAST)) else ampAbs
        val dur = min(t - lastRepAt, active * RepTuning.ACTIVE_TO_WHOLE)
        val r = CountedRep(t, dur, active, if (typ > 0) ampAbs / typ else 0.0)
        reps.add(r)
        lastRepAt = t
        return r
    }

    private fun advance(t: Double, u: Double): CountedRep? {
        val atHome = u * home > kind.swing
        if (wasHome && !atHome) leftHomeAt = t
        wasHome = atHome
        val (now, ext) = sides.step(u) ?: return null
        if (now != home) { prevHomeExt = ext; awayAt = t; return null }
        awayAt = null
        val left = leftHomeAt ?: return null
        val prev = prevHomeExt ?: return null
        val active = t - left
        val ampAbs = abs(ext - prev) * range
        val typA = median(typAmp.takeLast(RepTuning.TYPICAL_OF_LAST))
        val typD = median(typDur.takeLast(RepTuning.TYPICAL_OF_LAST))
        if (active < max(kind.minRep, RepTuning.MIN_REL_DUR * typD) || active > kind.maxRep) return null
        if (ampAbs < RepTuning.MIN_REL_AMP * typA || ampAbs > RepTuning.MAX_REL_AMP * typA) return null
        typAmp.add(ampAbs)
        typDur.add(active)
        return emit(t, active, ampAbs)
    }

    /** End of the set: parked on the far side for a whole usual rep means the last swing was a rep. */
    fun finish(t: Double): CountedRep? {
        val away = awayAt
        if (!locked || away == null || typDur.isEmpty()) return null
        val typD = median(typDur.takeLast(RepTuning.TYPICAL_OF_LAST))
        if (t - away < RepTuning.PARK_FACTOR * typD) return null
        val r = CountedRep(away, typD * RepTuning.ACTIVE_TO_WHOLE, typD, 1.0)
        reps.add(r)
        lastRepAt = away
        awayAt = null
        return r
    }
}

/**
 * Keeps up to two hands apart by wrist position. A second track opens only when two separate hands are seen
 * at once; one hand detected twice is kept once. Tracks are never forgotten.
 */
class HandTracks(private val aspect: Double) {
    private val last = arrayOfNulls<DoubleArray>(2)

    private fun d(k: Int, p: FloatArray): Double {
        val l = last[k]!!
        return abs(p[0] - l[0]) * aspect + abs(p[1] - l[1])
    }

    /** Which track each hand belongs to. */
    fun assign(hands: List<FloatArray>): List<Pair<Int, FloatArray>> {
        var det = hands.take(2)
        if (det.isEmpty()) return emptyList()
        if (det.size == 2) {
            val la = RepFeatures.handLength(det[0], aspect)
            val lb = RepFeatures.handLength(det[1], aspect)
            val gap = abs(det[0][0].toDouble() - det[1][0]) * aspect + abs(det[0][1].toDouble() - det[1][1])
            if (gap < RepTuning.DUPLICATE_HAND * max(la, lb)) det = listOf(if (la >= lb) det[0] else det[1])
        }
        val asg: List<Pair<Int, FloatArray>> = if (det.size == 2) {
            val (a, b) = det
            when {
                last[0] == null && last[1] == null -> listOf(0 to a, 1 to b)
                last[1] == null -> if (d(0, a) <= d(0, b)) listOf(0 to a, 1 to b) else listOf(0 to b, 1 to a)
                last[0] == null -> if (d(1, a) <= d(1, b)) listOf(1 to a, 0 to b) else listOf(1 to b, 0 to a)
                else -> if (d(0, a) + d(1, b) <= d(0, b) + d(1, a)) listOf(0 to a, 1 to b) else listOf(0 to b, 1 to a)
            }
        } else {
            val p = det[0]
            val k = when {
                last[1] == null -> 0
                last[0] == null -> 1
                else -> if (d(0, p) <= d(1, p)) 0 else 1
            }
            listOf(k to p)
        }
        for ((k, p) in asg) last[k] = doubleArrayOf(p[0].toDouble(), p[1].toDouble())
        return asg
    }
}

/**
 * One set: three signals (the whole picture, hand track A, hand track B), each counted twice: as it is and
 * fast (high-passed, short reps only). The count is the highest of the six: the picture misses a small
 * hand's movement, a hand track misses frames where the hand is not recognised, a still helper hand never
 * counts at all, and quick small reps on top of slower movement only show once the slow movement is removed.
 * The picture can come every frame and the hands less often ([pushPicture], [pushHands]).
 */
class RepCounter(private val aspect: Double) {
    enum class Source(val fast: Boolean) {
        Picture(false), HandA(false), HandB(false), PictureFast(true), HandAFast(true), HandBFast(true);

        /** Whether this follows the picture rather than a hand. */
        val picture: Boolean get() = this == Picture || this == PictureFast
    }

    class Output(val count: Int, val signal: Double, val newReps: List<RepMark>, val source: Source)

    private var counters = newCounters()
    private var highPass = List(3) { HighPass() }
    private var tracks = HandTracks(aspect)
    private var t0: Double? = null
    private var tLast = 0.0
    private var lead = 0
    val reps = ArrayList<RepMark>()
    private var reported = 0

    /** Reps per signal, for checking. */
    val perSignal: List<Int> get() = counters.map { it.count }

    private fun newCounters() = listOf(
        SignalCounter(RepTuning.GRID_DIM, RepTuning.PICTURE_MIN_RANGE),
        SignalCounter(RepTuning.HAND_DIM, RepTuning.HAND_MIN_RANGE),
        SignalCounter(RepTuning.HAND_DIM, RepTuning.HAND_MIN_RANGE),
        SignalCounter(RepTuning.GRID_DIM, RepTuning.PICTURE_MIN_RANGE, RepKind.Fast),
        SignalCounter(RepTuning.HAND_DIM, RepTuning.HAND_MIN_RANGE, RepKind.Fast),
        SignalCounter(RepTuning.HAND_DIM, RepTuning.HAND_MIN_RANGE, RepKind.Fast),
    )

    /** Start a set; everything learned before is forgotten. Times count from the first [push] after this. */
    fun begin() {
        counters = newCounters()
        highPass = List(3) { HighPass() }
        tracks = HandTracks(aspect)
        t0 = null; tLast = 0.0; lead = 0
        reps.clear(); reported = 0
    }

    private fun toMark(r: CountedRep) =
        RepMark(Math.rint(r.t * 1000).toInt(), Math.rint(r.dur * 1000).toInt(), Math.rint(r.amp * 100).toInt())

    /**
     * The leader changes only when another counter has more reps; reps beyond those reported come from it.
     * A fast counter may lead only with [RepTuning.FAST_MIN_LEAD] reps, [RepTuning.FAST_RATIO] × the best slow
     * count and an even rhythm: removing the trend also splits slow reps into bumps.
     */
    private fun report(): List<RepMark> {
        var best = lead
        var slowBest = 0
        for (i in 0 until 3) slowBest = max(slowBest, counters[i].count)
        for (i in counters.indices) {
            val c = counters[i]
            if (c.kind === RepKind.Fast && !(c.count >= RepTuning.FAST_MIN_LEAD && c.count >= RepTuning.FAST_RATIO * slowBest && steady(c.reps))) continue
            if (c.count >= counters[best].count + (if (best == lead) RepTuning.LEAD_MARGIN else 1)) best = i
        }
        lead = best
        val leader = counters[best]
        val out = ArrayList<RepMark>()
        while (reported < leader.count) {
            val mark = toMark(leader.reps[reported])
            reps.add(mark); out.add(mark)
            reported++
        }
        return out
    }

    private fun signal(): Double {
        val leader = counters[lead]
        if (leader.locked || leader.count > 0) return leader.signal()
        // Nothing counted yet: show whichever signal moves most clearly (display only).
        var best: SignalCounter? = null
        for (c in counters) if (c.pvAxis != null && (best == null || c.movingRatio > best.movingRatio)) best = c
        return best?.signal() ?: 0.0
    }

    private fun time(t: Double): Double {
        val start = t0 ?: t.also { t0 = it }
        val ts = t - start
        tLast = max(tLast, ts)
        return ts
    }

    /** One frame's picture, 768 bytes, without looking for hands. */
    fun pushPicture(t: Double, grid: ByteArray): Output {
        val ts = time(t)
        val x = RepFeatures.grid(grid)
        counters[0].push(ts, x, 1.0)
        counters[3].push(ts, highPass[0].push(ts, x), 1.0)
        val new = report()
        return Output(reported, signal(), new, Source.entries[lead])
    }

    /** The hands the model found in the frame taken at [t]: 0…2, 42 numbers each (x, y of 21 points, 0…1 of the upright picture). */
    fun pushHands(t: Double, hands: List<FloatArray>): Output {
        val ts = time(t)
        for ((k, p) in tracks.assign(hands)) {
            val x = RepFeatures.hand(p, aspect)
            val unit = RepFeatures.handLength(p, aspect)
            counters[1 + k].push(ts, x, unit)
            counters[4 + k].push(ts, highPass[1 + k].push(ts, x), unit)
        }
        val new = report()
        return Output(reported, signal(), new, Source.entries[lead])
    }

    /** Where the set stands, without a new frame. */
    fun current(): Output = Output(reported, signal(), emptyList(), Source.entries[lead])

    /** One frame: its picture and the hands found in it. */
    fun push(t: Double, hands: List<FloatArray>, grid: ByteArray): Output {
        val a = pushPicture(t, grid)
        val b = pushHands(t, hands)
        return Output(b.count, b.signal, a.newReps + b.newReps, b.source)
    }

    /**
     * End of the set: may add a last rep whose return was cut off. [reps] becomes the leader's own reps
     * (while counting, reps reported before a change of leader came from the one before).
     */
    fun end(): Output {
        for (c in counters) c.finish(tLast)
        val new = report()
        reps.clear()
        counters[lead].reps.mapTo(reps) { toMark(it) }
        return Output(reported, 0.0, new, Source.entries[lead])
    }
}
