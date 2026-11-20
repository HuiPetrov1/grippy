// SPDX-License-Identifier: MPL-2.0
// Grippy: the Measure tab — search, Live Data / Measure, and the folders of exercises.
// Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset

@Composable
fun MeasureTabScreen(nav: MeasureNavigator, onLiveData: () -> Unit) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    var query by rememberSaveable { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }

    val q = query.trim().lowercase(Locale.getDefault())
    val shown = data.exercises.filter { q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) }
    // My Measures (id 0 here) first, then the person's folders in the order they made them.
    val sections = listOf<Pair<Long, String>>(0L to "My Measures") + data.folders.map { it.id to it.name }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageTitle("Measure") {
                IconButton(onClick = { newFolder = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder", tint = c.link, modifier = Modifier.size(25.dp))
                }
            }
        }
        item { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                BigPill("Live Data", Icons.AutoMirrored.Filled.ShowChart, filled = false, onClick = onLiveData, modifier = Modifier.weight(1f))
                BigPill("Measure", Icons.Outlined.Speed, filled = true, onClick = { nav.openStart() }, modifier = Modifier.weight(1f))
            }
        }
        sections.forEach { (folderId, name) ->
            val inFolder = shown.filter { (it.folderId ?: 0L) == folderId }
            // A search hides folders with nothing matching; without one every folder shows.
            if (q.isNotEmpty() && inFolder.isEmpty()) return@forEach
            val isCollapsed = collapsed[folderId] == true
            item(key = "folder-$folderId") {
                FolderHeader(
                    name = name,
                    count = inFolder.size,
                    collapsed = isCollapsed,
                    onToggle = { collapsed[folderId] = !isCollapsed },
                    onRename = data.folders.firstOrNull { it.id == folderId }?.let { f -> { n: String -> store.renameFolder(f, n) } },
                    onDelete = if (folderId != 0L) ({ store.deleteFolder(folderId) }) else null,
                )
            }
            if (!isCollapsed) {
                items(inFolder, key = { "ex-${it.id}" }) { exercise ->
                    val ofIt = data.of(exercise.id)
                    ExerciseRow(exercise, ofIt, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                        nav.push(MeasureRoute.Exercise(exercise.id, 1))
                    }
                }
                item(key = "add-$folderId") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Row(Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable { nav.openStart(StartContext(folderId = folderId.takeIf { it != 0L })) }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, null, tint = c.link, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Measure", style = textStyle(15.5.sp, FontWeight.SemiBold, c.link))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp + LocalFloatingTabBarInset.current)) }
    }

    if (newFolder) {
        NameDialog(title = "New folder", initial = "", confirm = "Create",
            onDone = { name -> if (name.isNotBlank()) store.addFolder(name); newFolder = false },
            onDismiss = { newFolder = false })
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = measureColors()
    Row(modifier.fillMaxWidth().height(51.dp).clip(RoundedCornerShape(16.dp)).background(c.raised).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = c.text, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(13.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search exercises", style = textStyle(15.5.sp, FontWeight.Normal, c.text3))
            BasicTextField(value, onChange, singleLine = true, textStyle = textStyle(15.5.sp, FontWeight.Normal, c.text),
                cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FolderHeader(
    name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit,
    onRename: ((String) -> Unit)?, onDelete: (() -> Unit)?,
) {
    val c = measureColors()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 10.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.FolderOpen, null, tint = c.link, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(11.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = textStyle(18.sp, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text("$count", style = textStyle(16.5.sp, FontWeight.Medium, c.text3))
        }
        if (onDelete != null || onRename != null) {
            Box {
                TextButton(onClick = { menu = true }) { Text("•••", style = textStyle(13.sp, FontWeight.Bold, c.text2)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onRename != null) DropdownMenuItem(text = { Text("Rename folder") }, onClick = { menu = false; renaming = true })
                    if (onDelete != null) DropdownMenuItem(text = { Text("Delete folder") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Icon(if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = if (collapsed) "Expand" else "Collapse",
            tint = c.text2, modifier = Modifier.size(26.dp))
    }
    if (renaming && onRename != null) {
        NameDialog("Rename folder", name, "Save", onDone = { if (it.isNotBlank()) onRename(it); renaming = false }, onDismiss = { renaming = false })
    }
}

@Composable
fun ExerciseRow(exercise: ExerciseEntity, measurements: List<MeasurementEntity>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = measureColors()
    OutlinedCard(modifier, onClick = onClick, radius = 23.dp) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(c.raised)
                .border(1.dp, c.border, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text(initials(exercise.name), style = textStyle(18.sp, FontWeight.Bold, c.link))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = textStyle(15.5.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                val last = measurements.maxOfOrNull { it.takenAt }
                val count = measurements.size
                Text(
                    "$count result${if (count == 1) "" else "s"}" + (last?.let { " · " + shortDate(it) } ?: ""),
                    style = textStyle(13.sp, FontWeight.Normal, c.text2),
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
