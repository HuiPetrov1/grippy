// SPDX-License-Identifier: MPL-2.0
// Grippy: how the camera phone's screen looks. Big enough to read from where you train.
// Not part of upstream.

package app.grippy.camera

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.grippy.measure.textStyle

private val Blue = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val Amber = Color(0xFFFFB340)
private val Scrim = Color(0xB3000000)
private val Soft = Color(0xCCFFFFFF)

@Composable
fun CameraScreen(
    ui: CameraUi,
    onPreview: (PreviewView) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFlip: () -> Unit,
    onClose: () -> Unit,
) {
    val listeners by CamHub.listeners.collectAsState()
    val counting = ui.state == CamState.Counting
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreview(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        HandOverlay(ui.hand, ui.aspect, if (ui.tracking == Tracking.Hand) Green else Amber, Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Filled.Close, "Close camera", onClose)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val title = listOfNotNull(ui.exercise.takeIf { it.isNotBlank() }, ui.side?.takeIf { counting }?.let { "${it.label} hand" })
                        .joinToString(" · ").ifEmpty { "Grippy camera" }
                    Pill(title, Color.White, Scrim, 17)
                }
                if (!counting) RoundButton(Icons.Filled.Cameraswitch, "Switch camera", onFlip) else Spacer(Modifier.size(46.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    ui.state == CamState.Starting -> Pill("Opening the camera…", Soft, Scrim, 15)
                    ui.tracking == Tracking.Hand -> Pill("● Hand found", Green, Scrim, 15)
                    counting -> Pill("● Following the movement", Amber, Scrim, 15)
                    else -> Pill("Put your hand and forearm in view", Soft, Scrim, 15)
                }
            }
        }

        if (counting || ui.last != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = ui.count,
                    transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f), initialScale = 0.7f) + fadeIn()) togetherWith fadeOut() },
                    label = "count",
                ) { n ->
                    Text(
                        "$n",
                        style = textStyle(190.sp, FontWeight.Bold, Color.White, (-6).sp)
                            .copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 4f), 28f)),
                        modifier = Modifier.semantics { contentDescription = "$n reps" },
                    )
                }
                val line = if (counting && ui.count == 0) "counts after your 3rd rep" else if (counting) "reps · ${ui.elapsed.toInt()} s" else ui.last?.let { "reps in ${(it.durationMs / 1000.0).toInt()} s · done" } ?: ""
                Pill(line, Color.White, Scrim, 20)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.problem.isNotEmpty()) {
                Text(ui.problem, style = textStyle(16.sp, FontWeight.SemiBold, Color.White), textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6B3261E)).padding(14.dp))
                Spacer(Modifier.height(10.dp))
            }
            Text(
                when {
                    listeners > 0 && counting -> "Counting for your main phone · ${ui.fps} frames/s"
                    listeners > 0 -> "Connected to your main phone · ${ui.fps} frames/s"
                    else -> "Main phone not connected · ${ui.fps} frames/s"
                },
                style = textStyle(13.5.sp, FontWeight.Medium, Soft),
                modifier = Modifier.clip(CircleShape).background(Scrim).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            val ready = ui.state == CamState.Framing || counting
            Box(
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (!ready) Color(0x66FFFFFF) else if (counting) Color(0xFFFF453A) else Blue)
                    .clickable(enabled = ready, onClick = if (counting) onStop else onStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (counting) "Stop" else if (ui.last != null) "Count another set" else "Start counting",
                    style = textStyle(19.sp, FontWeight.SemiBold, Color.White))
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, background: Color, size: Int) {
    Text(text, style = textStyle(size.sp, FontWeight.SemiBold, color), maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Scrim).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/**
 * The followed hand drawn over the picture. [points]: 21 x, y pairs in 0…1 of the picture, which
 * sits centred in this box at [aspect] (width ÷ height), the way the preview does.
 */
@Composable
fun HandOverlay(points: FloatArray?, aspect: Float, color: Color, modifier: Modifier = Modifier, strokeDp: Float = 4f) {
    Canvas(modifier) {
        val p = points ?: return@Canvas
        if (p.size < 42 || aspect <= 0f) return@Canvas
        val w = minOf(size.width, size.height * aspect)
        val picture = Size(w, w / aspect)
        val origin = Offset((size.width - picture.width) / 2, (size.height - picture.height) / 2)
        fun at(i: Int) = Offset(origin.x + p[i * 2] * picture.width, origin.y + p[i * 2 + 1] * picture.height)
        val bones = HandTracker.BONES
        for (i in bones.indices step 2) {
            drawLine(color, at(bones[i]), at(bones[i + 1]), strokeDp.dp.toPx(), StrokeCap.Round)
        }
        for (i in 0 until 21) drawCircle(Color.White, (strokeDp * 0.9f).dp.toPx(), at(i))
    }
}
