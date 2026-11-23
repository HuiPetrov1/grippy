// SPDX-License-Identifier: MPL-2.0
// Grippy: the camera phone's screen — it watches, follows the hand and counts. Not part of upstream.

package app.grippy.camera

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.grippy.measure.Hand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import run.nuri.getagrip.R
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the camera screen shows. Written on the main thread only. */
class CameraUi {
    var state by mutableStateOf(CamState.Starting)
    var tracking by mutableStateOf(Tracking.None)
    var count by mutableIntStateOf(0)
    /** The followed hand, already mirrored for the screen when the front camera is used. */
    var hand by mutableStateOf<FloatArray?>(null)
    var aspect by mutableFloatStateOf(0.75f)
    var fps by mutableIntStateOf(0)
    var exercise by mutableStateOf("")
    var side by mutableStateOf<Hand?>(null)
    var elapsed by mutableDoubleStateOf(0.0)
    var signal by mutableIntStateOf(0)
    var front by mutableStateOf(true)
    var problem by mutableStateOf("")
    /** The last finished set, shown until the next one starts. */
    var last by mutableStateOf<CamEvent.Summary?>(null)
    var byLink by mutableStateOf(false)
}

class CameraActivity : ComponentActivity() {
    private val ui = CameraUi()
    private lateinit var analysisExecutor: ExecutorService
    private var tracker: HandTracker? = null
    private var session: RepSession? = null
    private var trace: SetTrace? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null
    private var tones: ToneGenerator? = null
    private val startNanos = System.nanoTime()
    private var lastFrameSentAt = 0.0
    private var lastStatusAt = 0.0
    private var frames = 0
    private var fpsFrom = 0.0
    private var aloneSince = 0L
    private var countingFrom = 0.0

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bind() else ui.problem = "Grippy needs the camera to count reps. Allow it in Settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On top of the lock screen, with the screen lit, without unlocking the phone.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()
        tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        ui.byLink = intent.getBooleanExtra(BY_LINK, false)
        ui.front = CameraRole.frontCamera(this)
        CamHub.screenOpen.value = true
        getSystemService(NotificationManager::class.java)?.cancel(CameraLinkService.WAKE_ID)
        publish(CamState.Starting)

        setContent {
            GetAGripTheme {
                CameraScreen(
                    ui = ui,
                    onPreview = { view -> previewView = view; start() },
                    onStart = { begin(ui.side ?: Hand.Left) },
                    onStop = { end() },
                    onFlip = { flip() },
                    onClose = { finish() },
                )
            }
        }

        lifecycleScope.launch { CamHub.commands.receiveAsFlow().collect { handle(it) } }
        lifecycleScope.launch {
            // Opened by the main phone and then left alone: close again rather than hold the camera open.
            while (true) {
                delay(2_000)
                val alone = CamHub.listeners.value == 0 && ui.byLink && ui.state != CamState.Counting
                if (!alone) aloneSince = 0L else if (aloneSince == 0L) aloneSince = SystemClock.elapsedRealtime()
                if (aloneSince != 0L && SystemClock.elapsedRealtime() - aloneSince > ALONE_CLOSE_MS) finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(BY_LINK, false)) ui.byLink = true
        getSystemService(NotificationManager::class.java)?.cancel(CameraLinkService.WAKE_ID)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity is not recreated on rotation, so the camera has to be told which way is up.
        display?.rotation?.let { rotation ->
            analysis?.targetRotation = rotation
            preview?.targetRotation = rotation
        }
    }

    override fun onDestroy() {
        CamHub.screenOpen.value = false
        runCatching { provider?.unbindAll() }
        val executor = analysisExecutor
        executor.execute {
            trace?.discard(); trace = null
            tracker?.close(); tracker = null
        }
        executor.shutdown()
        tones?.release()
        CamHub.status.value = CamEvent.Status(CamState.Idle, Tracking.None, 0, 0)
        CamHub.events.tryEmit(CamHub.status.value)
        super.onDestroy()
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bind()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun bind() {
        val view = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameras = runCatching { future.get() }.getOrElse { fail("The camera would not open."); return@addListener }
            provider = cameras
            analysisExecutor.execute {
                if (tracker == null) {
                    tracker = runCatching { HandTracker(this) }
                        .onFailure { Log.e(TAG, "hand model failed to load", it) }.getOrNull()
                }
            }
            val fourByThree = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val show = Preview.Builder().setResolutionSelector(fourByThree.build()).build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val look = ImageAnalysis.Builder()
                .setResolutionSelector(
                    fourByThree.setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    ).build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            look.setAnalyzer(analysisExecutor, ::analyze)
            val selector = if (ui.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val bound = runCatching {
                cameras.unbindAll()
                val chosen = if (cameras.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA
                cameras.bindToLifecycle(this, chosen, show, look)
            }
            if (bound.isFailure) { Log.e(TAG, "bind failed", bound.exceptionOrNull()); fail("The camera would not open."); return@addListener }
            preview = show
            analysis = look
            ui.problem = ""
            if (ui.state == CamState.Starting || ui.state == CamState.Failed) publish(CamState.Framing)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fail(message: String) {
        ui.problem = message
        publish(CamState.Failed, message)
    }

    private fun flip() {
        if (ui.state == CamState.Counting) return
        ui.front = !ui.front
        CameraRole.setFrontCamera(this, ui.front)
        bind()
    }

    private fun handle(command: CamCommand) {
        when (command) {
            is CamCommand.Wake -> {
                ui.byLink = true
                ui.exercise = command.exercise
                ui.last = null
                if (command.frontCamera != ui.front) { ui.front = command.frontCamera; if (provider != null) bind() }
                publish(if (ui.state == CamState.Counting) CamState.Counting else if (provider == null) CamState.Starting else CamState.Framing)
            }
            is CamCommand.Begin -> begin(command.hand)
            CamCommand.End -> end()
            CamCommand.Sleep -> finish()
            CamCommand.Hello -> publish(ui.state)
        }
    }

    private fun begin(hand: Hand) {
        if (ui.state != CamState.Framing && ui.state != CamState.Counting) return
        ui.side = hand
        ui.count = 0
        ui.elapsed = 0.0
        ui.last = null
        val now = seconds()
        countingFrom = now
        val exercise = ui.exercise
        val front = ui.front
        analysisExecutor.execute {
            session?.begin(now)
            trace?.discard()
            trace = if (CameraRole.keepTraces(this)) SetTrace.open(this, exercise, hand, front, ui.aspect) else null
        }
        tones?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 250)
        publish(CamState.Counting)
    }

    private fun end() {
        if (ui.state != CamState.Counting) return
        analysisExecutor.execute {
            val summary = session?.end() ?: return@execute
            trace?.finish(summary); trace = null
            CamHub.events.tryEmit(summary)
            runOnUiThread {
                ui.last = summary
                ui.count = summary.reps.size
                tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
                publish(CamState.Framing)
            }
        }
    }

    private fun publish(state: CamState, note: String = "") {
        ui.state = state
        val status = CamEvent.Status(state, ui.tracking, ui.fps, 0, note = note)
        CamHub.status.value = status
        CamHub.events.tryEmit(status)
    }

    private fun seconds(): Double = (System.nanoTime() - startNanos) / 1e9

    /** Runs on the analysis thread, one frame at a time; a frame that arrives while this is busy is skipped. */
    private fun analyze(image: ImageProxy) {
        val t = seconds()
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) return
        val upright = if (rotation % 180 == 0) bitmap.width.toFloat() / bitmap.height else bitmap.height.toFloat() / bitmap.width
        val reps = session ?: RepSession(upright).also { session = it }
        val hands = runCatching { tracker?.find(bitmap, rotation, (t * 1000).toLong()) }.getOrNull() ?: emptyList()
        val luma = shrink(bitmap)
        bitmap.recycle()
        val out = reps.frame(t, hands, luma)
        trace?.add(t - countingFrom, hands, luma)

        frames++
        if (t - fpsFrom >= 1.0) { val fps = (frames / (t - fpsFrom)).toInt(); frames = 0; fpsFrom = t; runOnUiThread { ui.fps = fps } }
        val front = ui.front
        val shown = out.hand?.points?.let { p -> if (front) FloatArray(p.size) { i -> if (i % 2 == 0) 1f - p[i] else p[i] } else p }
        val counting = reps.counting
        out.newReps.forEachIndexed { i, mark ->
            CamHub.events.tryEmit(CamEvent.Rep(out.count - (out.newReps.size - 1 - i), mark))
        }
        if (t - lastFrameSentAt >= FRAME_EVERY_SEC) {
            lastFrameSentAt = t
            CamHub.events.tryEmit(
                CamEvent.Frame(
                    tMs = if (counting) ((t - countingFrom) * 1000).toInt() else 0,
                    count = out.count, signal = out.signal, tracking = out.tracking,
                    aspect = (upright * 100).toInt(),
                    points = shown?.let { CamCodec.packHand(it) } ?: ByteArray(0),
                ),
            )
        }
        runOnUiThread {
            ui.hand = shown
            ui.aspect = upright
            ui.tracking = out.tracking
            ui.signal = out.signal
            if (counting) {
                if (out.newReps.isNotEmpty()) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                ui.count = out.count
                ui.elapsed = t - countingFrom
            }
            if (t - lastStatusAt >= STATUS_EVERY_SEC) {
                lastStatusAt = t
                val status = CamEvent.Status(ui.state, ui.tracking, ui.fps, 0)
                CamHub.status.value = status
                CamHub.events.tryEmit(status)
            }
        }
    }

    private val small = IntArray(RepFeatures.GRID_DIM)

    /** The picture as [RepFeatures.GRID_W]×[RepFeatures.GRID_H] brightness values. */
    private fun shrink(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, RepFeatures.GRID_W, RepFeatures.GRID_H, true)
        scaled.getPixels(small, 0, RepFeatures.GRID_W, 0, 0, RepFeatures.GRID_W, RepFeatures.GRID_H)
        if (scaled !== bitmap) scaled.recycle()
        return ByteArray(RepFeatures.GRID_DIM) { i ->
            val p = small[i]
            ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
    }

    companion object {
        private const val TAG = "GrippyCamera"
        private const val BY_LINK = "byLink"
        private const val FRAME_EVERY_SEC = 0.1
        private const val STATUS_EVERY_SEC = 2.0
        private const val ALONE_CLOSE_MS = 90_000L

        fun intent(context: Context, byLink: Boolean): Intent =
            Intent(context, CameraActivity::class.java).putExtra(BY_LINK, byLink)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /**
         * Opens the camera screen. From the background Android may refuse a plain start, so a
         * full-screen notification goes up as well: on a locked or sleeping phone that opens it.
         */
        fun open(context: Context, byLink: Boolean) {
            runCatching { context.startActivity(intent(context, byLink)) }.onFailure { Log.w(TAG, "start refused", it) }
            if (!byLink) return
            CameraLinkService.channels(context)
            val pending = PendingIntent.getActivity(
                context, 1, intent(context, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CameraLinkService.WAKE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Grippy camera")
                .setContentText("Your main phone wants to count reps. Tap to open the camera.")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .setAutoCancel(true).setTimeoutAfter(20_000)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(CameraLinkService.WAKE_ID, notification) }
        }
    }
}
