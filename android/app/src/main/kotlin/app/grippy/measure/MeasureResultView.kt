// SPDX-License-Identifier: MPL-2.0
// Grippy: one saved result, to explore — a scrubbable force curve, the numbers counting in,
// both hands side by side — with the blue share card underneath. Not part of upstream.

package app.grippy.measure

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val timeOfDay = SimpleDateFormat("HH:mm", Locale.US)

@Composable
fun ResultScreen(nav: MeasureNavigator, route: MeasureRoute.Result) {
    val c = measureColors()
    val store = rememberMeasureStore()
    val data = store.collect()
    val session = data.session(route.sessionId)
    val exercise = session.firstOrNull()?.let { data.exercise(it.exerciseId) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var handKey by rememberSaveable { mutableStateOf(session.firstOrNull()?.hand) }
    BackHandler { nav.pop() }
    val layers = session.map { it.id to rememberGraphicsLayer() }.toMap()
    val shown = session.firstOrNull { it.hand == handKey } ?: session.firstOrNull()

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(exercise?.name ?: "Result", onBack = { nav.pop() }, actions = {
            IconButton(onClick = {
                scope.launch {
                    val bitmaps = session.mapNotNull { m -> layers[m.id]?.toImageBitmap()?.asAndroidBitmap()?.let { m to it } }
                    shareCards(context, bitmaps)
                }
            }, enabled = session.isNotEmpty()) { Icon(Icons.Outlined.Share, "Share", tint = c.text) }
            IconButton(onClick = { deleting = true }, enabled = session.isNotEmpty()) { Icon(Icons.Outlined.Delete, "Delete", tint = c.text) }
        })
        if (shown == null) {
            EmptyNote("This result was deleted.")
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(shown.measureType)
                Spacer(Modifier.width(10.dp))
                Text("${longDate(shown.takenAt)} · ${timeOfDay.format(Date(shown.takenAt))}",
                    style = textStyle(14.sp, FontWeight.Normal, c.text2))
            }
            if (session.size > 1) {
                Spacer(Modifier.height(14.dp))
                Segmented(session, shown, { it.side.label }, { handKey = it.hand }, Modifier.fillMaxWidth(), height = 40.dp, fontSize = 15.sp)
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    val forward = targetState.side.ordinal > initialState.side.ordinal
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn(tween(250))) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
                },
                contentKey = { it.id },
                label = "hand",
            ) { m -> HandDetail(m) }
            if (session.size > 1) {
                Spacer(Modifier.height(14.dp))
                BalanceCard(session)
            }
            Spacer(Modifier.height(26.dp))
            CapsHeader("Share card")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                session.forEach { m -> ScaledCard(layers.getValue(m.id)) { ResultCard(m, exercise?.name ?: "") } }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton(if (session.size > 1) "Share both cards" else "Share card", onClick = {
                scope.launch {
                    val bitmaps = session.mapNotNull { m -> layers[m.id]?.toImageBitmap()?.asAndroidBitmap()?.let { m to it } }
                    shareCards(context, bitmaps)
                }
            })
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this result?") },
            text = { Text(if (session.size > 1) "Both hands' readings are deleted. This can't be undone." else "This can't be undone.") },
            confirmButton = { TextButton(onClick = { deleting = false; nav.pop(); store.deleteSession(route.sessionId) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** One hand: the headline counting in, the curve to scrub, and the numbers as tiles. */
@Composable
private fun HandDetail(m: MeasurementEntity) {
    val c = measureColors()
    val type = m.measureType
    val headline = type.headline
    val handColor = if (m.side == Hand.Left) c.left else c.right
    val points = remember(m.id) { m.points }
    val bodyWeight = m.bodyWeightKg?.takeIf { it > 0 }
    Column {
        OutlinedCard(radius = 22.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(handColor, 10.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${m.side.label} · ${headlineCaption(m)}", style = textStyle(14.sp, FontWeight.Medium, c.text2))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(headline.of(m), headline.unit, 52.sp, c.text, c.text2)
                    Spacer(Modifier.weight(1f))
                    if (bodyWeight != null && headline.isForce) {
                        Box(Modifier.padding(bottom = 10.dp).clip(CircleShape).background(c.raised).padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text("${(headline.of(m) / bodyWeight * 100).roundToInt()} % BW", style = textStyle(13.sp, FontWeight.SemiBold, c.text))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (type.usesCamera) {
                    val marks = remember(m.id) { m.marks.map { it.tMs / 1000.0 } }
                    ScrubCurve(points, m.durationSec.toDouble(), 0.0, handColor, Modifier.fillMaxWidth().height(200.dp), unit = "%", marks = marks)
                    Spacer(Modifier.height(6.dp))
                    Text("The movement the camera followed. Each tick along the bottom is a counted rep.", style = textStyle(12.sp, FontWeight.Normal, c.text3))
                } else {
                    ScrubCurve(points, m.durationSec.toDouble(), m.avgKg, handColor, Modifier.fillMaxWidth().height(200.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Touch and drag the curve to read any moment.", style = textStyle(12.sp, FontWeight.Normal, c.text3))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val tiles = statTiles(m, points)
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { col, tile ->
                    StatTile(tile, index = row * 2 + col, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (type == MeasureType.CriticalForce) {
            CfReps(points, m.cfKg, handColor)
        }
    }
}

private fun headlineCaption(m: MeasurementEntity): String = when (m.measureType) {
    MeasureType.Endurance -> "average over ${m.durationSec} s"
    MeasureType.Peak -> "peak force"
    MeasureType.CriticalForce -> "critical force"
    MeasureType.Rfd -> "rate of force development"
    MeasureType.Reps -> "counted by camera"
}

private class Tile(val label: String, val value: Double, val unit: String, val decimals: Int = 1)

private fun statTiles(m: MeasurementEntity, points: List<ForcePoint>): List<Tile> {
    if (m.measureType.usesCamera) {
        val took = m.marks.filter { it.durationMs > 0 }.map { it.durationMs / 1000.0 }
        val tiles = mutableListOf(Tile("Time", m.durationSec.toDouble(), "s", 0))
        m.loadKg?.let { tiles += Tile("Weight", it, "kg") }
        if (m.reps > 0) tiles += Tile("Tempo", m.tempoSec, "s/rep")
        took.minOrNull()?.let { tiles += Tile("Fastest rep", it, "s") }
        took.maxOrNull()?.let { tiles += Tile("Slowest rep", it, "s") }
        if (m.marks.isNotEmpty()) tiles += Tile("Range of motion", m.marks.map { it.amplitude }.average(), "%", 0)
        if (m.marks.size != m.reps) tiles += Tile("Camera counted", m.marks.size.toDouble(), "reps", 0)
        return tiles
    }
    val ttp = MeasureMath.timeToPeak(points)
    val base = mutableListOf(
        Tile("Max", m.maxKg, "kg"),
        Tile("Average", m.avgKg, "kg"),
        Tile("Min", m.minKg, "kg"),
        Tile("Range", m.maxKg - m.minKg, "kg"),
        Tile("Force × time", m.impulse, "kg·s", 0),
        Tile("RFD", m.rfd, "kg/s", 0),
    )
    if (ttp != null) base += Tile("Time to peak", ttp, "s", 2)
    base += Tile("Duration", m.durationSec.toDouble(), "s", 0)
    if (m.measureType == MeasureType.CriticalForce) base.add(0, Tile("Critical force", m.cfKg, "kg"))
    // The headline is already the big number above.
    val headline = m.measureType.headline.label
    return base.filterNot { it.label == headline || (headline == "RFD" && it.label == "RFD") }
}

@Composable
private fun StatTile(tile: Tile, index: Int, modifier: Modifier) {
    val c = measureColors()
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(320, delayMillis = 60 * index)) + slideInVertically(tween(380, delayMillis = 60 * index)) { it / 3 },
        modifier = modifier,
    ) {
        OutlinedCard(radius = 18.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(tile.label, style = textStyle(13.sp, FontWeight.Medium, c.text2))
                Spacer(Modifier.height(4.dp))
                CountUp(tile.value, tile.unit, 24.sp, c.text, c.text2, decimals = tile.decimals, delayMillis = 60 * index)
            }
        }
    }
}

/** A number that counts up from zero the first time it shows, and glides when it changes. */
@Composable
fun CountUp(
    value: Double, unit: String, size: androidx.compose.ui.unit.TextUnit, color: Color, unitColor: Color,
    decimals: Int = if (unit in setOf("kg·s", "kg/s", "reps", "s", "%")) 0 else 1, delayMillis: Int = 0,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (delayMillis > 0 && anim.value == 0f) delay(delayMillis.toLong())
        anim.animateTo(value.toFloat(), tween(850, easing = FastOutSlowInEasing))
    }
    val text = if (decimals == 0) "${anim.value.roundToInt()}" else "%.${decimals}f".format(Locale.US, anim.value)
    ValueWithUnit(text, unit, size, color, unitColor)
}

/**
 * The force curve, drawn in from the left. Touch or drag anywhere to pin a moment: a line,
 * a dot on the curve and the time and force there. The average is the dashed line.
 */
@Composable
fun ScrubCurve(
    points: List<ForcePoint>, seconds: Double, avg: Double, color: Color, modifier: Modifier = Modifier,
    /** "kg" for a force; "%" for a camera-followed movement, which has no peak worth marking. */
    unit: String = "kg",
    /** Moments to tick along the bottom (seconds): the counted reps. */
    marks: List<Double> = emptyList(),
) {
    val c = measureColors()
    val measurer = rememberTextMeasurer()
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    var touch by remember(points) { mutableStateOf<Float?>(null) }
    val pinAlpha by animateFloatAsState(if (touch != null) 1f else 0f, tween(180), label = "pin")
    var lastTouch by remember(points) { mutableStateOf(0f) }
    if (touch != null) lastTouch = touch!!

    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No readings", style = textStyle(14.sp, FontWeight.Normal, c.text3)) }
        return
    }
    val span = max(seconds, points.last().t).coerceAtLeast(0.5)
    val peak = points.maxBy { it.kg }
    // A movement is drawn across its own range, 0 to 100 %.
    val ticks = if (unit == "%") listOf(0.0, 50.0, 100.0) else niceTicks(0.0, max(peak.kg, 1.0) * 1.1, 4)
    val top = ticks.last()
    val labelStyle = TextStyle(fontFamily = run.nuri.getagrip.ui.theme.GripFonts.text, fontSize = 11.sp, color = c.text3)

    Canvas(
        modifier
            .pointerInput(points) { detectTapGestures(onPress = { touch = it.x; tryAwaitRelease() }) }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { touch = it.x },
                    onHorizontalDrag = { change, _ -> change.consume(); touch = change.position.x },
                )
            },
    ) {
        val axis = 34.dp.toPx()
        val w = size.width - axis
        val h = size.height - 18.dp.toPx()
        fun x(t: Double) = (t / span * w).toFloat()
        fun y(kg: Double) = (h - (kg / top) * h).toFloat()

        // Grid and its labels on the right.
        ticks.forEach { v ->
            val yy = y(v)
            drawLine(c.divider, Offset(0f, yy), Offset(w, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            val label = measurer.measure("${formatTick(v)}", labelStyle)
            drawText(label, topLeft = Offset(w + 6.dp.toPx(), yy - label.size.height / 2f))
        }
        val zero = measurer.measure("0 s", labelStyle)
        drawText(zero, topLeft = Offset(0f, h + 3.dp.toPx()))
        val end = measurer.measure("${formatTick(span)} s", labelStyle)
        drawText(end, topLeft = Offset(w - end.size.width, h + 3.dp.toPx()))

        val line = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.kg)) else lineTo(x(p.t), y(p.kg)) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.last().t), h)
            lineTo(x(points.first().t), h)
            close()
        }
        clipRect(right = w * reveal.value) {
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .35f), color.copy(alpha = 0f)), startY = 0f, endY = h))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Average, dashed.
        if (avg > 0) {
            val ay = y(avg)
            drawLine(c.text2.copy(alpha = .8f * reveal.value), Offset(0f, ay), Offset(w * reveal.value, ay), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        for (mark in marks) {
            val mx = x(mark)
            if (mx <= w * reveal.value) drawLine(color, Offset(mx, h - 9.dp.toPx()), Offset(mx, h), 2.dp.toPx(), StrokeCap.Round)
        }
        // The peak, once the curve reaches it.
        if (unit == "kg" && x(peak.t) <= w * reveal.value) {
            drawCircle(c.bg, 5.dp.toPx(), Offset(x(peak.t), y(peak.kg)))
            drawCircle(color, 5.dp.toPx(), Offset(x(peak.t), y(peak.kg)), style = Stroke(2.dp.toPx()))
        }
        // The pinned moment.
        if (pinAlpha > 0f) {
            val px = lastTouch.coerceIn(0f, w)
            val t = px / w * span
            val kg = valueAt(points, t)
            val py = y(kg)
            drawLine(c.text.copy(alpha = .5f * pinAlpha), Offset(px, 0f), Offset(px, h), 1.dp.toPx())
            drawCircle(color.copy(alpha = pinAlpha), 6.dp.toPx(), Offset(px, py))
            drawCircle(Color.White.copy(alpha = pinAlpha), 2.5.dp.toPx(), Offset(px, py))
            val text = measurer.measure("${if (unit == "kg") formatKg(kg) else kg.roundToInt().toString()} $unit · ${"%.1f".format(Locale.US, t)} s",
                TextStyle(fontFamily = run.nuri.getagrip.ui.theme.GripFonts.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text.copy(alpha = pinAlpha)))
            val pad = 8.dp.toPx()
            val bw = text.size.width + pad * 2
            val bh = text.size.height + pad
            val bx = (px - bw / 2).coerceIn(0f, w - bw)
            val by = (py - bh - 12.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(c.raisedHigh.copy(alpha = pinAlpha), Offset(bx, by), Size(bw, bh), CornerRadius(10.dp.toPx()))
            drawText(text, topLeft = Offset(bx + pad, by + pad / 2))
        }
    }
}

private fun formatTick(v: Double): String = if (v == v.roundToInt().toDouble()) "${v.roundToInt()}" else "%.1f".format(Locale.US, v)

/** Force at [t], straight between the two readings around it. */
internal fun valueAt(points: List<ForcePoint>, t: Double): Double {
    if (points.isEmpty()) return 0.0
    if (t <= points.first().t) return points.first().kg
    if (t >= points.last().t) return points.last().kg
    var lo = 0
    var hi = points.lastIndex
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (points[mid].t <= t) lo = mid else hi = mid
    }
    val a = points[lo]
    val b = points[hi]
    val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
    return a.kg + (b.kg - a.kg) * f
}

/** Left against right on the headline number, bars growing in. */
@Composable
private fun BalanceCard(session: List<MeasurementEntity>) {
    val c = measureColors()
    val type = session.first().measureType
    val metric = type.headline
    val left = session.firstOrNull { it.side == Hand.Left }?.let { metric.of(it) }
    val right = session.firstOrNull { it.side == Hand.Right }?.let { metric.of(it) }
    val strong = max(left ?: 0.0, right ?: 0.0).takeIf { it > 0 } ?: return
    OutlinedCard(radius = 22.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance", style = textStyle(16.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                imbalance(left, right)?.let {
                    Text(if (it < 0.05) "Even" else "${(it * 100).roundToInt()} % apart",
                        style = textStyle(14.sp, FontWeight.Medium, if (it < 0.1) c.text2 else c.right))
                }
            }
            Spacer(Modifier.height(12.dp))
            listOf(Hand.Left to left, Hand.Right to right).forEach { (hand, value) ->
                if (value == null) return@forEach
                val grow = remember { Animatable(0f) }
                LaunchedEffect(value) { grow.animateTo((value / strong).toFloat(), tween(900, easing = FastOutSlowInEasing)) }
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(hand.label, style = textStyle(14.sp, FontWeight.Medium, c.text2), modifier = Modifier.width(44.dp))
                    Box(Modifier.weight(1f).height(12.dp).clip(CircleShape).background(c.raised)) {
                        Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.001f, 1f)).fillMaxHeight().clip(CircleShape)
                            .background(if (hand == Hand.Left) c.left else c.right))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${formatNumber(value, metric.unit)} ${metric.unit}", style = textStyle(14.sp, FontWeight.SemiBold, c.text),
                        modifier = Modifier.width(72.dp))
                }
            }
        }
    }
}

/** Critical Force: each rep's mean force as a bar, rising in one after another, CF dashed. */
@Composable
private fun CfReps(points: List<ForcePoint>, cf: Double, color: Color) {
    val c = measureColors()
    val reps = remember(points) { MeasureMath.cfReps(points) }
    if (reps.isEmpty()) return
    val grow = remember { Animatable(0f) }
    LaunchedEffect(reps) { grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    OutlinedCard(radius = 22.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("Reps", style = textStyle(16.sp, FontWeight.SemiBold, c.text))
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val top = (reps.max() * 1.1).coerceAtLeast(1.0)
                val slot = size.width / reps.size
                reps.forEachIndexed { i, v ->
                    val local = ((grow.value * reps.size) - i).coerceIn(0f, 1f)
                    val bh = (v / top * size.height).toFloat() * local
                    drawRoundRect(color.copy(alpha = .85f), Offset(i * slot + slot * .15f, size.height - bh), Size(slot * .7f, bh),
                        CornerRadius(3.dp.toPx()))
                }
                if (cf > 0) {
                    val y = (size.height - cf / top * size.height).toFloat()
                    drawLine(c.text, Offset(0f, y), Offset(size.width * grow.value, y), 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Dashed: critical force, the mean of the last six reps.", style = textStyle(12.sp, FontWeight.Normal, c.text3))
        }
    }
}
