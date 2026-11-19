// SPDX-License-Identifier: MPL-2.0
// Grippy: "How do you want to start?" and "Select hand to measure". Not part of upstream.

package app.grippy.measure

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasureSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = measureColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(Modifier.padding(top = 14.dp, bottom = 10.dp).size(width = 52.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp)).background(c.text3.copy(alpha = .6f)))
        },
    ) { content() }
}

/** Live Data, or one of the four measurement types. */
@Composable
fun StartSheet(nav: MeasureNavigator, context: StartContext, onLiveData: () -> Unit) {
    val c = measureColors()
    var type by remember { mutableStateOf(context.type ?: MeasureType.Peak) }
    MeasureSheet(onDismiss = { nav.startSheet = null }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text("How do you want to start?", style = textStyle(27.sp, FontWeight.Bold, c.text, (-0.3).sp))
                Spacer(Modifier.height(8.dp))
                Text("Open Live Data or choose a measurement type.", style = textStyle(17.sp, FontWeight.Normal, c.text2))
                Spacer(Modifier.height(28.dp))
                OutlinedCard(color = c.blueWash, borderColor = c.blue.copy(alpha = .55f), radius = 28.dp,
                    onClick = { nav.startSheet = null; onLiveData() }) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoxedIcon(Icons.AutoMirrored.Filled.ShowChart, Color.White, c.blue, 56.dp)
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Live Data", style = textStyle(21.sp, FontWeight.Bold, c.text))
                            Spacer(Modifier.height(6.dp))
                            Text("Check your data right away.", style = textStyle(16.sp, FontWeight.Normal, c.text2))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.link, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Measurement type", style = textStyle(19.sp, FontWeight.SemiBold, c.text))
                Spacer(Modifier.height(14.dp))
                OutlinedCard(radius = 28.dp, color = c.card) {
                    MeasureType.entries.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().clickable { type = t }.padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = type == t, onClick = { type = t },
                                colors = RadioButtonDefaults.colors(selectedColor = c.blue, unselectedColor = c.text3),
                                modifier = Modifier.scale(1.15f))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.menuLabel, style = textStyle(19.sp, FontWeight.SemiBold, c.text))
                                Spacer(Modifier.height(6.dp))
                                Text(t.blurb, style = textStyle(16.sp, FontWeight.Normal, c.text2))
                            }
                        }
                        if (i < MeasureType.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp), color = c.divider)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            HorizontalDivider(color = c.divider)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (context.exerciseId == null) {
                    Text("You can pick the exercise after measuring", style = textStyle(16.sp, FontWeight.Normal, c.text2),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                }
                PrimaryButton("Start ${type.label}", onClick = { nav.startSheet = context; nav.chooseType(type) })
            }
        }
    }
}

/** Which hands, how long, and how long until it starts. */
@Composable
fun HandSheet(nav: MeasureNavigator, context: StartContext) {
    val c = measureColors()
    val type = context.type ?: MeasureType.Peak
    var left by remember { mutableStateOf(true) }
    var right by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf((context.seconds?.takeIf { it > 0 } ?: type.defaultSeconds).toString()) }
    var startAfter by remember { mutableStateOf("7") }
    MeasureSheet(onDismiss = { nav.handSheet = null }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Select hand to measure", style = textStyle(27.sp, FontWeight.Bold, c.text, (-0.3).sp))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HandCard("Both", hands = 2, selected = left && right, showCheck = false, Modifier.weight(1f)) { left = true; right = true }
                HandCard("Left", hands = 1, selected = left, showCheck = true, Modifier.weight(1f)) {
                    if (left && !right) return@HandCard
                    left = !left
                }
                HandCard("Right", hands = 1, selected = right, showCheck = true, Modifier.weight(1f), mirror = true) {
                    if (right && !left) return@HandCard
                    right = !right
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, tint = c.text2, modifier = Modifier.size(22.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        left && right -> "Left first, then right."
                        left -> "Only the left side is measured.\nTap right to add it."
                        else -> "Only the right side is measured.\nTap left to add it."
                    },
                    style = textStyle(17.sp, FontWeight.Normal, c.text),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    if (type == MeasureType.CriticalForce) {
                        Text("Protocol", style = textStyle(19.sp, FontWeight.SemiBold, c.text))
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(c.raisedHigh).padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text("$CF_REPS × ${CF_WORK_SECONDS}s/${CF_REST_SECONDS}s", style = textStyle(19.sp, FontWeight.Normal, c.text))
                        }
                    } else {
                        Text("Duration", style = textStyle(19.sp, FontWeight.SemiBold, c.text))
                        Spacer(Modifier.height(12.dp))
                        SecondsField(seconds) { seconds = it }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Start after", style = textStyle(19.sp, FontWeight.SemiBold, c.text))
                    Spacer(Modifier.height(12.dp))
                    SecondsField(startAfter) { startAfter = it }
                }
            }
            Spacer(Modifier.height(26.dp))
            val duration = seconds.toIntOrNull()?.coerceIn(1, 600) ?: type.defaultSeconds
            PrimaryButton("Continue", onClick = {
                nav.begin(MeasureRequest(
                    type = type,
                    hands = listOfNotNull(Hand.Left.takeIf { left }, Hand.Right.takeIf { right }),
                    seconds = if (type == MeasureType.CriticalForce) CF_TOTAL_SECONDS else duration,
                    startAfter = startAfter.toIntOrNull()?.coerceIn(0, 60) ?: 7,
                    exerciseId = context.exerciseId,
                    folderId = context.folderId,
                ))
            })
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
    val shape = RoundedCornerShape(26.dp)
    val ink = if (selected) c.link else c.text
    Box(
        modifier.height(150.dp).clip(shape)
            .background(if (selected) c.blueWash else c.raisedHigh)
            .border(if (selected) 2.dp else 0.dp, if (selected) c.link else Color.Transparent, shape)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                if (hands == 2) Icon(Icons.Outlined.BackHand, null, tint = ink, modifier = Modifier.size(46.dp).scale(-1f, 1f))
                Icon(Icons.Outlined.BackHand, null, tint = ink, modifier = Modifier.size(46.dp).scale(if (mirror) -1f else 1f, 1f))
            }
            Spacer(Modifier.height(14.dp))
            Text(label, style = textStyle(20.sp, FontWeight.SemiBold, ink))
        }
        if (showCheck) {
            Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(24.dp).clip(RoundedCornerShape(5.dp))
                .border(2.dp, if (selected) c.link else c.text3, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, null, tint = c.link, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SecondsField(value: String, onChange: (String) -> Unit) {
    val c = measureColors()
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(c.raisedHigh).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value, { new -> onChange(new.filter(Char::isDigit).take(3)) },
            singleLine = true,
            textStyle = textStyle(21.sp, FontWeight.Normal, c.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(c.blue),
            modifier = Modifier.weight(1f),
        )
        Text("s", style = textStyle(21.sp, FontWeight.Normal, c.text2))
    }
}
