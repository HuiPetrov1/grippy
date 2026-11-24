// SPDX-License-Identifier: MPL-2.0
// Grippy: the camera phone switches its screen off when the main phone is done with it. Not part of upstream.

package app.grippy.camera

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android lets an app turn the screen off only as a device admin with the single "lock the screen" power.
 * Without it the camera screen just closes and the phone dims after its own screen timeout.
 */
object ScreenOff {
    private const val TAG = "GrippyScreenOff"

    private fun component(context: Context) = ComponentName(context, ScreenOffAdmin::class.java)

    fun allowed(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true

    /** Android's own screen asking to allow it, with Grippy's reason. */
    fun askIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Grippy uses this only to switch the camera phone's screen off when your main phone stops counting.",
            )

    /** Gives the power back (when the phone stops being the camera), so Grippy can be uninstalled normally. */
    fun release(context: Context) {
        if (allowed(context)) runCatching { context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(component(context)) }
    }

    /** Switches the screen off shortly, once the camera screen has closed. Does nothing without the power. */
    fun soon(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { app.getSystemService(DevicePolicyManager::class.java)?.lockNow() }
                .onFailure { Log.w(TAG, "lockNow failed", it) }
        }, 400)
    }
}

/** The device-admin receiver Android needs for [ScreenOff]; it only answers Android's questions. */
class ScreenOffAdmin : DeviceAdminReceiver()
