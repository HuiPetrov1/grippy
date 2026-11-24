// SPDX-License-Identifier: MPL-2.0
// Grippy: "How do you want to start?" and "Select hand to measure". Not part of upstream.

package app.grippy.measure

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasureSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = measureColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp)).background(c.text3.copy(alpha = .6f)))
        },
    ) { content() }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = textStyle(21.sp, FontWeight.Bold, measureColors().text, (-0.2).sp))
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = measureColors().text) {
    Text(text, style = textStyle(16.sp, FontWeight.SemiBold, color), modifier = modifier)
}

/** Live Data, or one of the four measurement types, and optionally the exercise it's for. */
@Composable
fun StartSheet(nav: MeasureNavigator, context: StartContext, onLiveData: () -> Unit) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(context.type ?: MeasureType.Peak) }
    var exerciseId by rememberSaveable { mutableStateOf(context.exerciseId) }
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val chosen = exerciseId?.let { data.exercise(it) }

    MeasureSheet(onDismiss = { nav.startSheet = null }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                SheetTitle("How do you want to start?")
                Spacer(Modifier.height(4.dp))
                Text("Open Live Data or choose a measurement type.", style = textStyle(14.sp, FontWeight.Normal, c.text2))
                Spacer(Modifier.height(20.dp))
                OutlinedCard(color = c.blueWash, borderColor = c.blue.copy(alpha = .55f), radius = 22.dp,
                    onClick = { nav.startSheet = null; onLiveData() }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoxedIcon(Icons.AutoMirrored.Filled.ShowChart, Color.White, c.blue, 42.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Live Data", style = textStyle(17.sp, FontWeight.Bold, c.text))
                            Spacer(Modifier.height(2.dp))
                            Text("Check your data right away.", style = textStyle(14.sp, FontWeight.Normal, c.text2))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.link, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Measurement type")
                Spacer(Modifier.height(10.dp))
                OutlinedCard(radius = 22.dp, color = c.card) {
                    MeasureType.entries.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().clickable { type = t }.padding(start = 6.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = type == t, onClick = { type = t },
                                colors = RadioButtonDefaults.colors(selectedColor = c.blue, unselectedColor = c.text3))
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.menuLabel, style = textStyle(16.sp, FontWeight.SemiBold, c.text))
                                Spacer(Modifier.height(3.dp))
                                Text(t.blurb, style = textStyle(14.sp, FontWeight.Normal, c.text2))
                            }
                        }
                        if (i < MeasureType.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 58.dp), color = c.divider)
                    }
                }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Exercise (optional)")
                Spacer(Modifier.height(10.dp))
                OutlinedCard(radius = 22.dp, color = c.card) {
                    ExerciseOption(
                        title = "Create custom exercise",
                        subtitle = "Any exercise you can pull on the scale",
                        titleColor = c.link,
                        icon = { IconTile(Icons.Filled.Add, c.link, c.blueWash) },
                        onClick = { creating = true },
                    )
                    // The exercise picked so far, else the ones measured most recently.
                    val recent = data.exercises
                        .sortedByDescending { e -> data.of(e.id).maxOfOrNull { it.takenAt } ?: e.createdAt }
                        .let { list -> listOfNotNull(chosen) + list.filter { it.id != chosen?.id } }
                        .take(3)
                    recent.forEach { e ->
                        HorizontalDivider(Modifier.padding(start = 70.dp), color = c.divider)
                        val ofIt = data.of(e.id)
                        val selected = e.id == exerciseId
                        ExerciseOption(
                            title = e.name,
                            subtitle = "Custom · ${ofIt.size} measurement${if (ofIt.size == 1) "" else "s"}",
                            trailing = ofIt.maxOfOrNull { it.takenAt }?.let { shortDate(it) },
                            selected = selected,
                            icon = { InitialsTile(e.name, selected) },
                            onClick = { exerciseId = if (selected) null else e.id },
                        )
                    }
                    if (data.exercises.size > recent.size) {
                        HorizontalDivider(Modifier.padding(start = 70.dp), color = c.divider)
                        ExerciseOption(
                            title = "Choose another exercise",
                            subtitle = "All your exercises",
                            icon = { IconTile(Icons.Outlined.Search, c.text, c.raisedHigh) },
                            chevron = true,
                            onClick = { choosing = true },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    chosen?.let { "Saves to ${it.name}" } ?: "You can pick the exercise after measuring",
                    style = textStyle(14.sp, FontWeight.Normal, c.text2), textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Start ${type.label}", onClick = {
                    nav.startSheet = context.copy(exerciseId = exerciseId)
                    nav.chooseType(type)
                })
            }
        }
    }

    if (creating) {
        NameDialog(title = "New exercise", initial = "", confirm = "Create",
            onDone = { name ->
                creating = false
                if (name.isNotBlank()) scope.launch { exerciseId = store.addExercise(name, context.folderId) }
            },
            onDismiss = { creating = false })
    }
    if (choosing) {
        ExercisePicker(data, selected = exerciseId, onPick = { exerciseId = it; choosing = false }, onDismiss = { choosing = false })
    }
}

@Composable
private fun ExerciseOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    titleColor: Color = measureColors().text,
    trailing: String? = null,
    selected: Boolean = false,
    chevron: Boolean = false,
) {
    val c = measureColors()
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.blueWash else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = textStyle(16.sp, FontWeight.SemiBold, titleColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = textStyle(14.sp, FontWeight.Normal, c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, "Selected", tint = c.link, modifier = Modifier.size(22.dp))
        } else if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = textStyle(14.sp, FontWeight.Normal, c.text2))
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color, background: Color) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun InitialsTile(name: String, selected: Boolean) {
    val c = measureColors()
    val shape = RoundedCornerShape(11.dp)
    Box(Modifier.size(42.dp).clip(shape).background(if (selected) c.blueWash else c.raised)
        .border(1.dp, if (selected) c.link else c.border, shape), contentAlignment = Alignment.Center) {
        Text(initials(name), style = textStyle(15.sp, FontWeight.Bold, c.link))
    }
}

/** Every exercise, searchable, for "Choose another exercise". */
@Composable
private fun ExercisePicker(data: MeasureSnapshot, selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val c = measureColors()
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase(Locale.getDefault())
    val shown = data.exercises.filter { q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) }.sortedBy { it.name.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.raised,
        title = { Text("Choose exercise", style = textStyle(19.sp, FontWeight.Bold, c.text)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search exercises") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { e ->
                        val on = e.id == selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(e.id) }
                            .padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            InitialsTile(e.name, on)
                            Spacer(Modifier.width(12.dp))
                            Text(e.name, style = textStyle(16.sp, FontWeight.Medium, c.text), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, "Selected", tint = c.link, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Which hands, and for a timed type how long; for Peak, passive or active. */
@Composable
fun HandSheet(nav: MeasureNavigator, context: StartContext) {
    val c = measureColors()
    val type = context.type ?: MeasureType.Peak
    var left by remember { mutableStateOf(true) }
    var right by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf((context.seconds?.takeIf { it > 0 } ?: type.defaultSeconds).toString()) }
    var startAfter by remember { mutableStateOf(when (type) { MeasureType.Peak -> "3"; MeasureType.Reps -> "5"; else -> "7" }) }
    var active by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    MeasureSheet(onDismiss = { nav.handSheet = null }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SheetTitle("Select hand to measure")
            Spacer(Modifier.height(18.dp))
            if (type == MeasureType.Peak) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Measurement method", color = c.text2)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Info, "What's the difference?", tint = c.text2,
                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { explain = !explain })
                }
                if (explain) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Passive: starts by itself the moment you pull. Active: counts you in first. " +
                            "Then pull as many times as you like; only your best pull counts. Press Stop when you're done.",
                        style = textStyle(13.5.sp, FontWeight.Normal, c.text2),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Segmented(listOf(false, true), active, { if (it) "Active" else "Passive" }, { active = it },
                    Modifier.fillMaxWidth(), height = 40.dp, fontSize = 15.sp)
                Spacer(Modifier.height(18.dp))
                SectionLabel("Hand", color = c.text2)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HandCard("Both", hands = 2, selected = left && right, showCheck = false, Modifier.weight(1f)) { left = true; right = true }
                // One choice at a time: Left is left only, Right is right only, Both is both.
                HandCard("Left", hands = 1, selected = left && !right, showCheck = true, Modifier.weight(1f)) { left = true; right = false }
                HandCard("Right", hands = 1, selected = right && !left, showCheck = true, Modifier.weight(1f), mirror = true) {
                    left = false; right = true
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, tint = c.text2, modifier = Modifier.size(17.dp).padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        left && right -> "Left first, then right."
                        left -> "Only the left side is measured.\nPick Both to measure both sides."
                        else -> "Only the right side is measured.\nPick Both to measure both sides."
                    },
                    style = textStyle(14.sp, FontWeight.Normal, c.text),
                )
            }
            val showStartAfter = type != MeasureType.Peak || active
            if (type != MeasureType.Peak || showStartAfter) Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (type) {
                    MeasureType.Peak, MeasureType.Reps -> Unit
                    MeasureType.CriticalForce -> Column(Modifier.weight(1f)) {
                        SectionLabel("Protocol")
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedHigh).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text("$CF_REPS × ${CF_WORK_SECONDS}s/${CF_REST_SECONDS}s", style = textStyle(16.sp, FontWeight.Normal, c.text))
                        }
                    }
                    else -> Column(Modifier.weight(1f)) {
                        SectionLabel("Duration")
                        Spacer(Modifier.height(8.dp))
                        SecondsField(seconds) { seconds = it }
                    }
                }
                if (showStartAfter) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Start after")
                        Spacer(Modifier.height(8.dp))
                        SecondsField(startAfter) { startAfter = it }
                    }
                }
                // Peak, active: keep the field half-width, like the others.
                if ((type == MeasureType.Peak || type == MeasureType.Reps) && showStartAfter) Spacer(Modifier.weight(1f))
            }
            if (type == MeasureType.Reps) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your camera phone wakes up and counts. \"Start after\" is the time you have to get in position. " +
                        "The count shows after your 3rd rep, the first three together; for quick small reps after about the 8th. Press Stop when the set is done.",
                    style = textStyle(13.5.sp, FontWeight.Normal, c.text2),
                )
            }
            Spacer(Modifier.height(20.dp))
            val duration = seconds.toIntOrNull()?.coerceIn(1, 600) ?: type.defaultSeconds
            val hands = listOfNotNull(Hand.Left.takeIf { left }, Hand.Right.takeIf { right })
            PrimaryButton(
                (if (type == MeasureType.Reps) "Count " else "Measure ") + when {
                    left && right -> "both"
                    left -> "left"
                    else -> "right"
                },
                onClick = {
                    nav.begin(MeasureRequest(
                        type = type,
                        hands = hands,
                        seconds = when (type) {
                            MeasureType.CriticalForce -> CF_TOTAL_SECONDS
                            MeasureType.Peak -> PEAK_MAX_SECONDS
                            MeasureType.Reps -> 0
                            else -> duration
                        },
                        // A passive Peak has no countdown: it's armed and starts with the pull.
                        startAfter = if (!showStartAfter) 0 else startAfter.toIntOrNull()?.coerceIn(if (type == MeasureType.Peak) 1 else 0, 60) ?: 3,
                        exerciseId = context.exerciseId,
                        folderId = context.folderId,
                    ))
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HandCard(
    label: String, hands: Int, selected: Boolean, showCheck: Boolean, modifier: Modifier = Modifier,
    mirror: Boolean = false, onClick: () -> Unit,
) {
    val c = measureColors()
    val shape = RoundedCornerShape(18.dp)
    val ink by animateColorAsState(if (selected) c.link else c.text, label = "ink")
    val fill by animateColorAsState(if (selected) c.blueWash else c.raisedHigh, label = "fill")
    val edge by animateColorAsState(if (selected) c.link else c.raisedHigh, label = "edge")
    val press by animateFloatAsState(if (selected) 1f else .97f, spring(dampingRatio = .5f, stiffness = 500f), label = "press")
    Box(
        modifier.height(88.dp).graphicsLayer { scaleX = press; scaleY = press }.clip(shape)
            .background(fill)
            .border(2.dp, edge, shape)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy((-2).dp)) {
                if (hands == 2) Icon(Icons.Outlined.BackHand, null, tint = ink, modifier = Modifier.size(28.dp).scale(-1f, 1f))
                Icon(Icons.Outlined.BackHand, null, tint = ink, modifier = Modifier.size(28.dp).scale(if (mirror) -1f else 1f, 1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = textStyle(15.sp, FontWeight.SemiBold, ink))
        }
        if (showCheck) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp).clip(RoundedCornerShape(3.dp))
                .border(1.5.dp, if (selected) c.link else c.text3, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, null, tint = c.link, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun SecondsField(value: String, onChange: (String) -> Unit) {
    val c = measureColors()
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedHigh).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value, { new -> onChange(new.filter(Char::isDigit).take(3)) },
            singleLine = true,
            textStyle = textStyle(17.sp, FontWeight.Normal, c.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(c.blue),
            modifier = Modifier.weight(1f),
        )
        Text("s", style = textStyle(17.sp, FontWeight.Normal, c.text2))
    }
}
