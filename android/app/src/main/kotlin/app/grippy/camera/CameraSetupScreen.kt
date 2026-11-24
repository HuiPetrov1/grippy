// SPDX-License-Identifier: MPL-2.0
// Grippy: Settings → Camera phone. Turns a phone into the camera and shows what it still needs
// allowed to wake up by itself. Not part of upstream.

package app.grippy.camera

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.grippy.measure.OutlinedCard
import app.grippy.measure.PrimaryButton
import app.grippy.measure.Segmented
import app.grippy.measure.measureColors
import app.grippy.measure.textStyle
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset

/** One thing the camera phone needs: is it allowed, and how to allow it. */
private class Need(val title: String, val why: String, val ok: Boolean?, val fix: () -> Unit)

@Composable
fun CameraSetupScreen(modifier: Modifier = Modifier) {
    val c = measureColors()
    val context = LocalContext.current
    var isCamera by remember { mutableStateOf(CameraRole.isCamera(context)) }
    var front by remember { mutableStateOf(CameraRole.frontCamera(context)) }
    var keep by remember { mutableStateOf(CameraRole.keepTraces(context)) }
    // Bumped whenever the screen comes back, so the checklist re-reads what is allowed.
    var looked by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { looked++ }
    val advertising by CamHub.advertising.collectAsState()
    val listeners by CamHub.listeners.collectAsState()
    val problem by CamHub.problem.collectAsState()

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        looked++
        if (isCamera) CameraLinkService.start(context)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp + LocalFloatingTabBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "A second phone can stand still, watch your hand and count your reps, for exercises the scale cannot hang on. " +
                "Install Grippy on both phones. Turn the switch on only on the phone that will be the camera.",
            style = textStyle(14.sp, FontWeight.Normal, c.text2),
        )
        OutlinedCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use this phone as the camera", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                    Text(if (isCamera) "It waits for your main phone, even locked." else "Off: this is your main phone.",
                        style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
                }
                Switch(
                    checked = isCamera,
                    onCheckedChange = { on ->
                        isCamera = on
                        CameraRole.setCamera(context, on)
                        if (on) {
                            val missing = runtimePermissions().filter { !granted(context, it) }
                            if (missing.isNotEmpty()) ask.launch(missing.toTypedArray())
                        }
                        looked++
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.blue),
                )
            }
        }

        if (isCamera) {
            val needs = remember(looked, advertising) { needs(context) { ask.launch(it) } }
            val open = needs.count { it.ok == false }
            OutlinedCard {
                Column(Modifier.padding(14.dp)) {
                    val (line, color) = when {
                        problem.isNotEmpty() -> problem to Color(0xFFFF453A)
                        listeners > 0 -> "Connected to your main phone" to Color(0xFF30D158)
                        advertising -> "Ready: waiting for your main phone" to Color(0xFF30D158)
                        else -> "Starting…" to c.text2
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(9.dp))
                        Text(line, style = textStyle(15.5.sp, FontWeight.SemiBold, c.text))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (open == 0) "Everything below is allowed. Leave this phone where it can see your hand; it can sleep."
                        else "$open thing${if (open == 1) "" else "s"} below still need allowing, or the camera may not wake by itself.",
                        style = textStyle(13.5.sp, FontWeight.Normal, c.text2),
                    )
                }
            }
            OutlinedCard {
                needs.forEachIndexed { i, need ->
                    Row(Modifier.fillMaxWidth().clickable(onClick = need.fix).padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tint, wash) = when (need.ok) {
                            true -> Triple(Icons.Filled.Check, Color(0xFF30D158), Color(0x2630D158))
                            false -> Triple(Icons.Filled.PriorityHigh, Color(0xFFFF9F0A), Color(0x26FF9F0A))
                            null -> Triple(Icons.Filled.ChevronRight, c.text3, c.raised)
                        }
                        Box(Modifier.size(28.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(need.title, style = textStyle(15.sp, FontWeight.SemiBold, c.text))
                            Text(need.why, style = textStyle(13.sp, FontWeight.Normal, c.text2))
                        }
                        if (need.ok != true) Text(if (need.ok == null) "Open" else "Allow", style = textStyle(14.sp, FontWeight.SemiBold, c.link))
                    }
                    if (i < needs.lastIndex) HorizontalDivider(Modifier.padding(start = 54.dp), color = c.divider)
                }
            }
        }

        OutlinedCard {
            Column(Modifier.padding(14.dp)) {
                Text("Camera", style = textStyle(15.5.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(false, true), front, { if (it) "Front" else "Back" }, { front = it; CameraRole.setFrontCamera(context, it) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    if (front) "Front: the screen faces you, so you can read the count while you train."
                    else "Back: a sharper picture, but the screen faces away from you.",
                    style = textStyle(13.sp, FontWeight.Normal, c.text2),
                )
            }
        }

        if (isCamera) {
            OutlinedCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep each set's measurements", style = textStyle(15.5.sp, FontWeight.SemiBold, c.text))
                        Text("Hand positions and a tiny brightness map per frame, never photos or video. Used to check and tune the counting.",
                            style = textStyle(13.sp, FontWeight.Normal, c.text2))
                    }
                    Switch(keep, { keep = it; CameraRole.setKeepTraces(context, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.blue))
                }
            }
            PrimaryButton("Open the camera now", onClick = { CameraActivity.open(context, byLink = false) })
            Text("Opens the camera on this phone so you can check the view and try the counting without your main phone.",
                style = textStyle(13.sp, FontWeight.Normal, c.text3))
        } else {
            Text("On this phone: Measure → “Reps · camera”. The other phone wakes, opens its camera and counts; the result is saved here.",
                style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
        }
    }
}

private fun runtimePermissions(): List<String> = listOf(
    Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun needs(context: Context, ask: (Array<String>) -> Unit): List<Need> {
    val pkg = Uri.parse("package:${context.packageName}")
    fun go(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    val list = ArrayList<Need>()
    list += Need("Camera", "To see your hand.", granted(context, Manifest.permission.CAMERA)) {
        ask(arrayOf(Manifest.permission.CAMERA))
    }
    list += Need("Nearby devices", "Bluetooth, so your main phone can reach this one.", CameraLinkService.hasBluetooth(context)) {
        ask(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
    }
    list += Need("Notifications", "The standby notice that keeps the link alive.", granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
        ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    list += Need("Display over other apps", "Lets the camera open while the phone is locked.", Settings.canDrawOverlays(context)) {
        go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), details)
    }
    if (Build.VERSION.SDK_INT >= 34) {
        val allowed = context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
        list += Need("Full-screen notifications", "The second way to wake the screen.", allowed) {
            go(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg), details)
        }
    }
    val unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    list += Need("No battery restrictions", "So Android does not put the link to sleep.", unrestricted) {
        @Suppress("BatteryLife")
        go(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details)
    }
    list += Need(
        "Screen off after a set",
        "Optional. Lets Grippy switch this screen off when your main phone stops; otherwise it dims on its own timer.",
        ScreenOff.allowed(context),
    ) {
        go(ScreenOff.askIntent(context))
    }
    if (isXiaomi()) {
        // Xiaomi keeps these in its own settings, and gives apps no way to read them.
        list += Need("Xiaomi: Autostart", "Turn on Autostart for Grippy, or it will not come back after a restart.", null) {
            go(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details)
        }
        list += Need(
            "Xiaomi: Other permissions",
            "Allow “Show on Lock screen” and “Open new windows while running in the background”.", null,
        ) {
            go(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", context.packageName),
                details,
            )
        }
    }
    return list
}

private fun isXiaomi(): Boolean =
    listOf(Build.MANUFACTURER, Build.BRAND).any { it.lowercase() in setOf("xiaomi", "redmi", "poco") }
