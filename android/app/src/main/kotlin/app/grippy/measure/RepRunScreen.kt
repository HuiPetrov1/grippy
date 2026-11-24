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
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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

/** The name a set is shown under: its exercise's, once known. */
internal fun RepSet.title(data: MeasureSnapshot): String =
    exerciseId?.let { data.exercise(it)?.name } ?: exercise.ifBlank { "Reps" }

/** "Pronation · 2 kg" — what the camera phone shows. */
private fun RepSet.cameraTitle(data: MeasureSnapshot): String =
    listOfNotNull(title(data), weightKg?.let { kgText(it) }).joinToString(" · ")

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
            frontCamera = CameraRole.frontCamera(context),
            title = { it.cameraTitle(data) },
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
        TopBar(title = run.planName ?: "Reps · camera", onBack = { confirmQuit = true })
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (run.stage) {
                RepRun.Stage.Connecting -> Finding(client.link)
                RepRun.Stage.Waking -> WakingUp(run.wakingFor)
                RepRun.Stage.Ready -> ReadyBetweenSets(run, data)
                RepRun.Stage.Countdown -> RepCountdown(run, data)
                RepRun.Stage.Counting -> CardStack(run, data)
                RepRun.Stage.Finishing -> {
                    Spacer(Modifier.height(98.dp))
                    CircularProgressIndicator(color = c.blue, strokeWidth = 4.dp, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.height(23.dp))
                    Text("Getting the count from the camera…", style = textStyle(18.sp, FontWeight.SemiBold, c.text))
                }
                RepRun.Stage.Done -> Unit
            }
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (run.stage) {
                RepRun.Stage.Ready -> {
                    if (run.allDone) PrimaryButton("Finish & review", onClick = { run.finishSession() })
                    else PrimaryButton("Start", onClick = { run.start() })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (!run.allDone) {
                            TextButton(onClick = { run.skip() }) { Text("Skip this set", style = textStyle(14.sp, FontWeight.Medium, c.text2)) }
                        } else Spacer(Modifier.width(1.dp))
                        if (!run.allDone && run.results.isNotEmpty()) {
                            TextButton(onClick = { run.finishSession() }) { Text("End session", style = textStyle(14.sp, FontWeight.Medium, c.text2)) }
                        }
                    }
                }
                RepRun.Stage.Counting, RepRun.Stage.Finishing, RepRun.Stage.Done -> Unit
                else -> SecondaryButton("Cancel", onClick = { nav.pop() })
            }
        }
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Stop the session?") },
            text = { Text(if (run.results.isEmpty()) "Nothing has been counted yet." else "The sets counted so far are not saved.") },
            confirmButton = { TextButton(onClick = { confirmQuit = false; nav.pop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep going") } },
        )
    }
}

/** Screenshots only: the session screen's middle part for [run] as it stands, without a camera phone. */
@Composable
internal fun RepSessionPreview(run: RepRun, data: MeasureSnapshot) {
    val c = measureColors()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(title = run.planName ?: "Reps · camera", onBack = {})
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (run.stage == RepRun.Stage.Counting) CardStack(run, data) else ReadyBetweenSets(run, data)
        }
        if (run.stage == RepRun.Stage.Ready) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                PrimaryButton("Start", onClick = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {}) { Text("Skip this set", style = textStyle(14.sp, FontWeight.Medium, c.text2)) }
                    TextButton(onClick = {}) { Text("End session", style = textStyle(14.sp, FontWeight.Medium, c.text2)) }
                }
            }
        }
    }
}

/** The exercise's drawing on its dark tile (the art is drawn on that colour). */
@Composable
internal fun ExerciseArt(name: String, size: Dp, modifier: Modifier = Modifier) {
    val art = exerciseArt(name)
    Box(modifier.size(size).clip(RoundedCornerShape(size / 5)).background(ArtTile), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(painterResource(art), contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1).uppercase(), style = textStyle((size.value * 0.4f).sp, FontWeight.Bold, Color(0xFF8E8E93)))
        }
    }
}

private val ArtTile = Color(0xFF1B1B20)

private fun armLabel(hand: Hand) = "${hand.label} arm"

/** Between sets: what was just done (can be resumed), how long the rest has been, and what is next. Nothing starts by itself. */
@Composable
private fun ReadyBetweenSets(run: RepRun, data: MeasureSnapshot) {
    val c = measureColors()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(6.dp))
        val last = run.lastDone?.let { i -> run.results[i]?.let { i to it } }
        if (last != null) {
            val (i, result) = last
            OutlinedCard {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(RepGreen), contentAlignment = Alignment.Center) {
                        Text("✓", style = textStyle(17.sp, FontWeight.Bold, Color.Black))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${armLabel(result.set.hand)} done · ${result.reps} reps", style = textStyle(17.sp, FontWeight.SemiBold, c.text))
                        Text(run.sets[i].title(data), style = textStyle(13.sp, FontWeight.Normal, c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { run.resume() }) {
                        Text("↺ Resume", style = textStyle(14.sp, FontWeight.SemiBold, c.link))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (run.restFrom == 0L) {
                    Text("Ready when you are", style = textStyle(22.sp, FontWeight.Bold, c.text))
                    Spacer(Modifier.height(4.dp))
                    Text("Tap Start when you're in position.", style = textStyle(14.sp, FontWeight.Normal, c.text2))
                } else {
                    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                    LaunchedEffect(run.restFrom) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
                    val rest = ((now - run.restFrom) / 1000).coerceAtLeast(0)
                    Text("Rest", style = textStyle(17.sp, FontWeight.SemiBold, c.text2))
                    Text("${rest / 60}:${"%02d".format(rest % 60)}", style = textStyle(76.sp, FontWeight.Bold, c.blue, (-2).sp))
                    Text("Your pace: start whenever you're ready.", style = textStyle(13.sp, FontWeight.Normal, c.text3))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val next = run.set
        if (next == null) {
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("All sets done", style = textStyle(24.sp, FontWeight.Bold, c.text))
                    Spacer(Modifier.height(4.dp))
                    val total = run.results.values.sumOf { it.reps }
                    Text("${run.results.size} sets · $total reps. Resume the last one, or finish and check the counts.",
                        style = textStyle(14.sp, FontWeight.Normal, c.text2))
                }
            }
        } else {
            OutlinedCard(onClick = { run.start() }, borderColor = c.blue.copy(alpha = .55f)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Up next · Set ${run.index + 1} of ${run.sets.size}", style = textStyle(14.sp, FontWeight.SemiBold, c.link))
                        Spacer(Modifier.height(4.dp))
                        Text(armLabel(next.hand), style = textStyle(30.sp, FontWeight.Bold, c.text, (-0.5).sp))
                        Text(listOfNotNull(next.title(data), next.weightKg?.let { kgText(it) }).joinToString(" · "),
                            style = textStyle(16.sp, FontWeight.Medium, c.text2))
                    }
                    Spacer(Modifier.width(10.dp))
                    ExerciseArt(next.title(data), 104.dp)
                }
            }
            val later = run.sets.drop(run.index + 1).take(3)
            if (later.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Then", style = textStyle(13.sp, FontWeight.SemiBold, c.text3))
                later.forEach { s ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (s.hand == Hand.Left) c.left else c.right, 7.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${s.title(data)} · ${s.weightKg?.let { kgText(it) + " · " } ?: ""}${s.hand.label}",
                            style = textStyle(14.sp, FontWeight.Normal, c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun RepCountdown(run: RepRun, data: MeasureSnapshot) {
    val c = measureColors()
    val set = run.sets.getOrNull(run.countingIndex) ?: return
    Spacer(Modifier.height(18.dp))
    ExerciseArt(set.title(data), 120.dp)
    Spacer(Modifier.height(12.dp))
    Text(set.title(data), style = textStyle(20.sp, FontWeight.Bold, c.text), textAlign = TextAlign.Center)
    Text(listOfNotNull(set.weightKg?.let { kgText(it) }, armLabel(set.hand)).joinToString(" · "), style = textStyle(16.sp, FontWeight.Medium, c.text2))
    Text(if (run.countdown > 0) "${run.countdown}" else "…", style = textStyle(110.sp, FontWeight.Bold, c.text, (-4).sp))
    Text("Counting starts in", style = textStyle(14.sp, FontWeight.Normal, c.text2))
    Spacer(Modifier.height(14.dp))
    SeenCard(run, Modifier.size(width = 96.dp, height = 128.dp))
    Spacer(Modifier.height(8.dp))
    TrackingPill(run.tracking, counting = false)
}

/** Counting: the set as a big card (tap it to finish the set), the next sets peeking out above it. */
@Composable
private fun CardStack(run: RepRun, data: MeasureSnapshot) {
    val c = measureColors()
    val position = run.countingIndex
    val counting = run.sets.getOrNull(position) ?: return
    val next = run.sets.drop(position + 1).take(2)
    Box(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp)) {
        next.getOrNull(1)?.let { PeekCard(it, data, Modifier.padding(horizontal = 30.dp)) }
        next.getOrNull(0)?.let { PeekCard(it, data, Modifier.padding(horizontal = 15.dp, vertical = 0.dp).padding(top = 20.dp)) }
        val top = when (next.size) { 0 -> 0.dp; 1 -> 22.dp; else -> 42.dp }
        OutlinedCard(Modifier.padding(top = top).fillMaxSize(), onClick = { run.finishSet() }, radius = 26.dp, color = c.card) {
            CountingCard(run, counting, position, next.firstOrNull(), data)
        }
    }
}

@Composable
private fun PeekCard(set: RepSet, data: MeasureSnapshot, modifier: Modifier) {
    val c = measureColors()
    Box(modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(c.raised)
        .border(BorderStroke(1.dp, c.border), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))) {
        Text(listOfNotNull(set.title(data), set.weightKg?.let { kgText(it) }, armLabel(set.hand)).joinToString(" · "),
            style = textStyle(13.sp, FontWeight.SemiBold, c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun CountingCard(run: RepRun, set: RepSet, position: Int, next: RepSet?, data: MeasureSnapshot) {
    val c = measureColors()
    val handColor = if (set.hand == Hand.Left) c.left else c.right
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ExerciseArt(set.title(data), 76.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(set.title(data), style = textStyle(19.sp, FontWeight.Bold, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(set.weightKg?.let { kgText(it) }, armLabel(set.hand)).joinToString(" · "),
                    style = textStyle(15.sp, FontWeight.Medium, c.text2))
                Spacer(Modifier.height(5.dp))
                Text("Set ${position + 1} of ${run.sets.size}", style = textStyle(12.5.sp, FontWeight.SemiBold, Color.White),
                    modifier = Modifier.clip(CircleShape).background(c.blue).padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        val shown = run.resumedFrom + run.count
        AnimatedContent(
            targetState = shown,
            transitionSpec = { (scaleIn(spring(dampingRatio = 0.55f), initialScale = 0.8f) + fadeIn()) togetherWith fadeOut() },
            label = "reps",
        ) { n ->
            Text("$n", style = textStyle(124.sp, FontWeight.Bold, c.blue, (-4).sp),
                modifier = Modifier.semantics { contentDescription = "$n reps" })
        }
        Text(if (run.resumedFrom > 0) "reps · ${run.resumedFrom} before the break" else "reps", style = textStyle(17.sp, FontWeight.SemiBold, c.text2))
        Spacer(Modifier.height(6.dp))
        Text(
            next?.let { "Next: " + if (it.exerciseId == set.exerciseId && it.weightKg == set.weightKg) armLabel(it.hand) else "${it.title(data)} · ${armLabel(it.hand)}" }
                ?: "Last set",
            style = textStyle(16.sp, FontWeight.Medium, c.text2),
        )
        Spacer(Modifier.weight(1f))
        val tempo = run.marks.filter { it.durationMs > 0 }.takeLast(5).map { it.durationMs }.average().takeIf { !it.isNaN() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Text("${run.elapsed.toInt() / 60}:${"%02d".format(run.elapsed.toInt() % 60)}", style = textStyle(15.sp, FontWeight.SemiBold, c.text))
            Text(tempo?.let { "${"%.1f".format(Locale.US, it / 1000)} s/rep" } ?: "– s/rep", style = textStyle(15.sp, FontWeight.SemiBold, c.text))
            TrackingPill(run.tracking, counting = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SeenCard(run, Modifier.size(width = 66.dp, height = 88.dp))
            Spacer(Modifier.width(10.dp))
            val window = maxOf(12, ceil(run.elapsed + 1).toInt())
            LiveCurve(run.signal.toList(), window, handColor, c.divider, Modifier.weight(1f).height(88.dp))
        }
        Spacer(Modifier.height(10.dp))
        val hint = if (run.count == 0) "Counting shows after your 3rd rep (quick small reps: about the 8th)." else null
        Text(listOfNotNull("Tap the card to finish this set", hint).joinToString("\n"),
            style = textStyle(13.5.sp, FontWeight.Medium, c.text3), textAlign = TextAlign.Center)
        if (run.cameraFps in 1 until SLOW_CAMERA_FPS) {
            Text("Camera at ${run.cameraFps} frames/s. More light lets it see quick reps.",
                style = textStyle(12.5.sp, FontWeight.Normal, RepAmber), textAlign = TextAlign.Center)
        }
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

/** Below this the camera phone misses the turn of a rep that takes under a second. */
private const val SLOW_CAMERA_FPS = 20

/** After the session: every set's count (it can be corrected), and — when not known yet — the exercise. */
@Composable
fun RepReviewScreen(nav: MeasureNavigator, run: RepRun) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val settings = LocalSettingsStore.current
    val scope = rememberCoroutineScope()
    val needsExercise = run.results.values.any { it.set.exerciseId == null }
    var picked by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler { confirmDiscard = true }

    val done = run.results.keys.sorted().mapNotNull { i -> run.results[i] }
    val canSave = !saving && done.isNotEmpty() && (!needsExercise || picked != null || newName.isNotBlank())

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("Result", onBack = { confirmDiscard = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(run.planName ?: "Reps", style = textStyle(23.sp, FontWeight.Bold, c.text))
            Text("${longDate(run.startedAt)} · ${done.size} sets · ${done.sumOf { it.reps }} reps", style = textStyle(13.sp, FontWeight.Normal, c.text2))
            Spacer(Modifier.height(15.dp))
            done.forEach { result ->
                val set = result.set
                val color = if (set.hand == Hand.Left) c.left else c.right
                OutlinedCard {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExerciseArt(set.title(data), 48.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(set.title(data), style = textStyle(15.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(color, 7.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(listOfNotNull("${set.hand.label} arm", set.weightKg?.let { kgText(it) }).joinToString(" · "),
                                        style = textStyle(13.sp, FontWeight.Medium, c.text2))
                                }
                            }
                            Stepper("−", "One rep fewer", enabled = result.reps > 0) { result.reps-- }
                            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                                ValueWithUnit("${result.reps}", "reps", 26.sp, c.text, c.text2)
                            }
                            Stepper("+", "One rep more", enabled = true) { result.reps++ }
                        }
                        Spacer(Modifier.height(8.dp))
                        LiveCurve(result.signal, maxOf(1, ceil(result.signal.lastOrNull()?.t ?: 1.0).toInt()), color, c.divider,
                            Modifier.fillMaxWidth().height(44.dp))
                        Spacer(Modifier.height(6.dp))
                        val seconds = RepRun.secondsToSave(result)
                        val tempo = if (result.reps > 0) "${"%.1f".format(Locale.US, result.seconds / result.reps)} s/rep" else "–"
                        val camera = if (result.reps != result.marks.size) " · camera counted ${result.marks.size}" else ""
                        Text("$seconds s · $tempo · from ${if (result.tracking == Tracking.Hand) "your hand" else "movement"}$camera",
                            style = textStyle(12.5.sp, FontWeight.Normal, c.text3))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("Wrong count? Fix it with − and + before saving.", style = textStyle(12.5.sp, FontWeight.Normal, c.text3))
            if (needsExercise) {
                Spacer(Modifier.height(18.dp))
                ExerciseChooser(data, null, picked, newName, onPick = { picked = it; newName = "" }, onName = { newName = it; if (it.isNotBlank()) picked = null })
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            PrimaryButton(if (saving) "Saving…" else "Save", enabled = canSave, onClick = {
                saving = true
                scope.launch {
                    val chosen = if (needsExercise) picked ?: store.addExercise(newName, run.folderId) else null
                    // Both arms of one exercise share a session, as with the scale.
                    val sessions = HashMap<Long, String>()
                    val rows = done.map { r ->
                        val exerciseId = r.set.exerciseId ?: chosen!!
                        val session = sessions.getOrPut(exerciseId) { java.util.UUID.randomUUID().toString() }
                        MeasurementEntity.fromReps(exerciseId, session, r.set.hand, RepRun.secondsToSave(r), r.takenAt,
                            r.signal, r.reps, r.marks, settings.bodyWeightKg, r.set.weightKg)
                    }
                    store.save(rows)
                    val exercises = rows.map { it.exerciseId }.distinct()
                    if (exercises.size == 1) nav.showExercise(exercises.single()) else nav.closeRun()
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
            title = { Text("Discard this session?") },
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
