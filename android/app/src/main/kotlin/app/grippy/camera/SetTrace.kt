// SPDX-License-Identifier: MPL-2.0
// Grippy: what the camera measured during one set, kept on the camera phone so the counting can be
// checked and tuned afterwards. Numbers only — the hand's points and a 32×24 brightness thumbnail
// per frame — never the pictures themselves. Not part of upstream.

package app.grippy.camera

import android.content.Context
import app.grippy.measure.Hand
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One file pair per set in `Android/data/app.grippy/files/traces/`:
 * - `<stamp>.bin` (version 3): records in the order the counter got them, big-endian. A picture: kind 0
 *   (1 byte), seconds into the set (float64), 768 brightness bytes. Hands: kind 1, seconds into the set of
 *   the frame they were found in (float64), how many (1 byte, 0…2), 42 float32 each (x0, y0 … x20, y20).
 *   `eval.py --bin3` in the counting lab replays it exactly as the phone counted.
 * - `<stamp>.json`: the exercise, the hand, and what was counted.
 */
class SetTrace private constructor(private val bin: File, private val meta: File, private val head: String) {
    private var out: DataOutputStream? = DataOutputStream(bin.outputStream().buffered(1 shl 16))
    private var frames = 0

    fun picture(t: Double, luma: ByteArray) {
        val stream = out ?: return
        if (frames >= MAX_FRAMES) return
        runCatching {
            stream.writeByte(0)
            stream.writeDouble(t)
            stream.write(luma, 0, RepFeatures.GRID_DIM)
            frames++
        }
    }

    fun hands(t: Double, hands: List<SeenHand>) {
        val stream = out ?: return
        if (frames >= MAX_FRAMES) return
        runCatching {
            val n = hands.size.coerceAtMost(2)
            stream.writeByte(1)
            stream.writeDouble(t)
            stream.writeByte(n)
            for (k in 0 until n) for (i in 0 until 42) stream.writeFloat(hands[k].points.getOrNull(i) ?: 0f)
        }
    }

    fun finish(summary: CamEvent.Summary) {
        runCatching { out?.close() }
        out = null
        val reps = summary.reps.joinToString(",") { "{\"t\":${it.tMs},\"ms\":${it.durationMs},\"range\":${it.amplitude}}" }
        runCatching {
            meta.writeText(
                "{$head,\"frames\":$frames,\"durationMs\":${summary.durationMs},\"tracking\":\"${summary.tracking}\"," +
                    "\"counted\":${summary.reps.size},\"reps\":[$reps]}\n",
            )
        }
        prune(bin.parentFile)
    }

    /** The set was abandoned: nothing worth keeping. */
    fun discard() {
        runCatching { out?.close() }
        out = null
        bin.delete()
    }

    companion object {
        private const val KEEP = 40
        /** Ten minutes at 30 frames a second. */
        private const val MAX_FRAMES = 18_000

        fun open(context: Context, exercise: String, hand: Hand, front: Boolean, aspect: Float): SetTrace? = runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "traces").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = exercise.replace("\\", " ").replace("\"", "'")
            val head = "\"version\":3,\"aspect\":$aspect,\"at\":\"$stamp\",\"exercise\":\"$name\",\"hand\":\"${hand.key}\",\"frontCamera\":$front," +
                "\"grid\":[${RepFeatures.GRID_W},${RepFeatures.GRID_H}]"
            SetTrace(File(dir, "$stamp.bin"), File(dir, "$stamp.json"), head)
        }.getOrNull()

        private fun prune(dir: File?) {
            val sets = dir?.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: return
            sets.drop(KEEP).forEach { json ->
                json.delete()
                File(dir, json.name.removeSuffix(".json") + ".bin").delete()
            }
        }
    }
}
