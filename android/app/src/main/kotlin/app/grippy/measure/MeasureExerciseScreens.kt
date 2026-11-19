// SPDX-License-Identifier: MPL-2.0
// Grippy: an exercise (Summary / Measure / How to) and one measurement type's history.
// Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ExerciseScreen(nav: MeasureNavigator, route: MeasureRoute.Exercise) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val exercise = data.exercise(route.id)
    BackHandler { nav.pop() }
    if (exercise == null) {
        // Deleted, or the store has not loaded yet.
        Column(Modifier.fillMaxSize().background(c.bg)) { TopBar("", onBack = { nav.pop() }) }
        return
    }
    var tab by rememberSaveable(route.id) { mutableStateOf(route.tab) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val measurements = data.of(exercise.id)

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(exercise.name, onBack = { nav.pop() }, actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, "More", tint = c.text, modifier = Modifier.size(30.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; moving = true })
                    DropdownMenuItem(text = { Text("Delete exercise") }, onClick = { menu = false; deleting = true })
                }
            }
        })
        Row(Modifier.fillMaxWidth()) {
            listOf("Summary", "Measure", "How to").forEachIndexed { i, label ->
                Column(Modifier.weight(1f).clickable { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = textStyle(21.sp, if (tab == i) FontWeight.SemiBold else FontWeight.Medium, if (tab == i) c.text else c.text3),
                        modifier = Modifier.padding(vertical = 14.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (tab == i) c.text else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = c.divider)
        when (tab) {
            0 -> SummaryTab(exercise, measurements)
            1 -> MeasureListTab(nav, exercise, measurements)
            else -> HowToTab(exercise, store)
        }
    }

    if (renaming) NameDialog("Rename exercise", exercise.name, "Save",
        onDone = { if (it.isNotBlank()) store.updateExercise(exercise.copy(name = it.trim())); renaming = false },
        onDismiss = { renaming = false })
    if (moving) {
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to folder") },
            text = {
                Column {
                    (listOf<Pair<Long?, String>>(null to "My Measures") + data.folders.map { it.id to it.name }).forEach { (id, name) ->
                        Text(name, style = textStyle(18.sp, if (exercise.folderId == id) FontWeight.Bold else FontWeight.Normal, c.text),
                            modifier = Modifier.fillMaxWidth().clickable { store.updateExercise(exercise.copy(folderId = id)); moving = false }
                                .padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${exercise.name}?") },
            text = { Text("Its ${measurements.size} results are deleted with it. This can't be undone.") },
            confirmButton = { TextButton(onClick = { deleting = false; nav.pop(); store.deleteExercise(exercise.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Measurements grouped into Frez's cards, newest group first. */
fun groupsOf(measurements: List<MeasurementEntity>): List<Pair<GroupKey, List<MeasurementEntity>>> =
    measurements.groupBy { GroupKey.of(it) }.toList().sortedByDescending { (_, ms) -> ms.maxOf { it.takenAt } }

@Composable
private fun MeasureListTab(nav: MeasureNavigator, exercise: ExerciseEntity, measurements: List<MeasurementEntity>) {
    val c = measureColors()
    val groups = groupsOf(measurements)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Measurements", style = textStyle(27.sp, FontWeight.Bold, c.text, (-0.3).sp))
                Spacer(Modifier.width(10.dp))
                Text("${groups.size}", style = textStyle(24.sp, FontWeight.Medium, c.text3))
                Spacer(Modifier.weight(1f))
                SmallPill("Measure", onClick = { nav.openStart(StartContext(exerciseId = exercise.id)) }, icon = Icons.Filled.Add)
            }
        }
        if (groups.isEmpty()) item { EmptyNote("Nothing measured yet. Tap Measure to take the first one.") }
        items(groups, key = { it.first.id }) { (key, ms) ->
            GroupCard(key, ms, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                nav.push(MeasureRoute.Group(exercise.id, key.id))
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

@Composable
private fun GroupCard(key: GroupKey, ms: List<MeasurementEntity>, modifier: Modifier, onClick: () -> Unit) {
    val c = measureColors()
    val latest = ms.maxOf { it.takenAt }
    val metric = key.type.headline
    OutlinedCard(modifier, onClick = onClick, radius = 30.dp) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(key.type.dot)
            Spacer(Modifier.width(14.dp))
            Text(key.title, style = textStyle(21.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f), maxLines = 1)
            Text(longDate(latest), style = textStyle(16.sp, FontWeight.Normal, c.text2))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2, modifier = Modifier.size(26.dp))
        }
        HorizontalDivider(color = c.divider)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Hand.entries.forEach { hand ->
                val last = ms.filter { it.side == hand }.maxByOrNull { it.takenAt }
                Column(Modifier.padding(end = 26.dp)) {
                    Text(hand.label, style = textStyle(16.sp, FontWeight.Normal, c.text2))
                    Spacer(Modifier.height(10.dp))
                    Text(last?.let { "${formatNumber(metric.of(it), metric.unit)} ${metric.unit}" } ?: "—",
                        style = textStyle(22.sp, FontWeight.SemiBold, c.text))
                }
            }
            Spacer(Modifier.weight(1f))
            Sparkline(
                left = dailyPoints(ms.filter { it.side == Hand.Left }, metric, false),
                right = dailyPoints(ms.filter { it.side == Hand.Right }, metric, false),
                leftColor = c.left, rightColor = c.right,
                modifier = Modifier.width(130.dp).height(64.dp),
            )
        }
    }
}

@Composable
private fun SummaryTab(exercise: ExerciseEntity, measurements: List<MeasurementEntity>) {
    val c = measureColors()
    val groups = groupsOf(measurements)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        item {
            OutlinedCard {
                Row(Modifier.padding(20.dp)) {
                    SummaryFigure("Results", "${measurements.size}", Modifier.weight(1f))
                    SummaryFigure("First", measurements.minOfOrNull { it.takenAt }?.let(::shortDate) ?: "—", Modifier.weight(1f))
                    SummaryFigure("Latest", measurements.maxOfOrNull { it.takenAt }?.let(::shortDate) ?: "—", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(20.dp))
            if (groups.isNotEmpty()) CapsHeader("Personal bests")
            Spacer(Modifier.height(12.dp))
        }
        if (groups.isEmpty()) item { EmptyNote("Measure ${exercise.name} to see your bests and left–right balance here.") }
        items(groups, key = { it.first.id }) { (key, ms) ->
            val metric = key.type.headline
            val bestL = ms.filter { it.side == Hand.Left }.maxOfOrNull { metric.of(it) }
            val bestR = ms.filter { it.side == Hand.Right }.maxOfOrNull { metric.of(it) }
            OutlinedCard(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(key.type.dot)
                        Spacer(Modifier.width(12.dp))
                        Text(key.title, style = textStyle(19.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                        Text("${ms.size} results", style = textStyle(15.sp, FontWeight.Normal, c.text2))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        SummaryFigure("Best left", bestL?.let { "${formatNumber(it, metric.unit)} ${metric.unit}" } ?: "—", Modifier.weight(1f), c.left)
                        SummaryFigure("Best right", bestR?.let { "${formatNumber(it, metric.unit)} ${metric.unit}" } ?: "—", Modifier.weight(1f), c.right)
                        SummaryFigure("Balance", imbalance(bestL, bestR)?.let { "${(it * 100).roundToInt()} %" } ?: "—", Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, modifier: Modifier = Modifier, dot: androidx.compose.ui.graphics.Color? = null) {
    val c = measureColors()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) { Dot(dot, 8.dp); Spacer(Modifier.width(6.dp)) }
            Text(label, style = textStyle(14.sp, FontWeight.Normal, c.text2))
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = textStyle(20.sp, FontWeight.SemiBold, c.text))
    }
}

@Composable
private fun HowToTab(exercise: ExerciseEntity, store: MeasureStore) {
    val c = measureColors()
    var text by remember(exercise.id) { mutableStateOf(exercise.howTo) }
    LaunchedEffect(text) {
        if (text == exercise.howTo) return@LaunchedEffect
        delay(600)
        store.updateExercise(exercise.copy(howTo = text))
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("How to", style = textStyle(26.sp, FontWeight.Bold, c.text))
        Spacer(Modifier.height(6.dp))
        Text("Your own notes: setup, grip, body position, what to watch for.", style = textStyle(16.sp, FontWeight.Normal, c.text2))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(20.dp)).background(c.raised).padding(18.dp)) {
            if (text.isEmpty()) Text("e.g. Strap on the wrist, elbow locked, raise to shoulder height…", style = textStyle(17.sp, FontWeight.Normal, c.text3))
            BasicTextField(text, { text = it }, textStyle = textStyle(17.sp, FontWeight.Normal, c.text),
                cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
        }
    }
}

private enum class Range(val label: String, val days: Int?) { M1("1M", 30), M3("3M", 91), M6("6M", 182), Y1("1Y", 365), All("All", null) }

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

@Composable
fun GroupScreen(nav: MeasureNavigator, route: MeasureRoute.Group) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val exercise = data.exercise(route.exerciseId)
    val key = GroupKey.parse(route.key)
    BackHandler { nav.pop() }
    val all = data.of(route.exerciseId).filter { GroupKey.of(it) == key }
    var metric by rememberSaveable(route.key) { mutableStateOf(key.type.headline) }
    var range by rememberSaveable { mutableStateOf(Range.All) }
    var percent by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val usePercent = percent && metric.isForce

    val now = System.currentTimeMillis()
    val inRange = range.days?.let { d -> all.filter { it.takenAt >= now - d * 86_400_000L } } ?: all
    val (from, to) = if (range.days != null) (now - range.days!! * 86_400_000L) to now else {
        val lo = inRange.minOfOrNull { it.takenAt } ?: now
        val hi = inRange.maxOfOrNull { it.takenAt } ?: now
        val pad = ((hi - lo) / 6).coerceAtLeast(86_400_000L)
        (lo - pad) to (hi + pad)
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                TopBar(exercise?.name ?: "", onBack = { nav.pop() }, titleExtra = { TypeChip(key.type) }, actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = c.text) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Open latest result") }, enabled = all.isNotEmpty(), onClick = {
                                menu = false
                                all.maxByOrNull { it.takenAt }?.let { nav.push(MeasureRoute.Result(it.sessionId)) }
                            })
                        }
                    }
                })
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend("Left", c.left)
                    Spacer(Modifier.width(14.dp))
                    Legend("Right", c.right)
                    Spacer(Modifier.weight(1f))
                    Segmented(key.type.metrics, metric, { it.label }, { metric = it }, height = 48.dp, equal = false, fontSize = 13.5.sp)
                }
                Text("${metric.label} (${if (usePercent) "%BW" else metric.unit})", style = textStyle(26.sp, FontWeight.Bold, c.text),
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
                HistoryChart(
                    left = dailyPoints(inRange.filter { it.side == Hand.Left }, metric, usePercent),
                    right = dailyPoints(inRange.filter { it.side == Hand.Right }, metric, usePercent),
                    from = from, to = to, leftColor = c.left, rightColor = c.right,
                    ink = c.text2, grid = c.divider, background = c.bg,
                    modifier = Modifier.fillMaxWidth().height(300.dp).padding(end = 4.dp),
                )
                Segmented(Range.entries, range, { it.label }, { range = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), height = 56.dp)
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CapsHeader("Latest", Modifier.weight(1f))
                    if (metric.isForce) Segmented(listOf(false, true), percent, { if (it) "%BW" else "kg" }, { percent = it }, Modifier.width(160.dp), height = 50.dp)
                }
                Text("${if (range == Range.All) "All time" else range.label} · ${metric.label}", style = textStyle(16.sp, FontWeight.Normal, c.text2),
                    modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                    Hand.entries.forEachIndexed { i, hand ->
                        if (i > 0) { VerticalDivider(Modifier.fillMaxHeight(), color = c.divider); Spacer(Modifier.width(20.dp)) }
                        val last = inRange.filter { it.side == hand }.maxByOrNull { it.takenAt }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(if (hand == Hand.Left) c.left else c.right)
                                Spacer(Modifier.width(8.dp))
                                Text(hand.label, style = textStyle(18.sp, FontWeight.Medium, c.text2))
                            }
                            Spacer(Modifier.height(10.dp))
                            if (last != null) {
                                val v = metric.of(last)
                                val bw = last.bodyWeightKg
                                if (usePercent && bw != null && bw > 0) {
                                    ValueWithUnit("${(v / bw * 100).roundToInt()}", "%BW", 44.sp, c.text, c.text2)
                                } else {
                                    ValueWithUnit(formatNumber(v, metric.unit), if (usePercent) "kg (no bodyweight)" else metric.unit, 44.sp, c.text, c.text2)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(longDate(last.takenAt), style = textStyle(16.sp, FontWeight.Normal, c.text3))
                            } else {
                                Text("—", style = textStyle(44.sp, FontWeight.SemiBold, c.text3))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(30.dp))
                CapsHeader("History", Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
            }
            val sessions = inRange.groupBy { it.sessionId }.values.sortedByDescending { s -> s.maxOf { it.takenAt } }
            val byDay = sessions.groupBy { longDate(it.first().takenAt) }
            byDay.forEach { (day, daySessions) ->
                item(key = "day-$day") {
                    Text(day, style = textStyle(24.sp, FontWeight.Bold, c.text), modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
                }
                items(daySessions, key = { it.first().sessionId }) { session ->
                    Row(Modifier.fillMaxWidth().clickable { nav.push(MeasureRoute.Result(session.first().sessionId)) }
                        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.sortedBy { it.side.ordinal }.joinToString("  ·  ") { m ->
                                "${m.side.label} ${formatNumber(metric.of(m), metric.unit)} ${metric.unit}"
                            }, style = textStyle(18.sp, FontWeight.Medium, c.text))
                            Spacer(Modifier.height(4.dp))
                            Text(timeFormat.format(Date(session.first().takenAt)), style = textStyle(15.sp, FontWeight.Normal, c.text3))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = c.divider)
                }
            }
            if (inRange.isEmpty()) item { EmptyNote("No results in this range.") }
            item { Spacer(Modifier.height(120.dp)) }
        }
        PrimaryButton("Measure", onClick = {
            nav.handSheet = StartContext(exerciseId = route.exerciseId, type = key.type, seconds = key.seconds.takeIf { it > 0 })
        }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp))
    }
}

@Composable
private fun Legend(label: String, color: androidx.compose.ui.graphics.Color) {
    val c = measureColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = textStyle(16.sp, FontWeight.Medium, c.text2), textAlign = TextAlign.Start)
    }
}
