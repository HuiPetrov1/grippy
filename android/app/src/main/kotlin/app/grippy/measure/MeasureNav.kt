// SPDX-License-Identifier: MPL-2.0
// Grippy: where the Measure section is, and the state of a measurement in progress.
// Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlin.math.max

/** What a measurement is about to do. */
data class MeasureRequest(
    val type: MeasureType,
    val hands: List<Hand>,
    val seconds: Int,
    val startAfter: Int,
    /** Known when started from an exercise; otherwise picked after measuring. */
    val exerciseId: Long? = null,
    val folderId: Long? = null,
)

/** Where a "Measure" tap came from, so the sheets can preset what they know. */
data class StartContext(
    val exerciseId: Long? = null,
    val folderId: Long? = null,
    val type: MeasureType? = null,
    val seconds: Int? = null,
)

sealed interface MeasureRoute {
    /** tab: 0 Summary, 1 Measure, 2 How to. */
    data class Exercise(val id: Long, val tab: Int = 1) : MeasureRoute
    data class Group(val exerciseId: Long, val key: String) : MeasureRoute
    data class Result(val sessionId: String) : MeasureRoute
    class Run(val run: MeasureRun) : MeasureRoute
    class Review(val run: MeasureRun) : MeasureRoute
}

/** The Measure section's back stack and its two sheets. Outlives a rotation. */
class MeasureNavigator : ViewModel() {
    val stack = mutableStateListOf<MeasureRoute>()
    /** "How do you want to start?" */
    var startSheet by mutableStateOf<StartContext?>(null)
    /** "Select hand to measure", with the type chosen. */
    var handSheet by mutableStateOf<StartContext?>(null)

    val top: MeasureRoute? get() = stack.lastOrNull()

    fun push(route: MeasureRoute) { stack.add(route) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun replaceTop(route: MeasureRoute) { pop(); push(route) }

    fun openStart(context: StartContext = StartContext()) { handSheet = null; startSheet = context }

    fun chooseType(type: MeasureType) {
        val context = startSheet ?: StartContext()
        startSheet = null
        handSheet = context.copy(type = type)
    }

    fun begin(request: MeasureRequest) {
        handSheet = null
        startSheet = null
        push(MeasureRoute.Run(MeasureRun(request)))
    }

    /** After Save: the exercise's Measure tab, with the list underneath. */
    fun showExercise(id: Long) {
        while (stack.isNotEmpty() && (top is MeasureRoute.Run || top is MeasureRoute.Review)) pop()
        val existing = stack.indexOfFirst { it is MeasureRoute.Exercise && it.id == id }
        if (existing >= 0) {
            while (stack.size > existing + 1) pop()
        } else {
            push(MeasureRoute.Exercise(id, 1))
        }
    }
}

/**
 * One measurement in progress: a countdown and a recording per hand. Readings arrive from
 * the scale through [receive]; [drive] runs the clock. Both are on the main thread.
 */
class MeasureRun(val request: MeasureRequest) {
    enum class Stage { Connecting, Countdown, Recording, Done }

    var stage by mutableStateOf(Stage.Connecting)
        private set
    var handIndex by mutableIntStateOf(0)
        private set
    val hand: Hand get() = request.hands[handIndex]
    var countdown by mutableIntStateOf(request.startAfter)
        private set
    /** Seconds into the current recording. */
    var elapsed by mutableDoubleStateOf(0.0)
        private set
    var liveKg by mutableDoubleStateOf(0.0)
        private set
    var peakKg by mutableDoubleStateOf(0.0)
        private set
    /** The current hand's readings, for the live curve. */
    val points = mutableStateListOf<ForcePoint>()
    val results = mutableStateMapOf<Hand, List<ForcePoint>>()
    var startedAt: Long = System.currentTimeMillis()
        private set
    /** The id every hand of this run is saved under. */
    val sessionId: String = java.util.UUID.randomUUID().toString()
    var started = false

    private var recordStart = 0L

    val windowSeconds: Int
        get() = if (request.type == MeasureType.CriticalForce) CF_TOTAL_SECONDS else request.seconds

    /** Critical Force: which rep, and whether it is a pull or a rest. */
    val cfRep: Int get() = (elapsed / (CF_WORK_SECONDS + CF_REST_SECONDS)).toInt().coerceAtMost(CF_REPS - 1)
    val cfPulling: Boolean get() = elapsed % (CF_WORK_SECONDS + CF_REST_SECONDS) < CF_WORK_SECONDS
    val cfPhaseLeft: Double
        get() {
            val into = elapsed % (CF_WORK_SECONDS + CF_REST_SECONDS)
            return if (into < CF_WORK_SECONDS) CF_WORK_SECONDS - into else CF_WORK_SECONDS + CF_REST_SECONDS - into
        }

    fun receive(kg: Double, now: Long = System.nanoTime()) {
        liveKg = kg
        if (stage != Stage.Recording) return
        val t = (now - recordStart) / 1e9
        if (t < 0 || t > windowSeconds) return
        points.add(ForcePoint(t, kg))
        peakKg = max(peakKg, kg)
    }

    enum class Cue { Tick, Go, Rest, Stop }

    /** Runs every hand to the end. [tare] zeroes the scale before each countdown. */
    suspend fun drive(tare: suspend () -> Unit, cue: (Cue) -> Unit) {
        startedAt = System.currentTimeMillis()
        for (i in request.hands.indices) {
            handIndex = i
            points.clear()
            peakKg = 0.0
            elapsed = 0.0
            stage = Stage.Countdown
            tare()
            for (s in request.startAfter downTo 1) {
                countdown = s
                if (s <= 3) cue(Cue.Tick)
                delay(1000)
            }
            countdown = 0
            recordStart = System.nanoTime()
            stage = Stage.Recording
            points.add(ForcePoint(0.0, liveKg))
            cue(Cue.Go)
            var wasPulling = true
            while (true) {
                val t = (System.nanoTime() - recordStart) / 1e9
                elapsed = t.coerceAtMost(windowSeconds.toDouble())
                if (t >= windowSeconds) break
                if (request.type == MeasureType.CriticalForce && cfPulling != wasPulling) {
                    wasPulling = cfPulling
                    cue(if (wasPulling) Cue.Go else Cue.Rest)
                }
                delay(40)
            }
            points.add(ForcePoint(windowSeconds.toDouble(), liveKg))
            results[hand] = points.toList()
            cue(Cue.Stop)
        }
        stage = Stage.Done
    }

    /** A rotation cancelled the clock mid-run: start this run over rather than hang. */
    fun restart() {
        results.clear()
        points.clear()
        handIndex = 0
        stage = Stage.Connecting
        started = false
    }

    /** Skips ahead to the result with what was recorded so far (the person tapped Stop). */
    fun stopEarly() {
        if (stage == Stage.Recording && points.isNotEmpty()) results[hand] = points.toList()
        stage = Stage.Done
    }
}
