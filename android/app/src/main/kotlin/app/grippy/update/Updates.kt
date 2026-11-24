// SPDX-License-Identifier: MPL-2.0
// Grippy's self-updater, adapted from Distill's (HuiPetrov1). Not part of upstream Get a Grip.

package app.grippy.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Published next to every build as update.json in the releases repo by scripts/grippy-release.sh. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Downloading(val update: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val update: UpdateInfo) : UpdateState
    data class Failed(val message: String, val update: UpdateInfo? = null) : UpdateState
}

/**
 * Checks the releases repo when the app comes to the front and installs a new build with one tap.
 * On Android 12+ every update after the first one installs without a confirmation screen, because
 * Grippy is then its own installer of record.
 *
 * Xiaomi's system (MIUI / HyperOS) aborts app-driven installs ("INSTALL_FAILED_ABORTED: Permission
 * denied") whatever the user allowed. There, and on any phone where that has happened once, the APK is
 * handed to Android's own installer screen instead: one more tap, but it goes through.
 */
object UpdateManager {
    const val MANIFEST_URL = "https://github.com/HuiPetrov1/grippy-releases/releases/latest/download/update.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var lastCheck: TimeMark? = null

    fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Throttled to once every 5 minutes unless [force]. */
    fun check(context: Context, force: Boolean = false) {
        // JVM tests and screenshots (Robolectric) never reach out to GitHub.
        if (Build.FINGERPRINT == "robolectric") return
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        val last = lastCheck
        if (!force && last != null && last.elapsedNow() < 5.minutes) return
        lastCheck = TimeSource.Monotonic.markNow()
        val app = context.applicationContext
        scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching {
                val update = withContext(Dispatchers.IO) { fetchManifest() }
                    // No release published yet counts as up to date.
                    ?: return@runCatching UpdateState.UpToDate
                if (update.versionCode > currentVersionCode(app)) UpdateState.Available(update) else UpdateState.UpToDate
            }.getOrElse { UpdateState.Failed("Couldn't check for updates: ${it.message ?: it::class.simpleName}") }
        }
    }

    fun install(context: Context) {
        val update = when (val s = _state.value) {
            is UpdateState.Available -> s.update
            is UpdateState.Failed -> s.update ?: return
            else -> return
        }
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.value = UpdateState.Failed("Allow Grippy to install updates, then tap Update again.", update)
            return
        }
        val withScreen = useInstallerScreen(app)
        scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            runCatching {
                val apk = withContext(Dispatchers.IO) {
                    download(app, update) { p -> _state.value = UpdateState.Downloading(update, p) }
                }
                if (withScreen) {
                    openInstallerScreen(context, apk)
                    // If the screen is backed out of, the update is still there to install.
                    UpdateState.Available(update)
                } else {
                    withContext(Dispatchers.IO) { commit(app, apk) }
                    UpdateState.Installing(update)
                }
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = UpdateState.Failed("Update failed: ${it.message ?: it::class.simpleName}", update) }
        }
    }

    /** Called by [InstallResultReceiver] when the system reports the install didn't go through. */
    internal fun installFailed(context: Context, message: String) {
        val update = (_state.value as? UpdateState.Installing)?.update
        if ("INSTALL_FAILED_ABORTED" in message) {
            // The system refuses installs started by an app: from now on use its installer screen.
            prefs(context).edit().putBoolean(KEY_SCREEN, true).apply()
            _state.value = UpdateState.Failed("This phone blocks quick updates. Tap Retry to install with Android's installer.", update)
            return
        }
        _state.value = UpdateState.Failed(message, update)
    }

    private const val KEY_SCREEN = "installerScreen"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("grippy_update", Context.MODE_PRIVATE)

    /** Xiaomi, Redmi and POCO phones abort app-driven installs; so does any phone where it already happened. */
    private fun useInstallerScreen(context: Context): Boolean {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return listOf("xiaomi", "redmi", "poco").any { it in maker } || prefs(context).getBoolean(KEY_SCREEN, false)
    }

    private fun openInstallerScreen(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun fetchManifest(): UpdateInfo? {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("notes", ""),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the APK to `<cache>/updates/grippy.apk` (the only update kept). */
    private fun download(context: Context, update: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "grippy.apk")
        val part = File(dir, "grippy.apk.part")
        val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            if (connection.responseCode !in 200..299) error("download failed (HTTP ${connection.responseCode})")
            val total = connection.contentLengthLong
            var written = 0L
            part.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            if (total > 0 && written != total) error("download cut short ($written of $total bytes)")
            apk.delete()
            if (!part.renameTo(apk)) error("couldn't store the download")
            return apk
        } finally {
            connection.disconnect()
        }
    }

    /** Hands the APK to Android's package installer; the result arrives at [InstallResultReceiver]. */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("grippy.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Receives the system's install result; opens Android's confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { UpdateManager.installFailed(context, "Open Grippy and tap Update again to finish installing.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The app restarts into the new version.
            else -> UpdateManager.installFailed(
                context,
                "Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"),
            )
        }
    }
}
