// SPDX-License-Identifier: MPL-2.0
package app.grippy.camera

import app.grippy.measure.Hand
import app.grippy.measure.RepRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** What the two phones say to each other survives the trip, whole or in pieces. */
class CamProtocolTests {
    @Test
    fun everyCommandComesBackTheSame() {
        val commands = listOf(
            CamCommand.Hello, CamCommand.Wake(true, "wrist curl"), CamCommand.Wake(false, ""),
            CamCommand.Begin(Hand.Left), CamCommand.Begin(Hand.Right), CamCommand.End, CamCommand.Sleep,
        )
        for (c in commands) assertEquals(c, CamCodec.decodeCommand(CamCodec.encode(c)))
    }

    @Test
    fun aLongExerciseNameIsCutNotBroken() {
        val name = "ü".repeat(200)
        val back = CamCodec.decodeCommand(CamCodec.encode(CamCommand.Wake(true, name))) as CamCommand.Wake
        assertTrue(back.exercise.isNotEmpty() && back.exercise.all { it == 'ü' })
        assertTrue(back.exercise.toByteArray().size <= 80)
    }

    @Test
    fun everyEventComesBackTheSame() {
        val status = CamEvent.Status(CamState.Counting, Tracking.Hand, 24, 87, note = "ok")
        assertEquals(status, CamCodec.decodeEvent(CamCodec.encode(status)))

        val rep = CamEvent.Rep(12, RepMark(34_567, 2_100, 96))
        assertEquals(rep, CamCodec.decodeEvent(CamCodec.encode(rep)))

        val summary = CamEvent.Summary(61_234, Tracking.Motion, List(150) { RepMark(it * 400, 380, 100 - it % 7) })
        assertEquals(summary, CamCodec.decodeEvent(CamCodec.encode(summary)))

        val hand = FloatArray(42) { it / 41f }
        val frame = CamEvent.Frame(5_000, 7, -42, Tracking.Hand, 75, CamCodec.packHand(hand))
        val back = CamCodec.decodeEvent(CamCodec.encode(frame)) as CamEvent.Frame
        assertEquals(5_000, back.tMs); assertEquals(7, back.count); assertEquals(-42, back.signal)
        assertEquals(Tracking.Hand, back.tracking); assertEquals(75, back.aspect)
        val unpacked = CamCodec.unpackHand(back.points)!!
        for (i in hand.indices) assertEquals(hand[i], unpacked[i], 0.003f)

        val empty = CamCodec.decodeEvent(CamCodec.encode(CamEvent.Frame(0, 0, 0, Tracking.None, 75, ByteArray(0)))) as CamEvent.Frame
        assertNull(CamCodec.unpackHand(empty.points))
    }

    @Test
    fun rubbishIsIgnoredNotCrashedOn() {
        assertNull(CamCodec.decodeCommand(byteArrayOf()))
        assertNull(CamCodec.decodeCommand(byteArrayOf(0x7f)))
        assertNull(CamCodec.decodeEvent(byteArrayOf(0x84.toByte(), 1)))
    }

    @Test
    fun aLongMessageCrossesInSmallPacketsAndComesOutWhole() {
        val message = CamCodec.encode(CamEvent.Summary(90_000, Tracking.Hand, List(120) { RepMark(it * 700, 650, 90) }))
        for (packet in listOf(20, 100, 244, 2000)) {
            val parts = CamPackets.split(message, packet)
            assertTrue(parts.all { it.size <= packet })
            val assembler = CamPackets.Assembler()
            var whole: ByteArray? = null
            for ((i, part) in parts.withIndex()) {
                val out = assembler.accept(part)
                if (i < parts.lastIndex) assertNull(out) else whole = out
            }
            assertArrayEquals(message, whole)
            // And the next message starts clean.
            assertArrayEquals(byteArrayOf(4), assembler.accept(CamPackets.split(byteArrayOf(4), packet).single()))
        }
    }
}

/** Reps are counted from a signal that swings; stillness, jitter and one-off moves are not reps. Synthetic signals. */
class RepCounterTests {
    private val dim = RepTuning.HAND_DIM
    private val random = Random(7)
    private val base = DoubleArray(dim) { random.nextDouble() }
    private val direction = DoubleArray(dim) { random.nextDouble() - .5 }.also { d ->
        val norm = kotlin.math.sqrt(d.sumOf { it * it })
        for (i in d.indices) d[i] /= norm
    }

    /** A frame where the movement is at [position] along its path, with a little camera jitter. */
    private fun frame(position: Double, jitter: Double = 0.004): DoubleArray =
        DoubleArray(dim) { base[it] + direction[it] * position + random.nextDouble(-jitter, jitter) }

    /** Rest, then [reps] swings (0 → range → 0) of [period] seconds, then rest. Unit 1, so minRange is in the same units. */
    private fun play(
        reps: Int, period: Double, range: Double, fps: Int, restBefore: Double = 2.5, restAfter: Double = 2.0,
        drop: (Int) -> Boolean = { false }, pauseAfterRep: Int = -1, pauseSec: Double = 0.0,
    ): SignalCounter {
        val counter = SignalCounter(dim, 0.05)
        var t = 0.0
        var n = 0
        fun push(position: Double) {
            if (!drop(n++)) counter.push(t, frame(position), 1.0)
            t += 1.0 / fps
        }
        repeat((restBefore * fps).toInt()) { push(0.0) }
        for (rep in 0 until reps) {
            val frames = (period * fps).toInt()
            for (i in 0 until frames) push(range * (1 - kotlin.math.cos(2 * PI * i / frames)) / 2)
            if (rep == pauseAfterRep) repeat((pauseSec * fps).toInt()) { push(0.0) }
        }
        repeat((restAfter * fps).toInt()) { push(0.0) }
        counter.finish(t)
        return counter
    }

    /**
     * The user's case: wrist extensions about once a second with a short range, while the arm or body sways
     * slowly and much further. The slow counter follows the sway; the fast one (high-passed) counts the reps.
     */
    @Test
    fun quickSmallRepsOnASlowSwayAreCountedByTheFastCounter() {
        val sway = DoubleArray(dim) { random.nextDouble() - .5 }.also { d ->
            val norm = kotlin.math.sqrt(d.sumOf { it * it })
            for (i in d.indices) d[i] /= norm
        }
        val slow = SignalCounter(dim, 0.05)
        val fast = SignalCounter(dim, 0.05, RepKind.Fast)
        val highPass = HighPass()
        val fps = 30
        val reps = 40
        var t = 0.0
        repeat(2 * fps) { val x = frame(0.0); slow.push(t, x, 1.0); fast.push(t, highPass.push(t, x), 1.0); t += 1.0 / fps }
        val start = t
        for (i in 0 until reps * fps) {
            val rep = 0.12 * (1 - kotlin.math.cos(2 * PI * i / fps)) / 2
            val x = frame(rep).also { v -> for (k in v.indices) v[k] += sway[k] * 0.6 * kotlin.math.sin(2 * PI * (t - start) / 9.0) }
            slow.push(t, x, 1.0)
            fast.push(t, highPass.push(t, x), 1.0)
            t += 1.0 / fps
        }
        repeat(2 * fps) { val x = frame(0.0); slow.push(t, x, 1.0); fast.push(t, highPass.push(t, x), 1.0); t += 1.0 / fps }
        slow.finish(t); fast.finish(t)
        assertTrue("fast counted ${fast.count} of $reps", fast.count in reps - 1..reps + 1)
        assertTrue("slow counted ${slow.count}: it should follow the sway, not the reps", slow.count < reps / 2)
    }

    @Test
    fun tenSteadyRepsAreTen() {
        assertEquals(10, play(10, period = 1.6, range = 0.5, fps = 30).count)
        assertEquals(10, play(10, period = 1.6, range = 0.5, fps = 15).count)
    }

    @Test
    fun smallAndSlowAndFastAreAllCounted() {
        assertEquals(8, play(8, period = 3.0, range = 0.18, fps = 20).count)
        assertEquals(12, play(12, period = 0.8, range = 0.4, fps = 30).count)
    }

    @Test
    fun droppedFramesDoNotChangeTheCount() {
        assertEquals(10, play(10, period = 1.5, range = 0.5, fps = 30, drop = { it % 5 == 4 }).count)
    }

    @Test
    fun aRestInTheMiddleOfTheSetIsNotARepAndTheSetGoesOn() {
        assertEquals(9, play(9, period = 1.5, range = 0.5, fps = 30, pauseAfterRep = 4, pauseSec = 15.0).count)
    }

    @Test
    fun holdingStillCountsNothing() {
        assertEquals(0, play(0, period = 1.0, range = 0.0, fps = 30, restBefore = 12.0).count)
    }

    @Test
    fun twoRepsAreTooFewToBeSureOf() {
        assertEquals(0, play(2, period = 1.5, range = 0.5, fps = 30).count)
    }

    @Test
    fun gettingIntoPositionOnceIsNotARep() {
        val counter = SignalCounter(dim, 0.05)
        var t = 0.0
        repeat(60) { counter.push(t, frame(0.0), 1.0); t += 1 / 30.0 }
        repeat(30) { i -> counter.push(t, frame(0.6 * i / 30), 1.0); t += 1 / 30.0 }
        repeat(240) { counter.push(t, frame(0.6), 1.0); t += 1 / 30.0 }
        assertEquals(0, counter.count)
    }

    @Test
    fun everyRepSaysWhenItFinishedAndRoughlyHowLongItTook() {
        val counter = play(6, period = 2.0, range = 0.5, fps = 30)
        assertEquals(6, counter.count)
        val gaps = counter.reps.zipWithNext { a, b -> b.t - a.t }
        for (gap in gaps) assertEquals(2.0, gap, 0.25)
        for (r in counter.reps.drop(1)) assertEquals(2.0, r.dur, 0.6)
    }
}

/**
 * The port against the reference: the features of 22 real clips (plus 3 quick-rep sets where the hands are
 * looked for only every 2nd or 3rd frame, as on the phone) replayed frame by frame must give the
 * reference's counts, reps and signal. The fixtures come from the counting lab (`kotlin_fixtures/`, made
 * from internet exercise videos, so they are not kept in this repository): set GRIPPY_REPLAB to that
 * folder to run this; without it the test does nothing.
 */
class RepCounterReferenceTests {
    @Test
    fun thePortCountsExactlyLikeTheReference() {
        val dir = System.getenv("GRIPPY_REPLAB")?.let { java.io.File(it) }?.takeIf { it.isDirectory } ?: return
        val clips = dir.listFiles { f -> f.name.endsWith(".bin") }!!.sortedBy { it.name }
        assertTrue(clips.isNotEmpty())
        val problems = ArrayList<String>()
        var worstSignal = 0.0
        for (bin in clips) {
            val name = bin.name.removeSuffix(".bin")
            val lines = java.io.File(dir, "$name.txt").readLines()
            val (count, source) = lines[0].split(' ')
            val perSignal = lines[1].split(' ').map { it.toInt() }
            val reps = if (lines[2].isBlank()) emptyList() else lines[2].split(';').map { r -> r.split(',').map { it.toInt() } }
            val signal = lines[3].split(' ').map { it.toDouble() }

            val input = java.io.DataInputStream(bin.inputStream().buffered())
            val aspect = input.readDouble()
            val frames = input.readInt()
            val counter = RepCounter(aspect)
            counter.begin()
            for (i in 0 until frames) {
                val t = input.readDouble()
                val n = input.readUnsignedByte()
                val hands = List(2) { FloatArray(42) { input.readFloat() } }
                val grid = ByteArray(RepTuning.GRID_DIM).also { input.readFully(it) }
                // 255: the hand model did not look at this frame (the phone's picture runs faster than it).
                val out = if (n == 255) counter.pushPicture(t, grid) else counter.push(t, hands.take(n), grid)
                worstSignal = maxOf(worstSignal, abs(out.signal - signal[i]))
                if (abs(out.signal - signal[i]) > 1e-3) problems += "$name frame $i: signal ${out.signal} vs ${signal[i]}"
            }
            val last = counter.end()
            val sourceName = when (last.source) {
                RepCounter.Source.Picture -> "picture"
                RepCounter.Source.HandA -> "hand A"
                RepCounter.Source.HandB -> "hand B"
                RepCounter.Source.PictureFast -> "picture fast"
                RepCounter.Source.HandAFast -> "hand A fast"
                RepCounter.Source.HandBFast -> "hand B fast"
            }
            if (last.count != count.toInt()) problems += "$name: count ${last.count} vs $count"
            if (counter.perSignal != perSignal) problems += "$name: per signal ${counter.perSignal} vs $perSignal"
            if (sourceName != lines[0].substringAfter(' ')) problems += "$name: source $sourceName vs ${lines[0].substringAfter(' ')}"
            val got = counter.reps.map { listOf(it.tMs, it.durationMs, it.amplitude) }
            if (got != reps) problems += "$name: reps $got vs $reps"
        }
        println("Reference check: ${clips.size} clips, worst signal difference $worstSignal")
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }
}

/** A whole camera session: the hand is followed when it is there, plain movement when it is not. */
class RepSessionTests {
    private val aspect = 0.75f
    private val luma = ByteArray(RepFeatures.GRID_DIM) { 90 }

    /** A hand whose fingers swing about the wrist by [angle] radians, like a wrist curl seen side-on. */
    private fun hand(angle: Double): SeenHand {
        val points = FloatArray(42)
        val wx = 0.5f; val wy = 0.6f
        points[0] = wx; points[1] = wy
        for (i in 1..20) {
            val finger = (i - 1) / 4
            val joint = (i - 1) % 4 + 1
            val spread = (finger - 2) * 0.12
            val reach = 0.05 * joint
            val a = angle + spread
            points[i * 2] = (wx + reach * kotlin.math.cos(a) / aspect).toFloat()
            points[i * 2 + 1] = (wy - reach * sin(a)).toFloat()
        }
        return SeenHand(points)
    }

    @Test
    fun wristCurlsSeenAsAHandAreCounted() {
        val session = RepSession(aspect)
        var t = 0.0
        repeat(45) { session.frame(t, listOf(hand(0.2)), luma); t += 1 / 20.0 }
        session.begin(t)
        var last: RepSession.Output? = null
        repeat(40) { last = session.frame(t, listOf(hand(0.2)), luma); t += 1 / 20.0 }
        for (rep in 0 until 8) for (i in 0 until 30) {
            last = session.frame(t, listOf(hand(0.2 + 0.5 * (1 - kotlin.math.cos(2 * PI * i / 30)) / 2)), luma)
            t += 1 / 20.0
        }
        repeat(40) { last = session.frame(t, listOf(hand(0.2)), luma); t += 1 / 20.0 }
        assertEquals(Tracking.Hand, last!!.tracking)
        val summary = session.end()
        assertEquals(8, summary.reps.size)
        assertEquals(Tracking.Hand, summary.tracking)
    }

    /** The same hand drawn bigger and elsewhere: a helper hand holding the forearm still. */
    private fun stillHand(): SeenHand {
        val h = hand(1.2).points.copyOf()
        for (i in 0 until 21) { h[i * 2] = 0.15f + (h[i * 2] - 0.5f) * 1.6f; h[i * 2 + 1] = 0.3f + (h[i * 2 + 1] - 0.6f) * 1.6f }
        return SeenHand(h)
    }

    @Test
    fun theMovingHandIsFollowedNotTheBiggerStillOne() {
        val session = RepSession(aspect)
        var t = 0.0
        fun curl(i: Int) = hand(0.2 + 0.5 * (1 - kotlin.math.cos(2 * PI * i / 30)) / 2)
        // Getting ready: one warm-up swing so the camera sees which hand moves.
        for (i in 0 until 30) { session.frame(t, listOf(stillHand(), curl(i)), luma); t += 1 / 20.0 }
        repeat(20) { session.frame(t, listOf(stillHand(), hand(0.2)), luma); t += 1 / 20.0 }
        session.begin(t)
        repeat(20) { session.frame(t, listOf(stillHand(), hand(0.2)), luma); t += 1 / 20.0 }
        for (rep in 0 until 7) for (i in 0 until 30) { session.frame(t, listOf(stillHand(), curl(i)), luma); t += 1 / 20.0 }
        repeat(40) { session.frame(t, listOf(stillHand(), hand(0.2)), luma); t += 1 / 20.0 }
        assertEquals(7, session.end().reps.size)
    }

    @Test
    fun withNoHandInViewTheMovingPictureIsCounted() {
        val session = RepSession(aspect)
        var t = 0.0
        // A bright block that slides back and forth across a dark picture.
        fun picture(position: Double): ByteArray = ByteArray(RepFeatures.GRID_DIM) { i ->
            val x = i % RepFeatures.GRID_W
            val y = i / RepFeatures.GRID_W
            val left = 6 + (position * 12).toInt()
            if (y in 8..16 && x in left..left + 6) 220.toByte() else 40
        }
        repeat(40) { session.frame(t, emptyList(), picture(0.0)); t += 1 / 20.0 }
        session.begin(t)
        repeat(40) { session.frame(t, emptyList(), picture(0.0)); t += 1 / 20.0 }
        for (rep in 0 until 6) for (i in 0 until 30) {
            session.frame(t, emptyList(), picture((1 - kotlin.math.cos(2 * PI * i / 30)) / 2)); t += 1 / 20.0
        }
        repeat(40) { session.frame(t, emptyList(), picture(0.0)); t += 1 / 20.0 }
        val summary = session.end()
        assertEquals(6, summary.reps.size)
        assertEquals(Tracking.Motion, summary.tracking)
    }

    @Test
    fun nothingIsCountedBeforeBegin() {
        val session = RepSession(aspect)
        var t = 0.0
        var out: RepSession.Output? = null
        for (rep in 0 until 5) for (i in 0 until 30) {
            out = session.frame(t, listOf(hand(0.5 * (1 - kotlin.math.cos(2 * PI * i / 30)) / 2)), luma); t += 1 / 20.0
        }
        assertNotNull(out)
        assertEquals(0, out!!.count)
        assertEquals(0, session.reps.size)
    }
}

class RepRunMathTests {
    @Test
    fun aSetIsTimedFromTheFirstRepStartingToTheLastOneFinishing() {
        val marks = listOf(RepMark(3_000, 2_000, 100), RepMark(5_000, 2_000, 100), RepMark(7_500, 2_500, 95))
        assertEquals(6.5, RepRun.activeSeconds(marks, whole = 20.0), 1e-9)
        assertEquals(20.0, RepRun.activeSeconds(emptyList(), whole = 20.0), 1e-9)
    }
}
