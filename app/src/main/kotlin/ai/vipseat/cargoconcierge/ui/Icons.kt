package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.Hairline
import ai.vipseat.cargoconcierge.ui.theme.Ink
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.Mist
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.Panel
import ai.vipseat.cargoconcierge.ui.theme.PanelHigh
import ai.vipseat.cargoconcierge.ui.theme.itemColor
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ------------------------------------------------------------------------------------------------
 * Visual kit bindings. Every glyph is a VectorDrawable generated from design/build_kit.py:
 *  - ic_*      24-grid mono line icons, tinted here
 *  - ic_item_* 64-grid multi-colour pictograms, never tinted, shown on a tinted disc
 *  - ic_mark   brand mark (seat + sparkles), il_hero / il_route illustrations
 * ---------------------------------------------------------------------------------------------- */

@DrawableRes
/** Pictograms: designer-style raster art (design/raster → drawable-nodpi/art_item_*.png, 512 px, transparent); the vector ic_item_* set stays as a fallback kit. */
fun itemArt(kind: CargoKind): Int = when (kind) {
    CargoKind.CAKE -> R.drawable.art_item_cake
    CargoKind.HOT_FOOD -> R.drawable.art_item_hotfood
    CargoKind.FLOWERS -> R.drawable.art_item_flowers
    CargoKind.GROCERIES -> R.drawable.art_item_groceries
    CargoKind.COFFEE -> R.drawable.art_item_coffee
    CargoKind.FRAGILE -> R.drawable.art_item_fragile
    CargoKind.ELECTRONICS -> R.drawable.art_item_device
    CargoKind.PET -> R.drawable.art_item_pet
    CargoKind.GENERAL -> R.drawable.art_item_box
}

/** Mono line icon, tinted. */
@Composable
fun LineIcon(@DrawableRes res: Int, modifier: Modifier = Modifier, tint: Color = MistDim, size: Dp = 24.dp) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Pictogram on its tinted disc: radial item tint over panel, hairline ring in the item colour. */
@Composable
fun PresetArt(preset: CargoPreset, size: Dp, modifier: Modifier = Modifier, ring: Boolean = true) {
    val c = itemColor(preset.kind)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(c.copy(alpha = 0.34f), c.copy(alpha = 0.10f), Panel), center = Offset.Unspecified, radius = Float.POSITIVE_INFINITY))
            .then(if (ring) Modifier.border(1.dp, c.copy(alpha = 0.35f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(itemArt(preset.kind)), contentDescription = preset.displayName, modifier = Modifier.size(size * 0.78f))
    }
}

/** Small inline pictogram (chips, lists). */
@Composable
fun PresetGlyph(preset: CargoPreset, size: Dp = 28.dp) {
    Image(painterResource(itemArt(preset.kind)), contentDescription = null, modifier = Modifier.size(size))
}

/** Brand lockup: mark + wordmark. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, markSize: Dp = 56.dp, showSubtitle: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_mark), contentDescription = "VIP 조수석", modifier = Modifier.size(markSize))
        Spacer(Modifier.width(18.dp))
        Column {
            Text("VIP 조수석", style = MaterialTheme.typography.headlineLarge, color = Mist)
            if (showSubtitle) {
                Text("CARGO CONCIERGE · 적재물 기준으로 운전을 돕는 차", style = MaterialTheme.typography.labelMedium, color = MistDim)
            }
        }
    }
}

/** Home hero: seat + belted cake box over a soft mint glow (glow drawn here; the vector is flat). */
@Composable
fun HeroIllustration(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(560f / 320f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.45f, size.height * 0.6f)
            drawCircle(Brush.radialGradient(listOf(Mint.copy(alpha = 0.22f), Mint.copy(alpha = 0.0f)), center = c, radius = size.width * 0.42f), radius = size.width * 0.42f, center = c)
            drawOval(Color.Black.copy(alpha = 0.38f), topLeft = Offset(size.width * 0.12f, size.height * 0.815f), size = androidx.compose.ui.geometry.Size(size.width * 0.57f, size.height * 0.06f))
        }
        Image(painterResource(R.drawable.art_hero), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** Trip route strip for the arrival report (start · warnings · flag). */
@Composable
fun RouteArt(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.il_route), contentDescription = null, modifier = modifier.fillMaxWidth().aspectRatio(320f / 80f))
}

/**
 * Driving badge: static ring in the level colour with a soft halo, pictogram disc inside.
 * Nothing animates — colour + one line is all a glance needs.
 */
@Composable
fun GuardBadge(preset: CargoPreset, color: Color, size: Dp = 340.dp, label: String) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.toPx() / 2f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)), radius = r), radius = r)
            drawCircle(PanelHigh, radius = r * 0.78f, style = Stroke(width = r * 0.075f))
            drawArc(color, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(width = r * 0.075f, cap = StrokeCap.Round),
                topLeft = Offset(r - r * 0.78f, r - r * 0.78f), size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f))
            drawCircle(Ink.copy(alpha = 0.55f), radius = r * 0.70f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PresetArt(preset, size * 0.42f, ring = false)
            Spacer(Modifier.size(10.dp))
            Text(label, style = MaterialTheme.typography.headlineMedium, color = color)
        }
    }
}

/** Champagne sparkle, used as the VIP accent on the preset sheet title. */
@Composable
fun Sparkle(size: Dp = 28.dp, tint: Color = Champagne) = LineIcon(R.drawable.ic_sparkle, tint = tint, size = size)

/** Card surface: panel colour, 1 px hairline, large radius. */
fun Modifier.cardSurface(radius: Dp = 28.dp, color: Color = Panel): Modifier =
    this.clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)).background(color).border(1.dp, Hairline, androidx.compose.foundation.shape.RoundedCornerShape(radius))
