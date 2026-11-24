// SPDX-License-Identifier: MPL-2.0
// Grippy: one stretch of the camera being open — it shows the hand and counts sets when told to.
// No Android in here, so it can be replayed in tests. Not part of upstream.

package app.grippy.camera

/** One hand as the camera saw it: 21 points as x, y pairs (0…1 of the upright picture). */
class SeenHand(val points: FloatArray)

/**
 * Wraps [RepCounter] for the camera screen: counting only between [begin] and [end], the hand to draw always.
 * The picture comes every camera frame ([picture]); the hands come when the hand model has looked at a
 * frame ([hands]), usually a few frames later and not for every frame. Not thread-safe: call from one thread.
 */
class RepSession(aspect: Float) {
    class Output(
        val count: Int,
        /** −100 (home) … 100 (far side) of the movement being followed. */
        val signal: Int,
        val tracking: Tracking,
        val newReps: List<RepMark>,
        /** The hand to draw now, if any. */
        val hand: SeenHand?,
    )

    private val counter = RepCounter(aspect.toDouble())

    var counting = false
        private set
    private var beganAt = 0.0
    private var firstFrameAt: Double? = null
    private var lastT = 0.0
    private var seen: List<SeenHand> = emptyList()

    /** Reps carry times from the first frame after begin(); the set's clock starts at begin(). */
    private val offsetMs: Int get() = (((firstFrameAt ?: beganAt) - beganAt) * 1000).toInt()

    val reps: List<RepMark> get() = counter.reps.map { it.copy(tMs = it.tMs + offsetMs) }

    /** [luma]: the picture shrunk to [RepFeatures.GRID_W]×[RepFeatures.GRID_H] brightness bytes. */
    fun picture(t: Double, luma: ByteArray): Output {
        lastT = maxOf(lastT, t)
        if (!counting) return idle()
        if (firstFrameAt == null) firstFrameAt = t
        return output(counter.pushPicture(t, luma))
    }

    /** The hands the model found in the frame taken at [t] (none is an answer too: no hand there). */
    fun hands(t: Double, found: List<SeenHand>): Output {
        seen = found
        if (!counting) return idle()
        // A frame taken before the set began, whose hands were found after it: not part of the set.
        if (t < beganAt) return output(counter.current())
        if (firstFrameAt == null) firstFrameAt = t
        return output(counter.pushHands(t, found.map { it.points }))
    }

    /** Both at once, for a frame the hand model saw straight away. */
    fun frame(t: Double, found: List<SeenHand>, luma: ByteArray): Output {
        val a = picture(t, luma)
        val b = hands(t, found)
        return Output(b.count, b.signal, b.tracking, a.newReps + b.newReps, b.hand)
    }

    private fun idle() = Output(0, 0, if (seen.isNotEmpty()) Tracking.Hand else Tracking.None, emptyList(), seen.firstOrNull())

    private fun output(out: RepCounter.Output): Output {
        val tracking = when {
            !out.source.picture -> Tracking.Hand
            seen.isNotEmpty() && out.count == 0 -> Tracking.Hand
            else -> Tracking.Motion
        }
        val second = out.source == RepCounter.Source.HandB || out.source == RepCounter.Source.HandBFast
        val draw = if (second) seen.getOrNull(1) ?: seen.firstOrNull() else seen.firstOrNull()
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
        val tracking = if (last.source.picture) Tracking.Motion else Tracking.Hand
        return CamEvent.Summary(((lastT - beganAt) * 1000).toInt().coerceAtLeast(0), tracking, reps)
    }
}
