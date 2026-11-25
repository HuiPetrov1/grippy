// SPDX-License-Identifier: MPL-2.0
// Grippy: what the camera measured during one set, kept on the camera phone so the counting can be
// checked and tuned afterwards. Numbers only — the hand's points and a 32×24 brightness thumbnail
// per frame — never the pictures themselves. Not part of upstream.

package app.grippy.camera

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import run.nuri.getagrip.ui.history.SHARE_DIR
import run.nuri.getagrip.ui.history.ShareCache
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

        /** How many sets are kept, newest first. */
        fun count(context: Context): Int =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "traces").listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

        /**
         * The newest [sets] sets in one zip, handed to the share sheet (to send them to whoever tunes the
         * counting). Numbers only, as kept. False when there is nothing to send or it failed.
         */
        fun share(context: Context, sets: Int = 12): Boolean = runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "traces")
            val metas = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }?.take(sets).orEmpty()
            if (metas.isEmpty()) return false
            ShareCache.purge(ShareCache.directory(context))
            val out = File(context.cacheDir, "$SHARE_DIR/${UUID.randomUUID()}").apply { mkdirs() }
            val zip = File(out, "grippy-camera-sets-${metas.first().name.removeSuffix(".json")}.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                for (meta in metas) {
                    val bin = File(dir, meta.name.removeSuffix(".json") + ".bin")
                    for (f in listOf(meta, bin)) if (f.exists()) {
                        z.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, zip.name)
                clipData = ClipData.newRawUri(zip.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

        private fun prune(dir: File?) {
            val sets = dir?.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: return
            sets.drop(KEEP).forEach { json ->
                json.delete()
                File(dir, json.name.removeSuffix(".json") + ".bin").delete()
            }
        }
    }
}
