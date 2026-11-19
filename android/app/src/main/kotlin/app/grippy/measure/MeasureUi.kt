// SPDX-License-Identifier: MPL-2.0
// Grippy: the building blocks of the Frez-style screens. Not part of upstream Get a Grip.

package app.grippy.measure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.ui.theme.AccentFill
import run.nuri.getagrip.ui.theme.DarkPalette
import run.nuri.getagrip.ui.theme.GripFonts
import run.nuri.getagrip.ui.theme.LocalGripPalette

/** Frez's surfaces, derived from the app palette so light mode follows. */
@Immutable
class MeasureColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    /** Search field, pills, sheets. */
    val raised: Color,
    /** A card inside a sheet, and the selected segment. */
    val raisedHigh: Color,
    val border: Color,
    val divider: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val blue: Color,
    val link: Color,
    val left: Color,
    val right: Color,
    /** The selected hand card / Live Data card wash. */
    val blueWash: Color,
)

@Composable
fun measureColors(): MeasureColors {
    val p = LocalGripPalette.current
    val dark = p == DarkPalette
    return if (dark) MeasureColors(
        dark = true,
        bg = p.field, card = p.card, raised = Color(0xFF2C2C34), raisedHigh = Color(0xFF3A3A42),
        border = Color(0xFF38383F), divider = Color(0xFF34343B),
        text = p.inkPrimary, text2 = p.inkSecondary, text3 = p.inkTertiary,
        blue = AccentFill, link = p.graphite,
        left = Color(0xFF30D158), right = Color(0xFFFF6961),
        blueWash = Color(0xFF12294A),
    ) else MeasureColors(
        dark = false,
        bg = p.field, card = p.card, raised = Color(0xFFE9E9EF), raisedHigh = Color(0xFFFFFFFF),
        border = Color(0xFFE2E2E8), divider = Color(0xFFE5E5EA),
        text = p.inkPrimary, text2 = p.inkSecondary, text3 = p.inkTertiary,
        blue = AccentFill, link = p.graphite,
        left = Color(0xFF1FA34A), right = Color(0xFFE5484D),
        blueWash = Color(0xFFE3F0FF),
    )
}

fun textStyle(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, tracking: TextUnit = 0.sp) =
    TextStyle(fontFamily = GripFonts.text, fontSize = size, fontWeight = weight, color = color, letterSpacing = tracking)

@Composable
fun BackButton(onBack: () -> Unit, tint: Color = measureColors().text) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = tint, modifier = Modifier.size(34.dp))
    }
}

/** A large left-aligned page title with optional trailing actions, Frez's tab headers. */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = measureColors()
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = textStyle(32.sp, FontWeight.Bold, c.text, (-0.5).sp), modifier = Modifier.weight(1f))
        actions()
    }
}

/** The pushed-screen bar: back chevron, centred title, trailing actions. */
@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleExtra: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = measureColors()
    Box(modifier.fillMaxWidth().statusBarsPadding().height(60.dp)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) { BackButton(onBack) }
        Row(Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = textStyle(21.sp, FontWeight.SemiBold, c.text), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            titleExtra()
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** The blue, full-width, fully rounded call to action. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = measureColors()
    Box(
        modifier.fillMaxWidth().height(60.dp).clip(CircleShape)
            .background(if (enabled) c.blue else c.raisedHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle(19.sp, FontWeight.SemiBold, if (enabled) Color.White else c.text3))
    }
}

/** A secondary full-width button on the raised grey. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) {
    val c = measureColors()
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(c.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle(17.sp, FontWeight.SemiBold, color ?: c.text))
    }
}

/** Live Data / Measure: the two big pills under the search field. */
@Composable
fun BigPill(text: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = measureColors()
    Row(
        modifier.height(64.dp).clip(CircleShape).background(if (filled) c.blue else c.raised).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (filled) Color.White else c.text
        Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = textStyle(19.sp, FontWeight.SemiBold, ink))
    }
}

/** "+ Measure" on the grey, beside a section title. */
@Composable
fun SmallPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = measureColors()
    Row(
        modifier.height(44.dp).clip(CircleShape).background(c.raised).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = textStyle(16.sp, FontWeight.Medium, c.text), maxLines = 1, softWrap = false)
    }
}

/** A rounded card with Frez's hairline outline. */
@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    radius: Dp = 24.dp,
    color: Color? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = measureColors()
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(color ?: c.card)
            .border(BorderStroke(1.dp, borderColor ?: c.border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/** Average / Max / Total Load and 1M … All. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    /** False: each segment as wide as its label (Average / Max / Total Load). */
    equal: Boolean = true,
    fontSize: TextUnit = 16.sp,
) {
    val c = measureColors()
    Row(
        modifier.height(height).clip(RoundedCornerShape(14.dp)).background(if (c.dark) Color(0xFF111114) else c.raised).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                (if (equal) Modifier.weight(1f) else Modifier).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(if (on) (if (c.dark) Color(0xFF2E2E36) else Color.White) else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = if (equal) 6.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    style = textStyle(fontSize, if (on) FontWeight.SemiBold else FontWeight.Medium, if (on) c.text else c.text2),
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** The type pill in a group's top bar: "● Endurance". */
@Composable
fun TypeChip(type: MeasureType) {
    val c = measureColors()
    Row(Modifier.clip(CircleShape).background(c.raised).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(type.dot, 10.dp)
        Spacer(Modifier.width(8.dp))
        Text(type.label, style = textStyle(16.sp, FontWeight.SemiBold, c.text2))
    }
}

/** A big number with its unit set smaller beside it: "6.6 kg". */
@Composable
fun ValueWithUnit(value: String, unit: String, size: TextUnit, color: Color, unitColor: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = textStyle(size, FontWeight.SemiBold, color, (-0.5).sp))
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unit, style = textStyle(size * 0.5f, FontWeight.Medium, unitColor), modifier = Modifier.padding(bottom = (size.value * 0.12f).dp))
        }
    }
}

/** The caps section label: "LATEST", "HISTORY". */
@Composable
fun CapsHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = textStyle(17.sp, FontWeight.SemiBold, measureColors().text2, 0.5.sp), modifier = modifier)
}

@Composable
fun SheetSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), content = content)
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = textStyle(16.sp, FontWeight.Normal, measureColors().text2), textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(24.dp))
}

@Composable
fun BoxedIcon(icon: ImageVector, tint: Color, background: Color, size: Dp = 56.dp, content: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
        content()
    }
}

val PagePadding = PaddingValues(horizontal = 20.dp)

@Composable
fun SurfaceBox(content: @Composable () -> Unit) {
    Surface(color = measureColors().bg, content = content)
}
