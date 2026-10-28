package ai.vipseat.cargoconcierge.ui.theme

import ai.vipseat.cargoconcierge.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Pretendard (SIL OFL 1.1, see app/licenses) — Korean/Latin workhorse with even weights for car UI. */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Pretendard, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.sp,
)

// Sized for a 2560×1440 @160 dpi head unit read from the driver seat (~75 cm). Display styles are tight-tracked.
val CarTypography = Typography(
    displayLarge = style(88, FontWeight.ExtraBold, 96, -2.2f),
    displayMedium = style(60, FontWeight.ExtraBold, 68, -1.4f),
    displaySmall = style(48, FontWeight.Bold, 56, -1f),
    headlineLarge = style(42, FontWeight.Bold, 50, -0.8f),
    headlineMedium = style(32, FontWeight.SemiBold, 40, -0.4f),
    headlineSmall = style(26, FontWeight.SemiBold, 34, -0.2f),
    titleLarge = style(27, FontWeight.SemiBold, 34, -0.2f),
    titleMedium = style(22, FontWeight.SemiBold, 30),
    titleSmall = style(19, FontWeight.SemiBold, 26),
    bodyLarge = style(22, FontWeight.Normal, 31),
    bodyMedium = style(18, FontWeight.Normal, 26),
    bodySmall = style(16, FontWeight.Normal, 22),
    labelLarge = style(20, FontWeight.SemiBold, 26),
    labelMedium = style(16, FontWeight.SemiBold, 20, 0.2f),
    labelSmall = style(13, FontWeight.SemiBold, 16, 0.8f),
)
