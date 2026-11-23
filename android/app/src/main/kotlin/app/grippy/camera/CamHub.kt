// SPDX-License-Identifier: MPL-2.0
// Grippy: the camera phone's shared state, and whether this phone IS the camera. Not part of upstream.

package app.grippy.camera

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where the camera phone's two halves meet: the Bluetooth service hears the main phone, the camera
 * screen does the counting. Both live in one process, so this is plain shared memory.
 */
object CamHub {
    /** What the camera is doing right now; the service answers "Hello" with it. */
    val status = MutableStateFlow(CamEvent.Status(CamState.Idle, Tracking.None, 0, 0))

    /** Commands for the camera screen. Buffered, so one that arrives while the screen is still opening waits. */
    val commands = Channel<CamCommand>(32, BufferOverflow.DROP_OLDEST)

    /** Everything the camera screen wants the main phone to know. */
    val events = MutableSharedFlow<CamEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** How many main phones are listening. */
    val listeners = MutableStateFlow(0)

    /** Is the Bluetooth service up and visible to the main phone? */
    val advertising = MutableStateFlow(false)

    /** Why the service could not start, in words for the setup screen. Empty = fine. */
    val problem = MutableStateFlow("")

    /** True while the camera screen is on top. */
    val screenOpen = MutableStateFlow(false)

    fun clearCommands() { while (commands.tryReceive().isSuccess) Unit }
}

/** This phone's part: the main phone (default) or the stationary camera. Remembered across restarts. */
object CameraRole {
    private const val PREFS = "grippy-camera"
    private const val IS_CAMERA = "isCamera"
    private const val FRONT = "frontCamera"
    private const val KEEP_CLIPS = "keepTraces"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isCamera(context: Context): Boolean = prefs(context).getBoolean(IS_CAMERA, false)

    fun setCamera(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(IS_CAMERA, on).apply()
        if (on) CameraLinkService.start(context) else CameraLinkService.stop(context)
    }

    /** The front camera faces you along with the screen, so you can read the count. */
    fun frontCamera(context: Context): Boolean = prefs(context).getBoolean(FRONT, true)

    fun setFrontCamera(context: Context, on: Boolean) = prefs(context).edit().putBoolean(FRONT, on).apply()

    /** Keeps what the camera measured for each set (numbers only, no pictures) so the counting can be tuned. */
    fun keepTraces(context: Context): Boolean = prefs(context).getBoolean(KEEP_CLIPS, true)

    fun setKeepTraces(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEEP_CLIPS, on).apply()
}
