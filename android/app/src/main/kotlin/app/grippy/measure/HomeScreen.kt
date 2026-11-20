// SPDX-License-Identifier: MPL-2.0
// Grippy: the Home tab — the scale, quick starts, recent results, and the doors to Benchmarks
// and Settings. Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    nav: MeasureNavigator,
    onSettings: () -> Unit,
    onBenchmarks: () -> Unit,
    onLiveData: () -> Unit,
) {
    val c = measureColors()
    val device = LocalDeviceStore.current
    val data = rememberMeasureStore().collect()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Home") {
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = c.text, modifier = Modifier.size(23.dp)) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            // The scale. Tapping the card opens Settings, where the gauge is chosen.
            OutlinedCard(radius = 23.dp, onClick = onSettings) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoxedIcon(Icons.Outlined.Bluetooth, if (device.state.isConnected) c.blue else c.text2, c.raised, 43.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.deviceName ?: device.gaugeKind.displayName, style = textStyle(15.sp, FontWeight.SemiBold, c.text),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        val battery = device.batteryFraction?.let { " · ${(it * 100).roundToInt()} %" } ?: ""
                        Text(device.state.label + battery, style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
                    }
                    if (device.state.isConnected) {
                        SmallPill("Disconnect", onClick = { device.disconnect() })
                    } else {
                        SmallPill(if (device.state.isBusy) "Searching…" else "Connect", onClick = { if (!device.state.isBusy) device.connect() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                BigPill("Live Data", Icons.AutoMirrored.Filled.ShowChart, filled = false, onClick = onLiveData, modifier = Modifier.weight(1f))
                BigPill("Measure", Icons.Outlined.Speed, filled = true, onClick = { nav.openStart() }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Text("Recent", style = textStyle(18.sp, FontWeight.Bold, c.text))
            val recent = data.measurements.groupBy { it.sessionId }.values.sortedByDescending { s -> s.maxOf { it.takenAt } }.take(6)
            if (recent.isEmpty()) {
                OutlinedCard { EmptyNote("Your measurements show up here. Tap Measure to take one.") }
            }
            recent.forEach { session ->
                val first = session.first()
                val key = GroupKey.of(first)
                val metric = key.type.headline
                OutlinedCard(onClick = { nav.push(MeasureRoute.Result(first.sessionId)) }) {
                    Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(key.type.dot)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(data.exercise(first.exerciseId)?.name ?: "—", style = textStyle(15.sp, FontWeight.SemiBold, c.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("${key.title} · ${shortDate(first.takenAt)}", style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(session.sortedBy { it.side.ordinal }.joinToString("  ") { "${it.side.label.first()} ${formatNumber(metric.of(it), metric.unit)}" },
                            style = textStyle(13.sp, FontWeight.SemiBold, c.text))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2)
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            OutlinedCard(onClick = onBenchmarks, radius = 23.dp) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoxedIcon(Icons.Outlined.EmojiEvents, c.blue, c.raised, 43.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Benchmarks", style = textStyle(15.sp, FontWeight.SemiBold, c.text))
                        Spacer(Modifier.height(4.dp))
                        Text("Max pulls and critical force per grip, used by your workouts.", style = textStyle(13.5.sp, FontWeight.Normal, c.text2))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.text2)
                }
            }
            Spacer(Modifier.height(20.dp + LocalFloatingTabBarInset.current))
        }
    }
}

/** The measure routes and sheets, drawn over everything when a route is open. */
@Composable
fun MeasureRouteHost(nav: MeasureNavigator) {
    when (val route = nav.top) {
        is MeasureRoute.Exercise -> ExerciseScreen(nav, route)
        is MeasureRoute.Group -> GroupScreen(nav, route)
        is MeasureRoute.Result -> ResultScreen(nav, route)
        is MeasureRoute.Run -> MeasureRunScreen(nav, route.run)
        is MeasureRoute.Review -> MeasureReviewScreen(nav, route.run)
        null -> Unit
    }
}

@Composable
fun MeasureSheets(nav: MeasureNavigator, onLiveData: () -> Unit) {
    nav.startSheet?.let { StartSheet(nav, it, onLiveData) }
    nav.handSheet?.let { HandSheet(nav, it) }
}
