package ai.vipseat.cargoconcierge.ui.theme

import ai.vipseat.cargoconcierge.core.model.CargoKind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- tokens (mirrors design/build_kit.py)
// Dark cabin palette: low glare, high contrast — the driving badge must read at a glance.
val Ink = Color(0xFF0B0F14)
val InkSoft = Color(0xFF0F161E)
val Panel = Color(0xFF121922)
val PanelHigh = Color(0xFF1A2432)
val Line = Color(0xFF243244)
val Mist = Color(0xFFE6EDF3)
val MistDim = Color(0xFF8B9BB0)
val Mint = Color(0xFF4FD1C5)
val MintDeep = Color(0xFF1FA396)
val MintSoft = Color(0xFF9BEAE2)
val Champagne = Color(0xFFF2D18B)
val Gold = Color(0xFFD9A541)
val Amber = Color(0xFFF6AD55)
val Coral = Color(0xFFFC8181)
val Leaf = Color(0xFF68D391)

// Per-item accent (disc tint, tile gradient, chip colour).
val Rose = Color(0xFFF59CB7)
val Lilac = Color(0xFFC4A7F7)
val Mocha = Color(0xFFC58B5A)
val Sky = Color(0xFF7DD3FC)
val Steel = Color(0xFFB8C4D4)
val Kraft = Color(0xFFD9B48F)

fun itemColor(kind: CargoKind): Color = when (kind) {
    CargoKind.CAKE -> Rose
    CargoKind.HOT_FOOD -> Amber
    CargoKind.FLOWERS -> Lilac
    CargoKind.GROCERIES -> Leaf
    CargoKind.COFFEE -> Mocha
    CargoKind.FRAGILE -> Sky
    CargoKind.ELECTRONICS -> Steel
    CargoKind.PET -> Champagne
    CargoKind.GENERAL -> Kraft
}

/** 1 px hairline used on every card: white at 6 %. */
val Hairline = Mist.copy(alpha = 0.06f)

private val ColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    secondary = Champagne,
    onSecondary = Ink,
    tertiary = Amber,
    background = Ink,
    onBackground = Mist,
    surface = Panel,
    onSurface = Mist,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = MistDim,
    outline = Line,
    error = Coral,
)

private val CarShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun CargoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = CarTypography, shapes = CarShapes) {
        // Cards are plain Boxes (hairline + gradient), not Material Surfaces, so the default content
        // colour would be black: every Text/Icon without an explicit colour reads Mist from here.
        CompositionLocalProvider(LocalContentColor provides Mist, content = content)
    }
}
