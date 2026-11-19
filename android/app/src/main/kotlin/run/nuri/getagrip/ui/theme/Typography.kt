// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
// Grippy: Inter replaces the system face (bundled, OFL 1.1 — see LICENSES/Inter-OFL-1.1.txt).

package run.nuri.getagrip.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.R

/// Grippy's face, bundled so nothing depends on Play services or a download.
object GripFonts {
    /// Running text, rows and buttons: Inter.
    val text = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

    /// Headings and every big number: Inter again, but the light weights map up to SemiBold
    /// so the old thin numerals become solid ones.
    val display = FontFamily(
        Font(R.font.inter_semibold, FontWeight.Thin),
        Font(R.font.inter_semibold, FontWeight.ExtraLight),
        Font(R.font.inter_semibold, FontWeight.Light),
        Font(R.font.inter_semibold, FontWeight.Normal),
        Font(R.font.inter_semibold, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
}

private fun type(
    size: Int, leading: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f,
    family: FontFamily = GripFonts.text,
) = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = leading.sp,
    fontWeight = weight, letterSpacing = tracking.sp)

// The original sizes and leading (tuned for every layout's large-text fits); the Inter face and
// heavier display weights are Grippy's.
val GripTypography = Typography(
    displayLarge = type(57, 60, FontWeight.SemiBold, -1.2f, GripFonts.display),
    displayMedium = type(45, 49, FontWeight.SemiBold, -.9f, GripFonts.display),
    displaySmall = type(36, 40, FontWeight.Bold, -.7f, GripFonts.display),
    headlineLarge = type(32, 37, FontWeight.Bold, -.5f, GripFonts.display),
    headlineMedium = type(28, 33, FontWeight.Bold, -.4f, GripFonts.display),
    headlineSmall = type(24, 29, FontWeight.Bold, -.3f, GripFonts.display),
    titleLarge = type(22, 27, FontWeight.SemiBold, -.2f, GripFonts.display),
    titleMedium = type(17, 23, FontWeight.SemiBold, -.1f),
    titleSmall = type(15, 21, FontWeight.SemiBold),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 21),
    bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold, .1f),
    labelMedium = type(12, 16, FontWeight.SemiBold, .3f),
    labelSmall = type(11, 16, FontWeight.Bold, 1f),
)
