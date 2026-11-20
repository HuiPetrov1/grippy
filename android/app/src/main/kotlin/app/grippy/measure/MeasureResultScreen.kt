// SPDX-License-Identifier: MPL-2.0
// Grippy: one saved result as the blue card, shareable as an image. Not part of upstream.

package app.grippy.measure

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.nuri.getagrip.R
import java.io.File
import kotlin.math.roundToInt

private val CardBlue = Color(0xFF0A84FF)
private val CardLine = Color.White.copy(alpha = .32f)
private val CardSoft = Color.White.copy(alpha = .82f)

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
    BackHandler { nav.pop() }
    val layers = session.map { it.id to rememberGraphicsLayer() }.toMap()

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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            session.forEach { m ->
                ScaledCard(layers.getValue(m.id)) { ResultCard(m, exercise?.name ?: "") }
            }
            if (session.isEmpty()) EmptyNote("This result was deleted.")
            Spacer(Modifier.navigationBarsPadding().height(10.dp))
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

/** Lays the 360 × 450 dp card out at its design size, scales it to the width, and records it for sharing. */
@Composable
private fun ScaledCard(layer: GraphicsLayer, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = maxWidth / CARD_WIDTH
        Box(Modifier.fillMaxWidth().height(CARD_HEIGHT * scale).clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.TopStart) {
            Box(
                Modifier.requiredSize(CARD_WIDTH, CARD_HEIGHT)
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                // The card is a picture: fixed type, whatever the phone's text size.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) { content() }
            }
        }
    }
}

private val CARD_WIDTH = 360.dp
private val CARD_HEIGHT = 450.dp

/** The share card: Frez's blue grid of numbers around the force curve. */
@Composable
fun ResultCard(m: MeasurementEntity, exerciseName: String) {
    val type = m.measureType
    val key = GroupKey.of(m)
    val points = m.points
    val headline = type.headline
    val bodyWeight = m.bodyWeightKg?.takeIf { it > 0 }
    Column(Modifier.size(CARD_WIDTH, CARD_HEIGHT).background(CardBlue)) {
        // Header
        Row(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                Text(capsDate(m.takenAt), style = textStyle(10.sp, FontWeight.SemiBold, CardSoft, 1.6.sp))
                Spacer(Modifier.height(4.dp))
                Text(key.title, style = textStyle(30.sp, FontWeight.Bold, Color.White, (-0.8).sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(m.side.label, style = textStyle(13.sp, FontWeight.SemiBold, CardSoft), modifier = Modifier.padding(bottom = 16.dp))
        }
        HLine()
        // Headline and bodyweight
        Row(Modifier.fillMaxWidth().height(100.dp)) {
            Cell(Modifier.weight(1f)) {
                Text(headlineLabel(m), style = textStyle(10.sp, FontWeight.Medium, CardSoft), maxLines = 1)
                CardValue(formatNumber(headline.of(m), headline.unit), headline.unit, 40.sp)
                Spacer(Modifier.weight(1f))
                Text(exerciseName, style = textStyle(7.sp, FontWeight.Medium, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VLine()
            Cell(Modifier.weight(1f)) {
                Text("Of bodyweight", style = textStyle(10.sp, FontWeight.Medium, CardSoft))
                if (bodyWeight != null && headline.isForce) {
                    val pct = headline.of(m) / bodyWeight * 100
                    CardValue("${pct.roundToInt()}", "%", 30.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(.85f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .3f))) {
                        Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(.02f, 1f)).fillMaxHeight().clip(CircleShape).background(Color.White))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Bodyweight ${formatKg(bodyWeight)} kg", style = textStyle(7.sp, FontWeight.Medium, CardSoft))
                } else {
                    CardValue("—", "", 30.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (headline.isForce) "Set bodyweight in Settings" else "Not a force", style = textStyle(7.sp, FontWeight.Medium, CardSoft))
                }
            }
        }
        HLine()
        // Curve
        Column(Modifier.fillMaxWidth().height(100.dp).padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)) {
            Text("Force curve · ${m.durationSec} s", style = textStyle(10.sp, FontWeight.Medium, CardSoft))
            ResultCurve(points, m.durationSec, m.avgKg, m.maxKg, m.minKg, Modifier.fillMaxWidth().weight(1f))
        }
        HLine()
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            Cell(Modifier.weight(1f)) { Text("Max", style = textStyle(10.sp, FontWeight.Medium, CardSoft)); CardValue(formatKg(m.maxKg), "kg", 22.sp) }
            VLine()
            Cell(Modifier.weight(1f)) { Text("Min", style = textStyle(10.sp, FontWeight.Medium, CardSoft)); CardValue(formatKg(m.minKg), "kg", 22.sp) }
            VLine()
            Cell(Modifier.weight(1f)) { Text("Range", style = textStyle(10.sp, FontWeight.Medium, CardSoft)); CardValue(formatKg(m.maxKg - m.minKg), "kg", 22.sp) }
        }
        HLine()
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            Cell(Modifier.weight(1f)) {
                Text(if (type == MeasureType.Rfd) "Peak RFD" else "Force × time", style = textStyle(10.sp, FontWeight.Medium, CardSoft))
                if (type == MeasureType.Rfd) CardValue("${m.rfd.roundToInt()}", "kg/s", 26.sp) else CardValue("${m.impulse.roundToInt()}", "kg·s", 26.sp)
            }
            VLine()
            Cell(Modifier.weight(1f)) {
                Text("Grip", style = textStyle(10.sp, FontWeight.Medium, CardSoft))
                Spacer(Modifier.height(6.dp))
                Text(exerciseName, style = textStyle(13.sp, FontWeight.SemiBold, Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            VLine()
            Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(34.dp))
                Text("Grippy", style = textStyle(17.sp, FontWeight.Bold, Color.White, (-0.3).sp))
            }
        }
        HLine()
    }
}

private fun headlineLabel(m: MeasurementEntity): String = when (m.measureType) {
    MeasureType.Endurance -> "avg over ${m.durationSec} s · ${m.side.label}"
    MeasureType.Peak -> "peak · ${m.side.label}"
    MeasureType.CriticalForce -> "critical force · ${m.side.label}"
    MeasureType.Rfd -> "max rate of force · ${m.side.label}"
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp), content = content)
}

@Composable
private fun CardValue(value: String, unit: String, size: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = textStyle(size, FontWeight.Bold, Color.White, (-1).sp))
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = textStyle(size * .36f, FontWeight.SemiBold, CardSoft), modifier = Modifier.padding(bottom = (size.value * .14f).dp))
        }
    }
}

@Composable private fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(CardLine))
@Composable private fun RowScope.VLine() = Box(Modifier.width(1.dp).fillMaxHeight().background(CardLine))

private suspend fun shareCards(context: Context, cards: List<Pair<MeasurementEntity, Bitmap>>) {
    if (cards.isEmpty()) return
    val uris = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        cards.map { (m, bitmap) ->
            val file = File(dir, "grippy-${m.sessionId.take(8)}-${m.hand}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "image/png"
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share result").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
