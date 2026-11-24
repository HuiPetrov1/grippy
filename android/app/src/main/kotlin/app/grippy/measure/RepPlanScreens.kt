// SPDX-License-Identifier: MPL-2.0
// Grippy: camera session plans — the list, and one plan's editor (exercises in order, weights, arms).
// Not part of upstream.

package app.grippy.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val KG_STEP = 0.5

/** The camera session plans: start one, edit one, or make a new one. */
@Composable
fun PlansScreen(nav: MeasureNavigator) {
    val c = measureColors()
    val context = LocalContext.current
    val plans = remember { RepPlans.get(context) }
    val list by plans.plans.collectAsState()
    val store = rememberMeasureStore()
    val data = store.collect()
    LaunchedEffect(Unit) { plans.seedIfEmpty(store) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("Camera sessions", onBack = { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Your exercises in order, with their weights. Start one and the phone takes you set by set; " +
                "you start each set when you're ready.", style = textStyle(14.sp, FontWeight.Normal, c.text2))
            Spacer(Modifier.height(16.dp))
            list.forEach { plan ->
                OutlinedCard(onClick = { nav.push(MeasureRoute.PlanEdit(plan.id)) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(plan.name, style = textStyle(19.sp, FontWeight.Bold, c.text))
                        Text("${plan.items.size} exercises · ${plan.setCount} sets", style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            plan.items.take(5).forEach { item ->
                                ExerciseArt(data.exercise(item.exerciseId)?.name ?: "?", 46.dp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Start", onClick = { nav.beginPlan(plan, plan.sets { data.exercise(it)?.name }) }, modifier = Modifier.weight(1f),
                                enabled = plan.setCount > 0)
                            SecondaryButton("Edit", onClick = { nav.push(MeasureRoute.PlanEdit(plan.id)) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            SecondaryButton("+ New plan", onClick = {
                val plan = SessionPlan(plans.newId(), "Session ${list.size + 1}", emptyList())
                plans.save(plan)
                nav.push(MeasureRoute.PlanEdit(plan.id))
            })
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One plan: its name, and the exercises in order — drag the handle to reorder, step the weight, pick the arms. */
@Composable
fun PlanEditScreen(nav: MeasureNavigator, id: Long) {
    val c = measureColors()
    val context = LocalContext.current
    val plans = remember { RepPlans.get(context) }
    val list by plans.plans.collectAsState()
    val plan = list.firstOrNull { it.id == id }
    val store = rememberMeasureStore()
    val data = store.collect()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (plan == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    fun update(items: List<PlanItem>) = plans.save(plan.copy(items = items))

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("Session plan", onBack = { nav.pop() }, actions = {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete plan", tint = c.text2) }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = plan.name, onValueChange = { plans.save(plan.copy(name = it)) }, singleLine = true,
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text("${plan.items.size} exercises · ${plan.setCount} sets · hold ⋮⋮ and drag to reorder",
                style = textStyle(13.sp, FontWeight.Normal, c.text3))
            Spacer(Modifier.height(12.dp))
            ReorderableItems(
                items = plan.items,
                onMove = { from, to ->
                    val items = plan.items.toMutableList()
                    items.add(to, items.removeAt(from))
                    update(items)
                },
            ) { i, item, handle ->
                PlanRow(
                    number = i + 1, item = item, name = data.exercise(item.exerciseId)?.name ?: "Exercise", handle = handle,
                    onWeight = { kg -> update(plan.items.toMutableList().also { it[i] = item.copy(weightKg = kg) }) },
                    onArms = { arms -> update(plan.items.toMutableList().also { it[i] = item.copy(arms = arms) }) },
                    onExercise = { picking = i },
                    onRemove = { update(plan.items.toMutableList().also { it.removeAt(i) }) },
                )
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton("+ Add exercise", onClick = { adding = true })
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            PrimaryButton("Start session", enabled = plan.setCount > 0,
                onClick = { nav.beginPlan(plan, plan.sets { data.exercise(it)?.name }) })
        }
    }

    if (adding || picking != null) {
        PlanExercisePicker(
            data = data,
            onPick = { exerciseId ->
                val at = picking
                if (at == null) update(plan.items + PlanItem(exerciseId, plan.items.lastOrNull()?.weightKg ?: 5.0))
                else update(plan.items.toMutableList().also { it[at] = it[at].copy(exerciseId = exerciseId) })
                adding = false; picking = null
            },
            onCreate = { name ->
                val at = picking
                adding = false; picking = null
                scope.launch {
                    val exerciseId = store.addExercise(name.trim(), null)
                    val now = plans.plan(id) ?: return@launch
                    plans.save(
                        if (at == null) now.copy(items = now.items + PlanItem(exerciseId, now.items.lastOrNull()?.weightKg ?: 5.0))
                        else now.copy(items = now.items.toMutableList().also { it[at] = it[at].copy(exerciseId = exerciseId) }),
                    )
                }
            },
            onDismiss = { adding = false; picking = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${plan.name}”?") },
            text = { Text("Only the plan goes; what you measured stays.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; plans.delete(plan.id); nav.pop() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/**
 * The rows, each draggable by its handle: long-press the handle and drag up or down; the row swaps with
 * its neighbour once it has moved more than half a row.
 */
@Composable
private fun ReorderableItems(
    items: List<PlanItem>,
    onMove: (from: Int, to: Int) -> Unit,
    row: @Composable (index: Int, item: PlanItem, handle: Modifier) -> Unit,
) {
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    Column {
        items.forEachIndexed { i, item ->
            val handle = Modifier.pointerInput(items.size, i) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = i; offset = 0f },
                    onDragEnd = { dragging = -1; offset = 0f },
                    onDragCancel = { dragging = -1; offset = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        offset += amount.y
                        val at = dragging
                        if (at < 0) return@detectDragGesturesAfterLongPress
                        if (offset > rowHeight / 2 && at < items.lastIndex) {
                            onMove(at, at + 1); dragging = at + 1; offset -= rowHeight
                        } else if (offset < -rowHeight / 2 && at > 0) {
                            onMove(at, at - 1); dragging = at - 1; offset += rowHeight
                        }
                    },
                )
            }
            val lifted = dragging == i
            Box(
                Modifier.fillMaxWidth().onSizeChanged { rowHeight = it.height.toFloat() }
                    .offset { IntOffset(0, if (lifted) offset.roundToInt() else 0) }
                    .graphicsLayer { if (lifted) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f } },
            ) {
                row(i, item, handle)
            }
        }
    }
}

@Composable
private fun PlanRow(
    number: Int, item: PlanItem, name: String, handle: Modifier,
    onWeight: (Double) -> Unit, onArms: (Arms) -> Unit, onExercise: () -> Unit, onRemove: () -> Unit,
) {
    val c = measureColors()
    var armsOpen by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(width = 34.dp, height = 64.dp).semantics { contentDescription = "Hold and drag to reorder" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DragIndicator, null, tint = c.text3)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onExercise)) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(c.raised), contentAlignment = Alignment.Center) {
                        Text("$number", style = textStyle(13.sp, FontWeight.Bold, c.text))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = textStyle(16.sp, FontWeight.SemiBold, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clip(CircleShape).background(c.raised), verticalAlignment = Alignment.CenterVertically) {
                        RoundStep("−", "Lighter", enabled = item.weightKg > 0) { onWeight((item.weightKg - KG_STEP).coerceAtLeast(0.0)) }
                        Text(kgText(item.weightKg), style = textStyle(15.sp, FontWeight.SemiBold, c.text),
                            modifier = Modifier.width(62.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        RoundStep("+", "Heavier", enabled = true) { onWeight(item.weightKg + KG_STEP) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Text(item.arms.short + " ▾", style = textStyle(14.sp, FontWeight.Medium, c.text),
                            modifier = Modifier.clip(CircleShape).background(c.raised).clickable { armsOpen = true }
                                .padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1)
                        DropdownMenu(expanded = armsOpen, onDismissRequest = { armsOpen = false }) {
                            Arms.entries.forEach { arms ->
                                DropdownMenuItem(text = { Text(arms.label) }, onClick = { armsOpen = false; onArms(arms) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                ExerciseArt(name, 52.dp)
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Remove $name", tint = c.text3, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundStep(sign: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = measureColors()
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = textStyle(20.sp, FontWeight.SemiBold, if (enabled) c.link else c.text3))
    }
}

/** Pick one of the Measure section's exercises, or name a new one. */
@Composable
private fun PlanExercisePicker(data: MeasureSnapshot, onPick: (Long) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val c = measureColors()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exercise") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("New exercise") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                data.exercises.filter { name.isBlank() || it.name.contains(name.trim(), ignoreCase = true) }.forEach { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(e.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ExerciseArt(e.name, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(e.name, style = textStyle(15.sp, FontWeight.Medium, c.text))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Icon(Icons.Filled.Add, null)
                Text("Add “${name.trim()}”".takeIf { name.isNotBlank() } ?: "Add new")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
