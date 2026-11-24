// SPDX-License-Identifier: MPL-2.0
// Grippy: the main phone's screens while the camera phone counts reps, and the result to save.
// Not part of upstream.

package app.grippy.measure

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grippy.camera.CamClient
import app.grippy.camera.CamCommand
import app.grippy.camera.CameraRole
import app.grippy.camera.HandOverlay
import app.grippy.camera.Tracking
import kotlinx.coroutines.launch
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.LocalSettingsStore
import java.util.Locale
import kotlin.math.ceil

private val RepGreen = Color(0xFF30D158)
private val RepAmber = Color(0xFFFFB340)

@Composable
fun RepRunScreen(nav: MeasureNavigator, run: RepRun) {
    val c = measureColors()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val client = remember { CamClient.get(context) }
    val store = rememberMeasureStore()
    val data = store.collect()
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() }
    var confirmQuit by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { client.connect() }

    KeepScreenOn(true)
    BackHandler { confirmQuit = true }

    DisposableEffect(client, run) {
        client.onEvent = run::receive
        if (client.hasPermission) client.connect() else ask.launch(CamClient.REQUIRED)
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                client.onEvent = null
                // Leaving without a result: let the camera phone go back to sleep.
                if (run.stage != RepRun.Stage.Done) client.send(CamCommand.Sleep)
                client.disconnect()
            }
            tones?.release()
        }
    }
    LaunchedEffect(run) {
        if (run.stage == RepRun.Stage.Done) return@LaunchedEffect
        if (run.started) run.restart()
        run.started = true
        run.drive(
            client = client,
            exercise = run.request.exerciseId?.let { data.exercise(it)?.name } ?: "",
            frontCamera = CameraRole.frontCamera(context),
            cue = { cue ->
                when (cue) {
                    RepRun.Cue.Tick -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    RepRun.Cue.Go -> tones?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350)
                    RepRun.Cue.Stop -> tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, 400)
                }
            },
        )
    }
    LaunchedEffect(run.stage) {
        if (run.stage == RepRun.Stage.Done) {
            if (run.results.isEmpty()) nav.pop() else nav.replaceTop(MeasureRoute.RepsReview(run))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(title = "Reps · camera", onBack = { confirmQuit = true })
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (run.stage) {
                RepRun.Stage.Connecting -> Finding(client.link)
                RepRun.Stage.Waking -> WakingUp(run.wakingFor)
                RepRun.Stage.Countdown -> RepCountdown(run)
                RepRun.Stage.Counting -> RepCounting(run)
                RepRun.Stage.Finishing -> {
                    Spacer(Modifier.height(98.dp))
                    CircularProgressIndicator(color = c.blue, strokeWidth = 4.dp, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.height(23.dp))
                    Text("Getting the count from the camera…", style = textStyle(18.sp, FontWeight.SemiBold, c.text))
                }
                RepRun.Stage.Done -> Unit
            }
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            when (run.stage) {
                RepRun.Stage.Counting -> PrimaryButton(
                    if (run.isLastHand) "Stop" else "Stop · next: ${run.request.hands[run.handIndex + 1].label.lowercase()} hand",
                    onClick = { run.finishHand() },
                )
                RepRun.Stage.Finishing, RepRun.Stage.Done -> Unit
                else -> SecondaryButton("Cancel", onClick = { nav.pop() })
            }
        }
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Stop counting?") },
            text = { Text("Nothing from this set will be saved.") },
            confirmButton = { TextButton(onClick = { confirmQuit = false; nav.pop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun Finding(link: CamClient.Link) {
    val c = measureColors()
    Spacer(Modifier.height(80.dp))
    CircularProgressIndicator(color = c.blue, strokeWidth = 4.dp, modifier = Modifier.size(46.dp))
    Spacer(Modifier.height(23.dp))
    Text(link.label, style = textStyle(18.sp, FontWeight.SemiBold, c.text), textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(
        "On your second phone, open Grippy → Settings → Camera phone and turn on “Use this phone as the camera”. " +
            "After that it waits by itself, even locked.",
        style = textStyle(13.5.sp, FontWeight.Normal, c.text2), textAlign = TextAlign.Center,
    )
}

@Composable
private fun WakingUp(seconds: Int) {
    val c = measureColors()
    Spacer(Modifier.height(80.dp))
    CircularProgressIndicator(color = c.blue, strokeWidth = 4.dp, modifier = Modifier.size(46.dp))
    Spacer(Modifier.height(23.dp))
    Text("Waking the camera…", style = textStyle(18.sp, FontWeight.SemiBold, c.text), textAlign = TextAlign.Center)
    if (seconds >= 8) {
        Spacer(Modifier.height(10.dp))
        Text(
            "It is taking a while. Wake the camera phone by hand once and open Grippy on it; " +
                "then check Settings → Camera phone there, which lists what still needs allowing.",
            style = textStyle(13.5.sp, FontWeight.Normal, c.text2), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrackingPill(tracking: Tracking, counting: Boolean) {
    val c = measureColors()
    val (text, color) = when {
        tracking == Tracking.Hand -> "Hand found" to RepGreen
        tracking == Tracking.Motion && counting -> "Following the movement" to RepAmber
        else -> "No hand in view yet" to c.text3
    }
    Row(Modifier.clip(CircleShape).background(c.raised).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 8.dp)
        Spacer(Modifier.width(7.dp))
        Text(text, style = textStyle(13.sp, FontWeight.SemiBold, c.text))
    }
}

/** What the camera sees, as a drawn hand: enough to check the framing without sending pictures. */
@Composable
private fun SeenCard(run: RepRun, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101114)), contentAlignment = Alignment.Center) {
        if (run.seen == null) {
            Text("Camera view", style = textStyle(11.sp, FontWeight.Medium, Color(0x80FFFFFF)))
        } else {
            HandOverlay(run.seen, run.aspect, if (run.tracking == Tracking.Hand) RepGreen else RepAmber, Modifier.fillMaxSize().padding(6.dp), strokeDp = 2f)
        }
    }
}

@Composable
private fun RepCountdown(run: RepRun) {
    val c = measureColors()
    Spacer(Modifier.height(24.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(14.dp))
    Text(if (run.handIndex > 0) "Switch hands" else "Get in position", style = textStyle(19.5.sp, FontWeight.SemiBold, c.text2))
    Text(if (run.countdown > 0) "${run.countdown}" else "…", style = textStyle(110.sp, FontWeight.Bold, c.text, (-4).sp))
    Text("Counting starts in", style = textStyle(14.sp, FontWeight.Normal, c.text2))
    Spacer(Modifier.height(18.dp))
    SeenCard(run, Modifier.size(width = 120.dp, height = 160.dp))
    Spacer(Modifier.height(10.dp))
    TrackingPill(run.tracking, counting = false)
}

@Composable
private fun RepCounting(run: RepRun) {
    val c = measureColors()
    val handColor = if (run.hand == Hand.Left) c.left else c.right
    Spacer(Modifier.height(10.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(4.dp))
    AnimatedContent(
        targetState = run.count,
        transitionSpec = { (scaleIn(spring(dampingRatio = 0.55f), initialScale = 0.8f) + fadeIn()) togetherWith fadeOut() },
        label = "reps",
    ) { n ->
        Text("$n", style = textStyle(120.sp, FontWeight.Bold, c.text, (-4).sp),
            modifier = Modifier.semantics { contentDescription = "$n reps" })
    }
    Text("reps", style = textStyle(17.sp, FontWeight.SemiBold, c.text2))
    Spacer(Modifier.height(8.dp))
    val tempo = run.marks.filter { it.durationMs > 0 }.takeLast(5).map { it.durationMs }.average().takeIf { !it.isNaN() }
    Text(
        "${run.elapsed.toInt()} s" + (tempo?.let { " · ${"%.1f".format(Locale.US, it / 1000)} s per rep" } ?: ""),
        style = textStyle(15.sp, FontWeight.Medium, c.text2),
    )
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SeenCard(run, Modifier.size(width = 84.dp, height = 112.dp))
        Spacer(Modifier.width(12.dp))
        val window = maxOf(12, ceil(run.elapsed + 1).toInt())
        LiveCurve(run.signal.toList(), window, handColor, c.divider, Modifier.weight(1f).height(112.dp))
    }
    Spacer(Modifier.height(10.dp))
    TrackingPill(run.tracking, counting = true)
    Spacer(Modifier.height(8.dp))
    Text(if (run.count == 0) "The count shows after your 3rd rep (quick small reps: about the 8th). Press Stop when the set is done." else "Press Stop when the set is done.",
        style = textStyle(13.5.sp, FontWeight.Normal, c.text3), textAlign = TextAlign.Center)
    if (run.cameraFps > 0) {
        Spacer(Modifier.height(4.dp))
        val slow = run.cameraFps < SLOW_CAMERA_FPS
        Text(
            if (slow) "Camera at ${run.cameraFps} frames/s. More light lets it see quick reps." else "Camera at ${run.cameraFps} frames/s",
            style = textStyle(12.5.sp, FontWeight.Normal, if (slow) RepAmber else c.text3), textAlign = TextAlign.Center,
        )
    }
}

/** Below this the camera phone misses the turn of a rep that takes under a second. */
private const val SLOW_CAMERA_FPS = 20

/** After counting: the numbers (the count can be corrected), and which exercise they belong to. */
@Composable
fun RepReviewScreen(nav: MeasureNavigator, run: RepRun) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val settings = LocalSettingsStore.current
    val scope = rememberCoroutineScope()
    val preset = run.request.exerciseId?.let { data.exercise(it) }
    var picked by remember { mutableStateOf<Long?>(run.request.exerciseId) }
    var newName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler { confirmDiscard = true }

    val canSave = !saving && run.results.isNotEmpty() && (picked != null || newName.isNotBlank())

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("Result", onBack = { confirmDiscard = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Reps", style = textStyle(23.sp, FontWeight.Bold, c.text))
            Text(longDate(run.startedAt), style = textStyle(13.sp, FontWeight.Normal, c.text2))
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                run.request.hands.forEach { hand ->
                    val result = run.results[hand] ?: return@forEach
                    val color = if (hand == Hand.Left) c.left else c.right
                    OutlinedCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(color, 8.dp)
                                Spacer(Modifier.width(7.dp))
                                Text(hand.label, style = textStyle(14.sp, FontWeight.Medium, c.text2))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Stepper("−", "One rep fewer, ${hand.label}", enabled = result.reps > 0) { result.reps-- }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    ValueWithUnit("${result.reps}", "reps", 36.sp, c.text, c.text2)
                                }
                                Stepper("+", "One rep more, ${hand.label}", enabled = true) { result.reps++ }
                            }
                            if (result.reps != result.marks.size) {
                                Text("Camera counted ${result.marks.size}", style = textStyle(12.sp, FontWeight.Normal, c.text3),
                                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                            Spacer(Modifier.height(8.dp))
                            LiveCurve(result.signal, maxOf(1, ceil(result.signal.lastOrNull()?.t ?: 1.0).toInt()), color, c.divider,
                                Modifier.fillMaxWidth().height(57.dp))
                            Spacer(Modifier.height(8.dp))
                            val seconds = RepRun.secondsToSave(result)
                            StatLine("Time", "$seconds s")
                            StatLine("Tempo", if (result.reps > 0) "${"%.1f".format(Locale.US, result.seconds / result.reps)} s/rep" else "–")
                            StatLine("Counted from", if (result.tracking == Tracking.Hand) "your hand" else "movement")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Wrong count? Fix it with − and + before saving.", style = textStyle(12.5.sp, FontWeight.Normal, c.text3))
            Spacer(Modifier.height(18.dp))
            ExerciseChooser(data, preset, picked, newName, onPick = { picked = it; newName = "" }, onName = { newName = it; if (it.isNotBlank()) picked = null })
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            PrimaryButton(if (saving) "Saving…" else "Save", enabled = canSave, onClick = {
                saving = true
                scope.launch {
                    val exerciseId = picked ?: store.addExercise(newName, run.request.folderId)
                    store.save(run.request.hands.mapNotNull { hand ->
                        run.results[hand]?.let { r ->
                            MeasurementEntity.fromReps(exerciseId, run.sessionId, hand, RepRun.secondsToSave(r), run.startedAt,
                                r.signal, r.reps, r.marks, settings.bodyWeightKg)
                        }
                    })
                    nav.showExercise(exerciseId)
                }
            })
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Discard", style = textStyle(14.sp, FontWeight.Medium, c.text2))
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this result?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; nav.pop() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Stepper(sign: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = measureColors()
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.raised).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = textStyle(20.sp, FontWeight.SemiBold, if (enabled) c.text else c.text3))
    }
}
