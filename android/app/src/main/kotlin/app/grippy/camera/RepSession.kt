// SPDX-License-Identifier: MPL-2.0
// Grippy: one stretch of the camera being open — it shows the hand and counts sets when told to.
// No Android in here, so it can be replayed in tests. Not part of upstream.

package app.grippy.camera

/** One hand as the camera saw it: 21 points as x, y pairs (0…1 of the upright picture). */
class SeenHand(val points: FloatArray)

/** Wraps [RepCounter] for the camera screen: counting only between [begin] and [end], the hand to draw always. */
class RepSession(aspect: Float) {
    class Output(
        val count: Int,
        /** −100 (home) … 100 (far side) of the movement being followed. */
        val signal: Int,
        val tracking: Tracking,
        val newReps: List<RepMark>,
        /** The hand to draw this frame, if any. */
        val hand: SeenHand?,
    )

    private val counter = RepCounter(aspect.toDouble())

    var counting = false
        private set
    private var beganAt = 0.0
    private var firstFrameAt: Double? = null
    private var lastT = 0.0

    /** Reps carry times from the first frame after begin(); the set's clock starts at begin(). */
    private val offsetMs: Int get() = (((firstFrameAt ?: beganAt) - beganAt) * 1000).toInt()

    val reps: List<RepMark> get() = counter.reps.map { it.copy(tMs = it.tMs + offsetMs) }

    /** [luma]: the picture shrunk to [RepFeatures.GRID_W]×[RepFeatures.GRID_H] brightness bytes. */
    fun frame(t: Double, hands: List<SeenHand>, luma: ByteArray): Output {
        lastT = t
        if (!counting) {
            return Output(0, 0, if (hands.isNotEmpty()) Tracking.Hand else Tracking.None, emptyList(), hands.firstOrNull())
        }
        if (firstFrameAt == null) firstFrameAt = t
        val out = counter.push(t, hands.map { it.points }, luma)
        val tracking = when {
            out.source != RepCounter.Source.Picture -> Tracking.Hand
            hands.isNotEmpty() && out.count == 0 -> Tracking.Hand
            else -> Tracking.Motion
        }
        val draw = if (out.source == RepCounter.Source.HandB) hands.getOrNull(1) ?: hands.firstOrNull() else hands.firstOrNull()
        val offset = offsetMs
        return Output(out.count, (out.signal * 100).toInt(), tracking, out.newReps.map { it.copy(tMs = it.tMs + offset) }, draw)
    }

    fun begin(t: Double) {
        counter.begin()
        counting = true
        beganAt = t
        firstFrameAt = null
    }

    fun end(): CamEvent.Summary {
        val last = counter.end()
        counting = false
        val tracking = if (last.source == RepCounter.Source.Picture) Tracking.Motion else Tracking.Hand
        return CamEvent.Summary(((lastT - beganAt) * 1000).toInt().coerceAtLeast(0), tracking, reps)
    }
}
