// SPDX-License-Identifier: MPL-2.0
// Grippy: taking a measurement, and saving it to an exercise. Not part of upstream.

package app.grippy.measure

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.runner.KeepScreenOn
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalSettingsStore
import kotlin.math.ceil

private const val KEEP_ALIVE_HOLDER = "grippy.measure"

@Composable
fun MeasureRunScreen(nav: MeasureNavigator, run: MeasureRun) {
    val c = measureColors()
    val device = LocalDeviceStore.current
    val activity = LocalActivity.current
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() }
    var confirmQuit by remember { mutableStateOf(false) }

    KeepScreenOn(true)
    BackHandler { confirmQuit = true }

    DisposableEffect(device, run) {
        device.onTracePoint = { point -> run.receive(point.kg) }
        device.keepStreamAlive(KEEP_ALIVE_HOLDER, true)
        if (!device.state.isConnected && !device.state.isBusy) device.connect()
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                device.onTracePoint = null
                device.keepStreamAlive(KEEP_ALIVE_HOLDER, false)
                if (device.isStreaming) device.stopStreaming(StreamStopCause.measurementComplete)
            }
            tones?.release()
        }
    }
    LaunchedEffect(device, device.state.isConnected) {
        if (device.state.isConnected && !device.isStreaming) device.startStreaming(StreamStartCause.manualMeasurement)
    }
    LaunchedEffect(run) {
        if (run.stage == MeasureRun.Stage.Done) return@LaunchedEffect
        if (run.started) run.restart()
        snapshotFlow { device.state.isConnected }.first { it }
        run.started = true
        run.drive(
            tare = {
                // Zero only once the scale is actually reporting, so the zero is a real reading.
                withTimeoutOrNull(2_000) { snapshotFlow { device.isSignalFresh }.first { it } }
                device.tare()
            },
            cue = { cue ->
                when (cue) {
                    MeasureRun.Cue.Tick -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    MeasureRun.Cue.Go -> tones?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350)
                    MeasureRun.Cue.Rest -> tones?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                    MeasureRun.Cue.Stop -> tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, 400)
                }
            },
        )
    }
    LaunchedEffect(run.stage) {
        if (run.stage == MeasureRun.Stage.Done) {
            if (run.results.isEmpty()) nav.pop() else nav.replaceTop(MeasureRoute.Review(run))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(
            title = run.request.type.label + if (run.request.type == MeasureType.Endurance) " ${run.request.seconds} s" else "",
            onBack = { confirmQuit = true },
        )
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (run.stage) {
                MeasureRun.Stage.Connecting -> Connecting(device.state.label)
                MeasureRun.Stage.Countdown -> Countdown(run)
                MeasureRun.Stage.Waiting -> Waiting(run)
                MeasureRun.Stage.Recording -> if (run.isPeak) PeakRecording(run) else Recording(run)
                MeasureRun.Stage.Done -> Unit
            }
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            if (run.stage == MeasureRun.Stage.Recording) {
                if (run.isPeak) {
                    PrimaryButton(if (run.isLastHand) "Stop" else "Stop · next: ${run.request.hands[run.handIndex + 1].label.lowercase()} hand",
                        onClick = { run.finishHand() })
                } else {
                    SecondaryButton("Stop", onClick = { run.stopEarly() })
                }
            } else {
                SecondaryButton("Cancel", onClick = { nav.pop() })
            }
        }
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Stop measuring?") },
            text = { Text("Nothing from this measurement will be saved.") },
            confirmButton = { TextButton(onClick = { confirmQuit = false; nav.pop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun HandBadge(hand: Hand) {
    val c = measureColors()
    Row(Modifier.clip(CircleShape).background(c.raised).padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (hand == Hand.Left) c.left else c.right, 8.dp)
        Spacer(Modifier.width(7.dp))
        Text("${hand.label} hand", style = textStyle(14.sp, FontWeight.SemiBold, c.text))
    }
}

@Composable
private fun Connecting(label: String) {
    val c = measureColors()
    Spacer(Modifier.height(98.dp))
    CircularProgressIndicator(color = c.blue, strokeWidth = 4.dp, modifier = Modifier.size(46.dp))
    Spacer(Modifier.height(23.dp))
    Text("Connecting to your scale…", style = textStyle(18.sp, FontWeight.SemiBold, c.text), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text("Turn the scale on and keep it close. $label", style = textStyle(13.sp, FontWeight.Normal, c.text2), textAlign = TextAlign.Center)
}

@Composable
private fun Countdown(run: MeasureRun) {
    val c = measureColors()
    Spacer(Modifier.height(33.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(16.dp))
    Text(if (run.handIndex > 0) "Switch hands" else "Get ready", style = textStyle(19.5.sp, FontWeight.SemiBold, c.text2))
    Text(if (run.countdown > 0) "${run.countdown}" else "…", style = textStyle(123.sp, FontWeight.Bold, c.text, (-4).sp))
    Text("Starts in", style = textStyle(14.sp, FontWeight.Normal, c.text2))
    Spacer(Modifier.height(30.dp))
    Text("Scale reads ${formatKg(run.liveKg)} kg", style = textStyle(14.sp, FontWeight.Medium, c.text3))
}

/** Passive Peak: armed, and it starts by itself the moment they pull. */
@Composable
private fun Waiting(run: MeasureRun) {
    val c = measureColors()
    Spacer(Modifier.height(33.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(16.dp))
    Text(if (run.handIndex > 0) "Switch hands" else "Ready", style = textStyle(20.sp, FontWeight.SemiBold, c.text2))
    Spacer(Modifier.height(10.dp))
    Text("Pull when you're ready", style = textStyle(26.sp, FontWeight.Bold, c.text), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text("It starts when you pull. Pull as many times as you like, then press Stop.", style = textStyle(14.sp, FontWeight.Normal, c.text2),
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(30.dp))
    Text("Scale reads ${formatKg(run.liveKg)} kg", style = textStyle(14.sp, FontWeight.Medium, c.text3))
}

/** Peak: pull as often as you like; the best pull is the big number, each pull a chip. */
@Composable
private fun PeakRecording(run: MeasureRun) {
    val c = measureColors()
    val handColor = if (run.hand == Hand.Left) c.left else c.right
    val best = run.pullPeaks.maxOrNull()
    Spacer(Modifier.height(13.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(13.dp))
    Text(if (run.currentPullKg > 0) "Pull ${run.pullPeaks.size + 1} · keep pulling!" else if (run.pullPeaks.isEmpty()) "Pull as hard as you can!" else "Rest, then try to beat it",
        style = textStyle(20.sp, FontWeight.SemiBold, c.text2))
    Text("BEST", style = textStyle(14.sp, FontWeight.SemiBold, c.text3, 1.sp), modifier = Modifier.padding(top = 10.dp))
    ValueWithUnit(formatKg(run.peakKg), "kg", 85.sp, c.text, c.text2)
    Text(
        (if (run.currentPullKg > 0) "This pull ${formatKg(run.currentPullKg)} kg · " else "") + "Now ${formatKg(run.liveKg)} kg",
        style = textStyle(15.sp, FontWeight.Medium, c.text2),
    )
    Spacer(Modifier.height(12.dp))
    if (run.pullPeaks.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            run.pullPeaks.forEachIndexed { i, kg ->
                val top = kg == best
                Row(Modifier.clip(CircleShape).background(if (top) c.blueWash else c.raised).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = textStyle(12.sp, FontWeight.SemiBold, c.text3))
                    Spacer(Modifier.width(6.dp))
                    Text("${formatKg(kg)} kg", style = textStyle(14.sp, FontWeight.SemiBold, if (top) c.link else c.text))
                    if (top) Text(" ★", style = textStyle(13.sp, FontWeight.SemiBold, c.link))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    val window = maxOf(10, ceil(run.elapsed + 1).toInt())
    LiveCurve(run.points.toList(), window, handColor, c.divider, Modifier.fillMaxWidth().height(150.dp))
    Spacer(Modifier.height(10.dp))
    Text("${"%.0f".format(run.elapsed)} s · only your best pull counts. Press Stop when you're done.",
        style = textStyle(13.5.sp, FontWeight.Normal, c.text3), textAlign = TextAlign.Center)
}

@Composable
private fun Recording(run: MeasureRun) {
    val c = measureColors()
    val cf = run.request.type == MeasureType.CriticalForce
    val handColor = if (run.hand == Hand.Left) c.left else c.right
    Spacer(Modifier.height(13.dp))
    HandBadge(run.hand)
    Spacer(Modifier.height(13.dp))
    if (cf) {
        val pulling = run.cfPulling
        Box(Modifier.clip(CircleShape).background(if (pulling) c.blue else c.raised).padding(horizontal = 18.dp, vertical = 7.dp)) {
            Text(if (pulling) "PULL · ${ceil(run.cfPhaseLeft).toInt()} s" else "REST · ${ceil(run.cfPhaseLeft).toInt()} s",
                style = textStyle(15.sp, FontWeight.Bold, if (pulling) Color.White else c.text, 1.sp))
        }
        Spacer(Modifier.height(7.dp))
        Text("Rep ${run.cfRep + 1} / $CF_REPS", style = textStyle(15.sp, FontWeight.SemiBold, c.text2))
    } else {
        Text("Pull!", style = textStyle(19.5.sp, FontWeight.SemiBold, c.text2))
    }
    ValueWithUnit(formatKg(run.liveKg), "kg", 85.5.sp, c.text, c.text2)
    Text("Peak ${formatKg(run.peakKg)} kg", style = textStyle(15.sp, FontWeight.Medium, c.text2))
    Spacer(Modifier.height(20.dp))
    val left = (run.windowSeconds - run.elapsed).coerceAtLeast(0.0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${run.elapsed.toInt()} s", style = textStyle(13.5.sp, FontWeight.Medium, c.text3))
        Text("${ceil(left).toInt()} s left", style = textStyle(13.5.sp, FontWeight.Medium, c.text3))
    }
    Spacer(Modifier.height(7.dp))
    LinearProgressIndicator(
        progress = { (run.elapsed / run.windowSeconds).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
        color = c.blue, trackColor = c.raised, drawStopIndicator = {},
    )
    Spacer(Modifier.height(20.dp))
    LiveCurve(run.points.toList(), run.windowSeconds, handColor, c.divider, Modifier.fillMaxWidth().height(164.dp))
}

/** After measuring: the numbers, and which exercise they belong to. */
@Composable
fun MeasureReviewScreen(nav: MeasureNavigator, run: MeasureRun) {
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

    val type = run.request.type
    val canSave = !saving && run.results.isNotEmpty() && (picked != null || newName.isNotBlank())

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("Result", onBack = { confirmDiscard = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(GroupKey(type, run.request.seconds).title, style = textStyle(23.sp, FontWeight.Bold, c.text))
            Text(longDate(run.startedAt), style = textStyle(13.sp, FontWeight.Normal, c.text2))
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                run.request.hands.forEach { hand ->
                    val points = run.results[hand] ?: return@forEach
                    val draft = MeasurementEntity.from(0, run.sessionId, type, hand, run.secondsFor(hand), run.startedAt, points, settings.bodyWeightKg)
                    OutlinedCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(if (hand == Hand.Left) c.left else c.right, 8.dp)
                                Spacer(Modifier.width(7.dp))
                                Text(hand.label, style = textStyle(14.sp, FontWeight.Medium, c.text2))
                            }
                            Spacer(Modifier.height(7.dp))
                            ValueWithUnit(formatNumber(draft.headline, type.headline.unit), type.headline.unit, 33.sp, c.text, c.text2)
                            Text(type.headline.label, style = textStyle(12.5.sp, FontWeight.Normal, c.text3))
                            Spacer(Modifier.height(8.dp))
                            LiveCurve(MeasureMath.decode(draft.samples), draft.durationSec, if (hand == Hand.Left) c.left else c.right, c.divider,
                                Modifier.fillMaxWidth().height(57.dp))
                            Spacer(Modifier.height(8.dp))
                            StatLine("Max", "${formatKg(draft.maxKg)} kg")
                            StatLine("Average", "${formatKg(draft.avgKg)} kg")
                            if (type == MeasureType.Rfd) StatLine("RFD", "${draft.rfd.toInt()} kg/s")
                            if (type == MeasureType.CriticalForce) StatLine("CF", "${formatKg(draft.cfKg)} kg")
                        }
                    }
                }
            }
            Spacer(Modifier.height(23.dp))
            if (preset != null) {
                Text("Exercise", style = textStyle(15.5.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(8.dp))
                ExerciseRow(preset, data.of(preset.id)) {}
            } else {
                Text("Pick the exercise", style = textStyle(15.5.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(49.dp).clip(RoundedCornerShape(15.dp)).background(c.raised).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (newName.isEmpty()) Text("New exercise, e.g. supinated front raise", style = textStyle(14.sp, FontWeight.Normal, c.text3),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(newName, { newName = it; if (it.isNotBlank()) picked = null }, singleLine = true,
                            textStyle = textStyle(14.sp, FontWeight.Normal, c.text), cursorBrush = SolidColor(c.blue),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (data.exercises.isNotEmpty()) {
                    Spacer(Modifier.height(13.dp))
                    OutlinedCard {
                        data.exercises.sortedByDescending { e -> data.of(e.id).maxOfOrNull { it.takenAt } ?: e.createdAt }.forEachIndexed { i, e ->
                            Row(Modifier.fillMaxWidth().clickable { picked = e.id; newName = "" }.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(picked == e.id, onClick = { picked = e.id; newName = "" },
                                    colors = RadioButtonDefaults.colors(selectedColor = c.blue, unselectedColor = c.text3))
                                Spacer(Modifier.width(7.dp))
                                Text(e.name, style = textStyle(14.sp, FontWeight.Medium, c.text), modifier = Modifier.weight(1f))
                            }
                            if (i < data.exercises.lastIndex) HorizontalDivider(Modifier.padding(start = 52.dp), color = c.divider)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            PrimaryButton(if (saving) "Saving…" else "Save", enabled = canSave, onClick = {
                saving = true
                scope.launch {
                    val exerciseId = picked ?: store.addExercise(newName, run.request.folderId)
                    store.save(run.request.hands.mapNotNull { hand ->
                        run.results[hand]?.let { points ->
                            MeasurementEntity.from(exerciseId, run.sessionId, type, hand, run.secondsFor(hand), run.startedAt, points, settings.bodyWeightKg)
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
private fun StatLine(label: String, value: String) {
    val c = measureColors()
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = textStyle(12.5.sp, FontWeight.Normal, c.text2), modifier = Modifier.weight(1f))
        Text(value, style = textStyle(12.5.sp, FontWeight.SemiBold, c.text))
    }
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Close, "Close", tint = measureColors().text) }
}
