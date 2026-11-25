// SPDX-License-Identifier: MPL-2.0
// Grippy: counting reps with the camera phone, seen from the main phone. Not part of upstream.

package app.grippy.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The longest one set may run. */
const val REPS_MAX_SECONDS = 1200

/**
 * A camera-counted session in progress: the camera is woken, then the [sets] are done in order, each
 * started by the person when they are ready (no timer decides), counted until they tap to finish it.
 * The last finished set can be resumed (a finish tapped by accident). What the camera says arrives
 * through [receive]; [drive] runs the steps. Main thread only.
 */
class RepRun(
    initialSets: List<RepSet>,
    /** Seconds between tapping Start and counting, to get into position. */
    val startAfter: Int,
    /** Where a new exercise goes when the sets were not started from one. */
    val folderId: Long? = null,
    val planName: String? = null,
    /** The plan the sets came from: a weight changed during the session is kept in it for next time. */
    val planId: Long? = null,
) {
    /** The sets in order; their weights can change during the session ([setWeight]). */
    val sets = mutableStateListOf<RepSet>().apply { addAll(initialSets) }

    /** Ready: between sets, waiting for Start (the rest is tracked, never enforced). */
    enum class Stage { Connecting, Waking, Ready, Countdown, Counting, Finishing, Done }

    /** One set's result. */
    class Result(
        val set: RepSet,
        /** First rep's start to last rep's end; the whole set when nothing was counted. */
        val seconds: Double,
        /** How long the camera counted, start to finish (both parts when the set was resumed). */
        val wallSeconds: Double,
        val marks: List<RepMark>,
        /** The movement the camera followed: seconds into the set, and 0…100 across its range. */
        val signal: List<ForcePoint>,
        val tracking: Tracking,
        val takenAt: Long,
    ) {
        /** The count that will be saved; the person can correct it before saving. */
        var reps by mutableIntStateOf(marks.size)
        /** The weight that will be saved; the person can correct it before saving. */
        var weightKg by mutableStateOf(set.weightKg)
    }

    private sealed interface Action {
        data object Start : Action
        data object Resume : Action
        data object Skip : Action
        data object Finish : Action
    }

    /** The camera's own tally of the set that just ended, still on its way. */
    private var tallying: Job? = null
    /** Counting down: start now instead. */
    private var skipCountdown = false

    var stage by mutableStateOf(Stage.Connecting)
        private set
    /** The set being counted, or the next one while Ready ([sets].size when all are done). */
    var index by mutableIntStateOf(0)
        private set
    val set: RepSet? get() = sets.getOrNull(index)
    val hand: Hand get() = (set ?: sets.last()).hand
    val allDone: Boolean get() = index >= sets.size
    /** The set being counted (or counted down to) right now. */
    var countingIndex by mutableIntStateOf(0)
        private set
    private var front = false
    /** The set that finished last, while it can still be resumed. */
    var lastDone by mutableStateOf<Int?>(null)
        private set
    /** When the last set ended (elapsedRealtime ms), for the rest timer; 0 before the first. */
    var restFrom by mutableLongStateOf(0L)
        private set
    /** Counting a set again that was already counted: its earlier reps, shown on top of the new ones. */
    var resumedFrom by mutableIntStateOf(0)
        private set
    var countdown by mutableIntStateOf(startAfter)
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
    val results = mutableStateMapOf<Int, Result>()
    var startedAt: Long = System.currentTimeMillis()
        private set
    var started = false

    private var finishRequested = false
    private var summary: CompletableDeferred<CamEvent.Summary>? = null
    private val actions = Channel<Action>(Channel.CONFLATED)

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

    /** Ready: count the next set. */
    fun start() { actions.trySend(Action.Start) }
    /** Ready: count the last finished set again, adding to what it has. */
    fun resume() { actions.trySend(Action.Resume) }
    /** Ready: leave the next set out. */
    fun skip() { actions.trySend(Action.Skip) }
    /** Ready: the session is over; on to the results. */
    fun finishSession() { actions.trySend(Action.Finish) }
    /** Counting: this set is done (the card was tapped). */
    fun finishSet() { finishRequested = true }
    /** Counting down: no need to wait, start counting now. */
    fun startNow() { skipCountdown = true }

    /**
     * Set [i] is done with [kg] from now on, and so are the sets right after it of the same exercise at
     * the same weight (the other arm). Returns the weight it had, or null when nothing changed.
     */
    fun setWeight(i: Int, kg: Double): Double? {
        val set = sets.getOrNull(i) ?: return null
        val old = set.weightKg
        if (old == kg) return null
        var j = i
        while (j < sets.size && sets[j].exerciseId == set.exerciseId && sets[j].weightKg == old) {
            sets[j] = sets[j].copy(weightKg = kg)
            j++
        }
        return old
    }

    fun restart() {
        results.clear(); signal.clear(); marks.clear()
        index = 0
        lastDone = null
        restFrom = 0L
        stage = Stage.Connecting
        started = false
    }

    enum class Cue { Tick, Go, Stop }

    /** Screenshots only: puts the session in a state without a camera phone. */
    internal fun preview(stage: Stage, index: Int, counting: Int, count: Int, elapsed: Double, restSecondsAgo: Int, done: Map<Int, Result>) {
        this.stage = stage
        this.index = index
        countingIndex = counting
        this.count = count
        this.elapsed = elapsed
        results.putAll(done)
        lastDone = done.keys.maxOrNull()
        restFrom = if (restSecondsAgo > 0) SystemClock.elapsedRealtime() - restSecondsAgo * 1000L else 0L
        tracking = Tracking.Hand
        cameraFps = 30
        marks.addAll(List(count) { RepMark((it + 1) * 1100, 1100, 100) })
        signal.addAll(List((elapsed * 10).toInt()) { ForcePoint(it / 10.0, 50 + 45 * kotlin.math.sin(it / 10.0 * 2 * Math.PI / 1.1)) })
    }

    /** Runs the session until the person finishes it. */
    suspend fun drive(client: CamClient, frontCamera: Boolean, title: (RepSet) -> String, cue: (Cue) -> Unit) {
        startedAt = System.currentTimeMillis()
        front = frontCamera
        stage = Stage.Connecting
        snapshotFlow { client.link }.first { it == CamClient.Link.Ready }
        stage = Stage.Waking
        val wakeFrom = System.nanoTime()
        // The camera phone may be asleep and locked: keep asking until it says the camera is open.
        while (true) {
            if (client.link == CamClient.Link.Ready) client.send(CamCommand.Wake(frontCamera, sets.firstOrNull()?.let(title) ?: ""))
            val open = withTimeoutOrNull(WAKE_RETRY_MS) {
                snapshotFlow { client.status?.state }.first { it == CamState.Framing || it == CamState.Counting }
            }
            if (open != null) break
            wakingFor = ((System.nanoTime() - wakeFrom) / 1e9).toInt()
        }
        coroutineScope {
            while (true) {
                stage = Stage.Ready
                when (actions.receive()) {
                    Action.Start -> {
                        if (allDone) continue
                        val i = index
                        count(client, i, title, cue)
                        lastDone = i
                        index = i + 1
                    }
                    Action.Resume -> {
                        val again = lastDone ?: continue
                        count(client, again, title, cue)
                    }
                    Action.Skip -> if (!allDone) index++
                    Action.Finish -> {
                        // The last set's own tally, if it is still coming, before the review.
                        if (tallying?.isActive == true) stage = Stage.Finishing
                        tallying?.join()
                        break
                    }
                }
            }
        }
        client.send(CamCommand.Sleep)
        stage = Stage.Done
    }

    /**
     * Counts set [i] once; a set that already has a result gets the new reps added after it. Returns as
     * soon as the set is finished, with what arrived live; the camera's own tally replaces it when it comes.
     */
    private suspend fun CoroutineScope.count(client: CamClient, i: Int, title: (RepSet) -> String, cue: (Cue) -> Unit) {
        countingIndex = i
        // The previous set's tally first: the camera answers in order, so it is normally already here.
        tallying?.join()
        val before = results[i]
        resumedFrom = before?.reps ?: 0
        count = 0
        elapsed = 0.0
        signal.clear()
        marks.clear()
        finishRequested = false
        skipCountdown = false
        if (client.link == CamClient.Link.Ready) client.send(CamCommand.Wake(front, title(sets[i])))
        stage = Stage.Countdown
        countdown@ for (s in startAfter downTo 1) {
            countdown = s
            if (s <= 3) cue(Cue.Tick)
            for (k in 0 until 10) {
                if (skipCountdown) break@countdown
                delay(100)
            }
        }
        countdown = 0
        val done = CompletableDeferred<CamEvent.Summary>()
        summary = done
        val takenAt = System.currentTimeMillis()
        client.send(CamCommand.Begin(sets[i].hand))
        val from = System.nanoTime()
        stage = Stage.Counting
        cue(Cue.Go)
        while (!finishRequested && !done.isCompleted) {
            val now = (System.nanoTime() - from) / 1e9
            // The screen shows seconds: a few updates a second are plenty (each one redraws the card).
            if (now - elapsed >= 0.25) elapsed = now
            if (now >= REPS_MAX_SECONDS) break
            delay(30)
        }
        elapsed = (System.nanoTime() - from) / 1e9
        // On to the rest straight away with what arrived live; the camera's own count follows.
        if (client.link == CamClient.Link.Ready && !done.isCompleted) client.send(CamCommand.End)
        val live = Result(sets[i], activeSeconds(marks.toList(), elapsed), elapsed, marks.toList(), signal.toList(), tracking, takenAt)
        results[i] = if (before == null) live else join(before, live)
        restFrom = SystemClock.elapsedRealtime()
        cue(Cue.Stop)
        tallying = launch {
            // Ask for the camera's own tally; if the link is down, what arrived live stands.
            var tally: CamEvent.Summary? = if (done.isCompleted) done.await() else withTimeoutOrNull(END_WAIT_MS) { done.await() }
            var asked = 1
            while (tally == null && asked < END_TRIES) {
                if (client.link == CamClient.Link.Ready) client.send(CamCommand.End)
                tally = withTimeoutOrNull(END_WAIT_MS) { done.await() }
                asked++
            }
            if (summary === done) summary = null
            if (tally == null) return@launch
            val shown = results[i]
            val wall = tally.durationMs / 1000.0
            val now = Result(sets[i], activeSeconds(tally.reps, wall), wall, tally.reps, live.signal, tally.tracking, takenAt)
            val merged = if (before == null) now else join(before, now)
            // A weight changed meanwhile stays.
            shown?.weightKg?.let { merged.weightKg = it }
            results[i] = merged
        }
    }

    companion object {
        private const val WAKE_RETRY_MS = 4_000L

        /** A resumed set: the second part's reps and movement follow the first part's. */
        fun join(first: Result, second: Result): Result {
            val shiftMs = (first.wallSeconds * 1000).toInt()
            val marks = first.marks + second.marks.map { it.copy(tMs = it.tMs + shiftMs) }
            val signal = first.signal + second.signal.map { ForcePoint(it.t + first.wallSeconds, it.kg) }
            val wall = first.wallSeconds + second.wallSeconds
            return Result(second.set, activeSeconds(marks, wall), wall, marks, signal, second.tracking, first.takenAt).also {
                it.reps = first.reps + second.reps
                it.weightKg = second.weightKg
            }
        }
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
