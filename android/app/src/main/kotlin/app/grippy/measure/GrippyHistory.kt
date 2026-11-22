// SPDX-License-Identifier: MPL-2.0
// Grippy: the History tab, Frez's way — a month calendar of everything measured, worked out
// and planned, a day sheet to open or add to, and Analytics. Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private val WorkoutGreen = Color(0xFF30D158)
private val dayTitle = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private const val CENTER_PAGE = 600

/** One thing on a day of the calendar. */
sealed interface DayEntry {
    val label: String
    val color: Color
    val sortKey: Long

    class Measured(val session: List<MeasurementEntity>, override val label: String) : DayEntry {
        val first: MeasurementEntity get() = session.first()
        override val color: Color get() = first.measureType.dot
        override val sortKey: Long get() = first.takenAt
    }

    class Workout(val log: WorkoutLogEntity, override val label: String) : DayEntry {
        override val color: Color get() = WorkoutGreen
        override val sortKey: Long get() = log.startedAt.toEpochMilli()
    }

    class Planned(val plan: PlanEntity, override val label: String) : DayEntry {
        override val color: Color get() = plan.measureType.dot
        override val sortKey: Long get() = Long.MAX_VALUE - 1_000_000 + plan.id
    }
}

private fun LocalDate.epoch(): Long = toEpochDay()
private fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** Everything by day: measurements (one entry per session), workouts and plans. */
internal fun dayEntries(
    data: MeasureSnapshot,
    workouts: List<WorkoutLogEntity>,
    routineNames: Map<UUID, String>,
): Map<Long, List<DayEntry>> {
    val out = HashMap<Long, MutableList<DayEntry>>()
    data.measurements.groupBy { it.sessionId }.values.forEach { session ->
        val sorted = session.sortedBy { it.side.ordinal }
        val name = data.exercise(sorted.first().exerciseId)?.name ?: GroupKey.of(sorted.first()).title
        out.getOrPut(sorted.first().takenAt.toDate().epoch()) { mutableListOf() } += DayEntry.Measured(sorted, name)
    }
    workouts.forEach { log ->
        val name = log.templateID?.let { routineNames[it] } ?: log.templateName
        out.getOrPut(log.historyDate().epoch()) { mutableListOf() } += DayEntry.Workout(log, name.ifBlank { "Workout" })
    }
    data.plans.forEach { plan ->
        val name = plan.exerciseId?.let { data.exercise(it)?.name } ?: plan.measureType.label
        out.getOrPut(plan.epochDay) { mutableListOf() } += DayEntry.Planned(plan, name)
    }
    return out.mapValues { (_, v) -> v.sortedBy { it.sortKey } }
}

enum class HistoryPage { Calendar, Analytics }

@Composable
fun GrippyHistoryScreen(nav: MeasureNavigator, onWorkoutLog: () -> Unit) {
    val c = measureColors()
    val data = rememberMeasureStore().collect()
    val feed = LocalHistoryFeed.current
    val templates = LocalTemplateStore.current
    LaunchedEffect(feed, templates.writeRevision) { feed.refreshIfStale() }
    val workouts = feed.logs
    val routineNames = templates.routineNames
    val entries = remember(data, workouts, routineNames) { dayEntries(data, workouts, routineNames) }
    var page by rememberSaveable { mutableStateOf(HistoryPage.Calendar) }
    var openDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var scheduleDay by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            HistoryPage.entries.forEach { p ->
                val ink by animateColorAsState(if (p == page) c.link else c.text3, tween(250), label = "tab")
                Text(p.name, style = textStyle(27.sp, FontWeight.Bold, ink, (-0.5).sp),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { page = p })
            }
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(250))) togetherWith
                    (slideOutHorizontally(tween(320)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            label = "page",
            modifier = Modifier.weight(1f),
        ) { p ->
            when (p) {
                HistoryPage.Calendar -> CalendarPage(entries, openDay, onDay = { openDay = it }, onAdd = { nav.openStart() })
                HistoryPage.Analytics -> AnalyticsPage(data, workouts, nav, onWorkoutLog)
            }
        }
    }

    openDay?.let { epochDay ->
        DaySheet(
            day = LocalDate.ofEpochDay(epochDay),
            entries = entries[epochDay].orEmpty(),
            data = data,
            onDismiss = { openDay = null },
            onOpen = { entry ->
                openDay = null
                when (entry) {
                    is DayEntry.Measured -> nav.push(MeasureRoute.Result(entry.first.sessionId))
                    is DayEntry.Workout -> onWorkoutLog()
                    is DayEntry.Planned -> nav.openStart(StartContext(exerciseId = entry.plan.exerciseId, type = entry.plan.measureType))
                }
            },
            onRecord = { openDay = null; nav.openStart() },
            onSchedule = { openDay = null; scheduleDay = epochDay },
        )
    }
    scheduleDay?.let { epochDay ->
        ScheduleSheet(LocalDate.ofEpochDay(epochDay), data, onDismiss = { scheduleDay = null })
    }
}

// ---- Calendar ---------------------------------------------------------------------------

@Composable
private fun CalendarPage(entries: Map<Long, List<DayEntry>>, selected: Long?, onDay: (Long) -> Unit, onAdd: () -> Unit) {
    val c = measureColors()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = CENTER_PAGE) { CENTER_PAGE * 2 }
    val thisMonth = remember { YearMonth.now() }
    val shown = thisMonth.plusMonths((pager.currentPage - CENTER_PAGE).toLong())
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInVertically { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutVertically { if (forward) -it else it } + fadeOut())
                },
                label = "month",
                modifier = Modifier.weight(1f),
            ) { month ->
                Text("${month.year}.${month.monthValue}", style = textStyle(28.sp, FontWeight.Bold, c.text, 0.5.sp),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(CENTER_PAGE) } })
            }
            if (shown != thisMonth) {
                Text("Today", style = textStyle(14.sp, FontWeight.SemiBold, c.link),
                    modifier = Modifier.clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(CENTER_PAGE) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.raised).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, "Measure", tint = c.text, modifier = Modifier.size(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { i, d ->
                Text(d, style = textStyle(14.sp, FontWeight.Medium, when (i) { 0 -> c.right; 6 -> c.link; else -> c.text2 }),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = c.divider)
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { index ->
            MonthGrid(thisMonth.plusMonths((index - CENTER_PAGE).toLong()), entries, selected, onDay)
        }
        Spacer(Modifier.height(LocalFloatingTabBarInset.current))
    }
}

@Composable
private fun MonthGrid(month: YearMonth, entries: Map<Long, List<DayEntry>>, selected: Long?, onDay: (Long) -> Unit) {
    val c = measureColors()
    val today = remember { LocalDate.now() }
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value % 7 // Sunday first
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    Column(Modifier.fillMaxSize()) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = r * 7 + col - offset + 1
                    val inMonth = dayNumber in 1..month.lengthOfMonth()
                    val date = if (inMonth) month.atDay(dayNumber) else null
                    DayCell(date, col, date?.let { entries[it.epoch()] }.orEmpty(), date == today,
                        date != null && date.epoch() == selected, Modifier.weight(1f).fillMaxHeight()) { date?.let { onDay(it.epoch()) } }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?, column: Int, entries: List<DayEntry>, isToday: Boolean, isSelected: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    val c = measureColors()
    val wash by animateColorAsState(
        when {
            isSelected -> c.blue.copy(alpha = .28f)
            isToday -> c.blueWash
            else -> Color.Transparent
        }, tween(220), label = "wash",
    )
    val line = c.divider
    Column(
        modifier
            .drawBehind {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                if (column < 6) drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
            }
            .background(wash)
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 3.dp, vertical = 4.dp),
    ) {
        if (date == null) return@Column
        val ink = when (column) { 0 -> c.right; 6 -> c.link; else -> c.text }
        if (isToday) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                Text("${date.dayOfMonth}", style = textStyle(13.sp, FontWeight.Bold, Color.White))
            }
        } else {
            Text("${date.dayOfMonth}", style = textStyle(14.sp, FontWeight.Medium, ink), modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
        Spacer(Modifier.height(4.dp))
        val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                entries.take(2).forEach { DayChip(it) }
                if (entries.size > 2) {
                    Text("+${entries.size - 2}", style = textStyle(11.sp, FontWeight.SemiBold, c.link), modifier = Modifier.padding(start = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun DayChip(entry: DayEntry) {
    val shape = RoundedCornerShape(5.dp)
    val planned = entry is DayEntry.Planned
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (planned) Modifier.border(1.dp, entry.color.copy(alpha = .8f), shape) else Modifier.background(entry.color.copy(alpha = .2f)))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entryIcon(entry), null, tint = entry.color, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(2.dp))
        Text(entry.label, style = textStyle(10.sp, FontWeight.Medium, entry.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun entryIcon(entry: DayEntry): ImageVector = when (entry) {
    is DayEntry.Measured -> Icons.Outlined.Speed
    is DayEntry.Workout -> Icons.Outlined.FitnessCenter
    is DayEntry.Planned -> Icons.Outlined.EventNote
}

// ---- Day sheet --------------------------------------------------------------------------

@Composable
private fun DaySheet(
    day: LocalDate,
    entries: List<DayEntry>,
    data: MeasureSnapshot,
    onDismiss: () -> Unit,
    onOpen: (DayEntry) -> Unit,
    onRecord: () -> Unit,
    onSchedule: () -> Unit,
) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val records = entries.filter { it !is DayEntry.Planned }
    val plans = entries.filterIsInstance<DayEntry.Planned>()
    MeasureSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(day.format(dayTitle), style = textStyle(21.sp, FontWeight.Bold, c.text, (-0.2).sp))
                Spacer(Modifier.height(16.dp))
                if (entries.isEmpty()) {
                    OutlinedCard(radius = 22.dp, color = c.card) {
                        EmptyNote(if (day.isAfter(LocalDate.now())) "Nothing planned yet. Schedule a measurement for this day."
                        else "Nothing recorded on this day.")
                    }
                }
                if (records.isNotEmpty()) {
                    Text("Records", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                    Spacer(Modifier.height(10.dp))
                    EntryList(records, onOpen) { null }
                    Spacer(Modifier.height(18.dp))
                }
                if (plans.isNotEmpty()) {
                    Text("Scheduled", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                    Spacer(Modifier.height(10.dp))
                    EntryList(plans, onOpen) { entry ->
                        { IconButton(onClick = { store.deletePlan((entry as DayEntry.Planned).plan.id) }) {
                            Icon(Icons.Outlined.Delete, "Remove", tint = c.text2, modifier = Modifier.size(20.dp))
                        } }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("+ Record", onClick = onRecord, modifier = Modifier.weight(1f), background = c.raisedHigh)
                SecondaryButton("+ Schedule", onClick = onSchedule, modifier = Modifier.weight(1f), background = c.raisedHigh)
            }
        }
    }
}

@Composable
private fun EntryList(entries: List<DayEntry>, onOpen: (DayEntry) -> Unit, trailing: (DayEntry) -> (@Composable () -> Unit)?) {
    val c = measureColors()
    OutlinedCard(radius = 22.dp, color = c.card) {
        entries.forEachIndexed { i, entry ->
            val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
            AnimatedVisibility(visibleState = visible,
                enter = fadeIn(tween(260, delayMillis = 50 * i)) + slideInVertically(tween(300, delayMillis = 50 * i)) { it / 3 }) {
                Column {
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = c.divider)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val planned = entry is DayEntry.Planned
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp))
                            .then(if (planned) Modifier.border(1.dp, entry.color, RoundedCornerShape(11.dp)) else Modifier.background(entry.color.copy(alpha = .18f))),
                            contentAlignment = Alignment.Center) {
                            Icon(entryIcon(entry), null, tint = entry.color, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, style = textStyle(16.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(entrySubtitle(entry), style = textStyle(14.sp, FontWeight.Normal, c.text2), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val extra = trailing(entry)
                        if (extra != null) extra() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

private fun entrySubtitle(entry: DayEntry): String = when (entry) {
    is DayEntry.Measured -> {
        val key = GroupKey.of(entry.first)
        val metric = key.type.headline
        val values = entry.session.joinToString(" · ") { "${it.side.label}: ${formatNumber(metric.of(it), metric.unit)} ${metric.unit}" }
        "${key.title} · $values · ${clock.format(Instant.ofEpochMilli(entry.first.takenAt).atZone(ZoneId.systemDefault()))}"
    }
    is DayEntry.Workout -> buildString {
        append("Workout")
        if (entry.log.plannedReps > 0) append(" · ${entry.log.completedReps}/${entry.log.plannedReps} reps")
        entry.log.sessionMinutes?.let { append(" · $it min") }
        append(" · ${clock.format(entry.log.startedAt.atZone(ZoneId.systemDefault()))}")
    }
    is DayEntry.Planned -> buildString {
        append("Planned · ${entry.plan.measureType.label}")
        if (entry.plan.note.isNotBlank()) append(" · ${entry.plan.note}")
        append(" · tap to start")
    }
}

// ---- Schedule ---------------------------------------------------------------------------

@Composable
private fun ScheduleSheet(day: LocalDate, data: MeasureSnapshot, onDismiss: () -> Unit) {
    val c = measureColors()
    val store = rememberMeasureStore()
    var type by remember { mutableStateOf(MeasureType.Peak) }
    var exerciseId by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    MeasureSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text("Schedule", style = textStyle(21.sp, FontWeight.Bold, c.text, (-0.2).sp))
                Spacer(Modifier.height(2.dp))
                Text(day.format(dayTitle), style = textStyle(14.sp, FontWeight.Normal, c.text2))
                Spacer(Modifier.height(18.dp))
                Text("Measurement type", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(10.dp))
                OutlinedCard(radius = 22.dp, color = c.card) {
                    MeasureType.entries.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = c.divider)
                        Row(Modifier.fillMaxWidth().clickable { type = t }.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(type == t, onClick = { type = t }, colors = RadioButtonDefaults.colors(selectedColor = c.blue, unselectedColor = c.text3))
                            Spacer(Modifier.width(4.dp))
                            Dot(t.dot, 10.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(t.menuLabel, style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Exercise (optional)", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(10.dp))
                OutlinedCard(radius = 22.dp, color = c.card) {
                    val options = listOf<ExerciseEntity?>(null) + data.exercises
                    options.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = c.divider)
                        val on = e?.id == exerciseId
                        Row(Modifier.fillMaxWidth().background(if (on) c.blueWash else Color.Transparent).clickable { exerciseId = e?.id }
                            .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e?.name ?: "Any exercise", style = textStyle(16.sp, FontWeight.Medium, if (e == null) c.text2 else c.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, "Selected", tint = c.link, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Note", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedHigh).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (note.isEmpty()) Text("e.g. after warm-up", style = textStyle(16.sp, FontWeight.Normal, c.text3))
                    BasicTextField(note, { note = it.take(80) }, singleLine = true, textStyle = textStyle(16.sp, FontWeight.Normal, c.text),
                        cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                PrimaryButton("Schedule for ${day.format(dayTitle)}", onClick = {
                    store.addPlan(PlanEntity(epochDay = day.toEpochDay(), type = type.key, exerciseId = exerciseId, note = note.trim()))
                    onDismiss()
                })
            }
        }
    }
}

// ---- Analytics --------------------------------------------------------------------------

private enum class Span(val label: String, val days: Long?) { M1("1M", 30), M3("3M", 91), Y1("1Y", 365), All("All", null) }

@Composable
private fun AnalyticsPage(data: MeasureSnapshot, workouts: List<WorkoutLogEntity>, nav: MeasureNavigator, onWorkoutLog: () -> Unit) {
    val c = measureColors()
    var span by rememberSaveable { mutableStateOf(Span.M3) }
    val today = remember { LocalDate.now() }
    val earliest = listOfNotNull(
        data.measurements.minOfOrNull { it.takenAt }?.toDate(),
        workouts.minOfOrNull { it.historyDate() },
    ).minOrNull() ?: today
    val from = span.days?.let { today.minusDays(it - 1) } ?: earliest
    val sessions = data.measurements.filter { !it.takenAt.toDate().isBefore(from) }.groupBy { it.sessionId }.values.map { it.sortedBy { m -> m.side.ordinal } }
    val doneWorkouts = workouts.filter { !it.historyDate().isBefore(from) }
    val activeDays = (sessions.map { it.first().takenAt.toDate() } + doneWorkouts.map { it.historyDate() }).toSet()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Segmented(Span.entries, span, { it.label }, { span = it }, Modifier.fillMaxWidth(), height = 40.dp, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        val tiles = listOf(
            "Measurements" to sessions.size.toDouble(),
            "Workouts" to doneWorkouts.size.toDouble(),
            "Active days" to activeDays.size.toDouble(),
            "Best streak" to bestStreak(activeDays).toDouble(),
        )
        tiles.chunked(2).forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { i, (label, value) ->
                    val index = r * 2 + i
                    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
                    AnimatedVisibility(visibleState = visible, modifier = Modifier.weight(1f),
                        enter = fadeIn(tween(300, delayMillis = 60 * index)) + slideInVertically(tween(360, delayMillis = 60 * index)) { it / 3 }) {
                        OutlinedCard(radius = 18.dp) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text(label, style = textStyle(13.sp, FontWeight.Medium, c.text2))
                                Spacer(Modifier.height(4.dp))
                                CountUp(value, if (label == "Best streak") "days" else "", 26.sp, c.text, c.text2, decimals = 0, delayMillis = 60 * index)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(4.dp))
        ActivityCard(sessions, doneWorkouts, from, today, span)
        Spacer(Modifier.height(12.dp))
        TypesCard(sessions)
        Spacer(Modifier.height(18.dp))
        val byExercise = sessions.flatten().groupBy { it.exerciseId }.entries.sortedByDescending { (_, ms) -> ms.maxOf { it.takenAt } }
        if (byExercise.isNotEmpty()) {
            Text("Exercises", style = textStyle(19.sp, FontWeight.Bold, c.text))
            Spacer(Modifier.height(10.dp))
            byExercise.forEach { (id, ms) ->
                ExerciseTrend(data.exercise(id)?.name ?: "—", ms) { nav.push(MeasureRoute.Exercise(id, 0)) }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedCard(radius = 22.dp, onClick = onWorkoutLog) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(WorkoutGreen.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.FitnessCenter, null, tint = WorkoutGreen, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Workout log", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                    Spacer(Modifier.height(2.dp))
                    Text("Every workout, its reps and load trends.", style = textStyle(14.sp, FontWeight.Normal, c.text2))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(24.dp + LocalFloatingTabBarInset.current))
    }
}

/** Longest run of consecutive days with something done. */
internal fun bestStreak(days: Set<LocalDate>): Int {
    var best = 0
    days.forEach { d ->
        if (d.minusDays(1) in days) return@forEach
        var n = 0
        var cur = d
        while (cur in days) { n++; cur = cur.plusDays(1) }
        best = max(best, n)
    }
    return best
}

/** Bars per week (per month for long spans): measurements blue, workouts green. Tap a bar. */
@Composable
private fun ActivityCard(sessions: List<List<MeasurementEntity>>, workouts: List<WorkoutLogEntity>, from: LocalDate, today: LocalDate, span: Span) {
    val c = measureColors()
    val monthly = span == Span.All && from.isBefore(today.minusDays(200)) || span == Span.Y1
    val buckets: List<LocalDate> = remember(from, today, monthly) {
        val out = ArrayList<LocalDate>()
        var d = if (monthly) from.withDayOfMonth(1) else from.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        while (!d.isAfter(today)) { out += d; d = if (monthly) d.plusMonths(1) else d.plusWeeks(1) }
        out
    }
    fun bucketOf(d: LocalDate): Int {
        val key = if (monthly) d.withDayOfMonth(1) else d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        return buckets.indexOf(key)
    }
    val measured = IntArray(buckets.size)
    val worked = IntArray(buckets.size)
    sessions.forEach { s -> bucketOf(s.first().takenAt.toDate()).takeIf { it >= 0 }?.let { measured[it]++ } }
    workouts.forEach { w -> bucketOf(w.historyDate()).takeIf { it >= 0 }?.let { worked[it]++ } }
    val top = max(1, (buckets.indices).maxOfOrNull { measured[it] + worked[it] } ?: 1)
    var picked by remember(span) { mutableStateOf<Int?>(null) }
    val grow = remember(span) { Animatable(0f) }
    LaunchedEffect(span) { grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val fmt = DateTimeFormatter.ofPattern(if (monthly) "MMM yyyy" else "MMM d", Locale.US)

    OutlinedCard(radius = 22.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = textStyle(16.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                Dot(c.blue, 8.dp); Spacer(Modifier.width(4.dp))
                Text("Measured", style = textStyle(12.sp, FontWeight.Medium, c.text2)); Spacer(Modifier.width(10.dp))
                Dot(WorkoutGreen, 8.dp); Spacer(Modifier.width(4.dp))
                Text("Workouts", style = textStyle(12.sp, FontWeight.Medium, c.text2))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                picked?.takeIf { it in buckets.indices }?.let { i ->
                    "${if (monthly) "" else "Week of "}${buckets[i].format(fmt)}: ${measured[i]} measured, ${worked[i]} workout${if (worked[i] == 1) "" else "s"}"
                } ?: "Tap a bar for its numbers.",
                style = textStyle(13.sp, FontWeight.Normal, if (picked != null) c.text else c.text3),
            )
            Spacer(Modifier.height(10.dp))
            val blue = c.blue
            val faint = c.text3
            Canvas(
                Modifier.fillMaxWidth().height(130.dp).pointerInput(buckets.size) {
                    detectTapGestures { pos ->
                        val i = (pos.x / (size.width / buckets.size.coerceAtLeast(1))).toInt().coerceIn(0, buckets.lastIndex)
                        picked = if (picked == i) null else i
                    }
                },
            ) {
                if (buckets.isEmpty()) return@Canvas
                val slot = size.width / buckets.size
                val bw = (slot * .62f).coerceAtMost(28.dp.toPx())
                buckets.indices.forEach { i ->
                    val x = i * slot + (slot - bw) / 2
                    val mh = measured[i].toFloat() / top * size.height * grow.value
                    val wh = worked[i].toFloat() / top * size.height * grow.value
                    val dim = picked != null && picked != i
                    if (measured[i] + worked[i] == 0) {
                        drawRoundRect(faint.copy(alpha = .25f), Offset(x, size.height - 3.dp.toPx()), Size(bw, 3.dp.toPx()), CornerRadius(2f))
                    }
                    if (wh > 0) drawRoundRect(WorkoutGreen.copy(alpha = if (dim) .35f else 1f), Offset(x, size.height - wh), Size(bw, wh), CornerRadius(4.dp.toPx()))
                    if (mh > 0) drawRoundRect(blue.copy(alpha = if (dim) .35f else 1f), Offset(x, size.height - wh - mh), Size(bw, mh), CornerRadius(4.dp.toPx()))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(buckets.firstOrNull()?.format(fmt) ?: "", style = textStyle(11.sp, FontWeight.Normal, c.text3), modifier = Modifier.weight(1f))
                Text(buckets.lastOrNull()?.format(fmt) ?: "", style = textStyle(11.sp, FontWeight.Normal, c.text3))
            }
        }
    }
}

/** How the measurements split across the four types, bars growing in. */
@Composable
private fun TypesCard(sessions: List<List<MeasurementEntity>>) {
    val c = measureColors()
    val counts = MeasureType.entries.associateWith { t -> sessions.count { it.first().measureType == t } }
    val top = max(1, counts.values.maxOrNull() ?: 1)
    OutlinedCard(radius = 22.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("By type", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
            Spacer(Modifier.height(10.dp))
            counts.forEach { (type, n) ->
                val grow = remember { Animatable(0f) }
                LaunchedEffect(n, top) { grow.animateTo(n.toFloat() / top, tween(800, easing = FastOutSlowInEasing)) }
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = textStyle(14.sp, FontWeight.Medium, c.text2), modifier = Modifier.width(104.dp))
                    Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(c.raised)) {
                        if (grow.value > 0f) Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(type.dot))
                    }
                    Text("$n", style = textStyle(14.sp, FontWeight.SemiBold, c.text), textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
                }
            }
        }
    }
}

/** An exercise's most-measured kind in the span: the latest number, the change, a sparkline. */
@Composable
private fun ExerciseTrend(name: String, measurements: List<MeasurementEntity>, onClick: () -> Unit) {
    val c = measureColors()
    val key = measurements.groupBy { GroupKey.of(it) }.maxBy { it.value.size }.key
    val ofKey = measurements.filter { GroupKey.of(it) == key }.sortedBy { it.takenAt }
    val metric = key.type.headline
    val left = dailyPoints(ofKey.filter { it.side == Hand.Left }, metric, false)
    val right = dailyPoints(ofKey.filter { it.side == Hand.Right }, metric, false)
    val first = metric.of(ofKey.first())
    val latest = metric.of(ofKey.last())
    val change = if (first > 0 && ofKey.size > 1) (latest - first) / first * 100 else null
    OutlinedCard(radius = 22.dp, onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = textStyle(16.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(key.type.dot, 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${key.title} · ${ofKey.size}×", style = textStyle(13.sp, FontWeight.Normal, c.text2))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(latest, metric.unit, 22.sp, c.text, c.text2)
                    if (change != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("${if (change >= 0) "▲" else "▼"} ${"%.0f".format(Locale.US, kotlin.math.abs(change))} %",
                            style = textStyle(13.sp, FontWeight.SemiBold, if (change >= 0) WorkoutGreen else c.right),
                            modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
            Sparkline(left, right, c.left, c.right, Modifier.width(110.dp).height(48.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
        }
    }
}
