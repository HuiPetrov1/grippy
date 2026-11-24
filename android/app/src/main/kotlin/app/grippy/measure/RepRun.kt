// SPDX-License-Identifier: MPL-2.0
// Grippy: counting reps with the camera phone, seen from the main phone. Not part of upstream.

package app.grippy.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.grippy.camera.CamClient
import app.grippy.camera.CamCodec
import app.grippy.camera.CamCommand
import app.grippy.camera.CamEvent
import app.grippy.camera.CamState
import app.grippy.camera.RepMark
import app.grippy.camera.Tracking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The longest one set may run. */
const val REPS_MAX_SECONDS = 1200

/**
 * One camera-counted measurement in progress: the camera is woken, then each hand gets a countdown
 * and a set that runs until Stop. What the camera says arrives through [receive]; [drive] runs the
 * steps. Main thread only.
 */
class RepRun(val request: MeasureRequest) {
    enum class Stage { Connecting, Waking, Countdown, Counting, Finishing, Done }

    /** One hand's finished set. */
    class Result(
        /** First rep's start to last rep's end; the whole set when nothing was counted. */
        val seconds: Double,
        val marks: List<RepMark>,
        /** The movement the camera followed: seconds into the set, and 0…100 across its range. */
        val signal: List<ForcePoint>,
        val tracking: Tracking,
    ) {
        /** The count that will be saved; the person can correct it before saving. */
        var reps by mutableIntStateOf(marks.size)
    }

    var stage by mutableStateOf(Stage.Connecting)
        private set
    var handIndex by mutableIntStateOf(0)
        private set
    val hand: Hand get() = request.hands[handIndex]
    val isLastHand: Boolean get() = handIndex >= request.hands.lastIndex
    var countdown by mutableIntStateOf(request.startAfter)
        private set
    var elapsed by mutableDoubleStateOf(0.0)
        private set
    var count by mutableIntStateOf(0)
        private set
    var tracking by mutableStateOf(Tracking.None)
        private set
    /** Frames a second the camera phone counts from (0 until it says). */
    var cameraFps by mutableIntStateOf(0)
        private set
    /** The hand the camera sees, for the little preview: 21 x, y pairs, or null. */
    var seen by mutableStateOf<FloatArray?>(null)
        private set
    var aspect by mutableStateOf(0.75f)
        private set
    /** How long the camera has been asked to wake without answering, in seconds. */
    var wakingFor by mutableIntStateOf(0)
        private set
    val signal = mutableStateListOf<ForcePoint>()
    val marks = mutableStateListOf<RepMark>()
    val results = mutableStateMapOf<Hand, Result>()
    var startedAt: Long = System.currentTimeMillis()
        private set
    val sessionId: String = java.util.UUID.randomUUID().toString()
    var started = false

    private var finishRequested = false
    private var summary: CompletableDeferred<CamEvent.Summary>? = null

    fun receive(event: CamEvent) {
        when (event) {
            is CamEvent.Status -> { tracking = event.tracking; if (event.fps > 0) cameraFps = event.fps }
            is CamEvent.Frame -> {
                tracking = event.tracking
                seen = CamCodec.unpackHand(event.points)
                aspect = (event.aspect / 100f).coerceIn(0.3f, 3f)
                if (stage == Stage.Counting) {
                    count = maxOf(count, event.count)
                    val t = event.tMs / 1000.0
                    if (signal.isEmpty() || t > signal.last().t) signal.add(ForcePoint(t, (event.signal + 100) / 2.0))
                }
            }
            is CamEvent.Rep -> if (stage == Stage.Counting && marks.none { it.tMs == event.mark.tMs }) {
                marks.add(event.mark)
                count = maxOf(count, event.count)
            }
            is CamEvent.Summary -> summary?.complete(event)
        }
    }

    enum class Cue { Tick, Go, Stop }

    /** Runs every hand to the end. [exercise] is shown on the camera phone. */
    suspend fun drive(client: CamClient, exercise: String, frontCamera: Boolean, cue: (Cue) -> Unit) {
        startedAt = System.currentTimeMillis()
        stage = Stage.Connecting
        snapshotFlow { client.link }.first { it == CamClient.Link.Ready }
        stage = Stage.Waking
        val wakeFrom = System.nanoTime()
        // The camera phone may be asleep and locked: keep asking until it says the camera is open.
        while (true) {
            if (client.link == CamClient.Link.Ready) client.send(CamCommand.Wake(frontCamera, exercise))
            val open = withTimeoutOrNull(WAKE_RETRY_MS) {
                snapshotFlow { client.status?.state }.first { it == CamState.Framing || it == CamState.Counting }
            }
            if (open != null) break
            wakingFor = ((System.nanoTime() - wakeFrom) / 1e9).toInt()
        }
        for (i in request.hands.indices) {
            handIndex = i
            count = 0
            elapsed = 0.0
            signal.clear()
            marks.clear()
            finishRequested = false
            stage = Stage.Countdown
            for (s in request.startAfter downTo 1) {
                countdown = s
                if (s <= 3) cue(Cue.Tick)
                delay(1000)
            }
            countdown = 0
            val done = CompletableDeferred<CamEvent.Summary>()
            summary = done
            client.send(CamCommand.Begin(hand))
            val from = System.nanoTime()
            stage = Stage.Counting
            cue(Cue.Go)
            while (!finishRequested && !done.isCompleted) {
                elapsed = (System.nanoTime() - from) / 1e9
                if (elapsed >= REPS_MAX_SECONDS) break
                delay(50)
            }
            stage = Stage.Finishing
            // Ask for the camera's own tally; if the link is down, what arrived live stands.
            var tally: CamEvent.Summary? = if (done.isCompleted) done.await() else null
            var asked = 0
            while (tally == null && asked < END_TRIES) {
                if (client.link == CamClient.Link.Ready) client.send(CamCommand.End)
                tally = withTimeoutOrNull(END_WAIT_MS) { done.await() }
                asked++
            }
            summary = null
            val counted = tally?.reps ?: marks.toList()
            results[hand] = Result(
                seconds = activeSeconds(counted, tally?.durationMs?.div(1000.0) ?: elapsed),
                marks = counted, signal = signal.toList(), tracking = tally?.tracking ?: tracking,
            )
            cue(Cue.Stop)
        }
        client.send(CamCommand.Sleep)
        stage = Stage.Done
    }

    /** This hand is done (the person tapped Stop); the next hand, or the result, follows. */
    fun finishHand() { finishRequested = true }

    /** A rotation cancelled the steps mid-run: start over rather than hang. */
    fun restart() {
        results.clear(); signal.clear(); marks.clear()
        handIndex = 0
        stage = Stage.Connecting
        started = false
    }

    companion object {
        private const val WAKE_RETRY_MS = 4_000L
        private const val END_WAIT_MS = 2_500L
        private const val END_TRIES = 4

        /** From the first rep starting to the last one finishing; without reps, the whole set. */
        fun activeSeconds(marks: List<RepMark>, whole: Double): Double {
            val first = marks.firstOrNull() ?: return whole
            val from = (first.tMs - first.durationMs).coerceAtLeast(0)
            return ((marks.last().tMs - from) / 1000.0).coerceAtLeast(0.0)
        }

        fun secondsToSave(result: Result): Int = result.seconds.roundToInt().coerceAtLeast(1)
    }
}
