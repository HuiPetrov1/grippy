// SPDX-License-Identifier: MPL-2.0
// Grippy: what the two phones say to each other when one is the camera. Not part of upstream.

package app.grippy.camera

import app.grippy.measure.Hand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** The Bluetooth service the camera phone offers, and the two things in it. */
object CamLink {
    val SERVICE: UUID = UUID.fromString("7e5f0001-6c1d-4b8e-9a37-677269707079")
    /** The main phone writes commands here. */
    val COMMAND: UUID = UUID.fromString("7e5f0002-6c1d-4b8e-9a37-677269707079")
    /** The camera phone notifies events here. */
    val EVENT: UUID = UUID.fromString("7e5f0003-6c1d-4b8e-9a37-677269707079")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val PROTOCOL = 1
    /** Bytes of one hand in a [CamEvent.Frame]: 21 points, x and y, one byte each. */
    const val HAND_BYTES = 42
}

enum class CamState { Idle, Starting, Framing, Counting, Failed }

/** What the camera is following: a hand it recognises, plain movement, or nothing yet. */
enum class Tracking { None, Hand, Motion }

/** Main phone → camera phone. */
sealed interface CamCommand {
    /** "Are you there, and what are you doing?" Answered with a [CamEvent.Status]. */
    data object Hello : CamCommand
    /** Wake the screen and open the camera, ready to count. */
    data class Wake(val frontCamera: Boolean, val exercise: String) : CamCommand
    /** Start counting a set for this hand; the clock starts now. */
    data class Begin(val hand: Hand) : CamCommand
    /** The set is over: answered with a [CamEvent.Summary]. The camera stays open. */
    data object End : CamCommand
    /** Close the camera and let the screen sleep. */
    data object Sleep : CamCommand
}

/** Camera phone → main phone. */
sealed interface CamEvent {
    data class Status(
        val state: CamState, val tracking: Tracking, val fps: Int, val battery: Int,
        val protocol: Int = CamLink.PROTOCOL, val note: String = "",
    ) : CamEvent

    /**
     * A few times a second while the camera is open: where the hand is (for the little preview) and
     * the movement signal (−100…100 around the middle of the motion) the reps are counted from.
     */
    class Frame(
        val tMs: Int, val count: Int, val signal: Int, val tracking: Tracking,
        /** Image width ÷ height × 100, so the preview keeps its shape. */
        val aspect: Int,
        /** [CamLink.HAND_BYTES] bytes when a hand is followed, else empty. */
        val points: ByteArray,
    ) : CamEvent

    data class Rep(val count: Int, val mark: RepMark) : CamEvent

    data class Summary(val durationMs: Int, val tracking: Tracking, val reps: List<RepMark>) : CamEvent
}

/** Bytes on the wire: the first byte says what it is. Little-endian. */
object CamCodec {
    private const val HELLO = 0x01
    private const val WAKE = 0x02
    private const val BEGIN = 0x03
    private const val END = 0x04
    private const val SLEEP = 0x05
    private const val STATUS = 0x81
    private const val FRAME = 0x82
    private const val REP = 0x83
    private const val SUMMARY = 0x84
    private const val MAX_TEXT = 80

    fun encode(command: CamCommand): ByteArray = when (command) {
        CamCommand.Hello -> byteArrayOf(HELLO.toByte(), CamLink.PROTOCOL.toByte())
        is CamCommand.Wake -> {
            val name = text(command.exercise)
            buffer(3 + name.size).put(WAKE.toByte()).put(if (command.frontCamera) 1 else 0).put(name.size.toByte()).put(name).array()
        }
        is CamCommand.Begin -> byteArrayOf(BEGIN.toByte(), command.hand.ordinal.toByte())
        CamCommand.End -> byteArrayOf(END.toByte())
        CamCommand.Sleep -> byteArrayOf(SLEEP.toByte())
    }

    fun decodeCommand(bytes: ByteArray): CamCommand? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (b.get().toInt() and 0xFF) {
            HELLO -> CamCommand.Hello
            WAKE -> {
                val front = b.get().toInt() != 0
                val name = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
                CamCommand.Wake(front, String(name, Charsets.UTF_8))
            }
            BEGIN -> CamCommand.Begin(Hand.entries[(b.get().toInt() and 0xFF).coerceAtMost(Hand.entries.lastIndex)])
            END -> CamCommand.End
            SLEEP -> CamCommand.Sleep
            else -> null
        }
    }.getOrNull()

    fun encode(event: CamEvent): ByteArray = when (event) {
        is CamEvent.Status -> {
            val note = text(event.note)
            buffer(7 + note.size).put(STATUS.toByte()).put(event.state.ordinal.toByte()).put(event.tracking.ordinal.toByte())
                .put(event.fps.coerceIn(0, 255).toByte()).put(event.battery.coerceIn(0, 100).toByte())
                .put(event.protocol.toByte()).put(note.size.toByte()).put(note).array()
        }
        is CamEvent.Frame -> buffer(10 + event.points.size).put(FRAME.toByte()).putInt(event.tMs).putShort(event.count.toShort())
            .put(event.signal.coerceIn(-100, 100).toByte()).put(event.tracking.ordinal.toByte())
            .put(event.aspect.coerceIn(1, 255).toByte()).put(event.points).array()
        is CamEvent.Rep -> buffer(3 + MARK_BYTES).put(REP.toByte()).putShort(event.count.toShort()).putMark(event.mark).array()
        is CamEvent.Summary -> {
            val b = buffer(8 + event.reps.size * MARK_BYTES).put(SUMMARY.toByte()).putInt(event.durationMs)
                .put(event.tracking.ordinal.toByte()).putShort(event.reps.size.toShort())
            event.reps.forEach { b.putMark(it) }
            b.array()
        }
    }

    fun decodeEvent(bytes: ByteArray): CamEvent? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (b.get().toInt() and 0xFF) {
            STATUS -> {
                val state = CamState.entries[b.get().toInt().coerceIn(0, CamState.entries.lastIndex)]
                val tracking = tracking(b.get())
                val fps = b.get().toInt() and 0xFF
                val battery = b.get().toInt() and 0xFF
                val protocol = b.get().toInt() and 0xFF
                val note = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
                CamEvent.Status(state, tracking, fps, battery, protocol, String(note, Charsets.UTF_8))
            }
            FRAME -> {
                val t = b.getInt()
                val count = b.getShort().toInt() and 0xFFFF
                val signal = b.get().toInt()
                val tracking = tracking(b.get())
                val aspect = b.get().toInt() and 0xFF
                CamEvent.Frame(t, count, signal, tracking, aspect, ByteArray(b.remaining()).also { b.get(it) })
            }
            REP -> CamEvent.Rep(b.getShort().toInt() and 0xFFFF, b.readMark())
            SUMMARY -> {
                val duration = b.getInt()
                val tracking = tracking(b.get())
                val n = b.getShort().toInt() and 0xFFFF
                CamEvent.Summary(duration, tracking, List(n) { b.readMark() })
            }
            else -> null
        }
    }.getOrNull()

    /** A hand's 21 points (x0, y0, x1, y1 … each 0…1 of the image) squeezed into one byte each. */
    fun packHand(points: FloatArray): ByteArray =
        ByteArray(CamLink.HAND_BYTES) { i -> (points.getOrElse(i) { 0f }.coerceIn(0f, 1f) * 255f + .5f).toInt().toByte() }

    fun unpackHand(bytes: ByteArray): FloatArray? =
        if (bytes.size < CamLink.HAND_BYTES) null else FloatArray(CamLink.HAND_BYTES) { i -> (bytes[i].toInt() and 0xFF) / 255f }

    private const val MARK_BYTES = 7

    private fun ByteBuffer.putMark(m: RepMark): ByteBuffer =
        putInt(m.tMs).putShort(m.durationMs.coerceIn(0, 65_535).toShort()).put(m.amplitude.coerceIn(0, 255).toByte())

    private fun ByteBuffer.readMark(): RepMark =
        RepMark(getInt(), getShort().toInt() and 0xFFFF, get().toInt() and 0xFF)

    private fun tracking(b: Byte): Tracking = Tracking.entries[b.toInt().coerceIn(0, Tracking.entries.lastIndex)]

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** UTF-8, cut at a character boundary so it never exceeds [MAX_TEXT] bytes. */
    private fun text(s: String): ByteArray {
        var cut = s
        while (cut.toByteArray(Charsets.UTF_8).size > MAX_TEXT) cut = cut.dropLast(1)
        return cut.toByteArray(Charsets.UTF_8)
    }
}

/**
 * A message can be longer than one Bluetooth packet, so every packet starts with one byte:
 * 1 = more of this message follows, 0 = this packet ends it.
 */
object CamPackets {
    fun split(message: ByteArray, packetSize: Int): List<ByteArray> {
        val room = (packetSize - 1).coerceAtLeast(1)
        if (message.isEmpty()) return listOf(byteArrayOf(0))
        return (message.indices step room).map { from ->
            val to = minOf(from + room, message.size)
            ByteArray(to - from + 1).also {
                it[0] = if (to < message.size) 1 else 0
                message.copyInto(it, 1, from, to)
            }
        }
    }

    /** Puts packets back together; one per sender. */
    class Assembler {
        private var pending = ByteArray(0)

        /** The whole message once its last packet arrives, else null. */
        fun accept(packet: ByteArray): ByteArray? {
            if (packet.isEmpty()) return null
            pending += packet.copyOfRange(1, packet.size)
            if (packet[0].toInt() != 0) {
                if (pending.size > 64_000) pending = ByteArray(0) // a runaway sender: drop it
                return null
            }
            return pending.also { pending = ByteArray(0) }
        }

        fun reset() { pending = ByteArray(0) }
    }
}
