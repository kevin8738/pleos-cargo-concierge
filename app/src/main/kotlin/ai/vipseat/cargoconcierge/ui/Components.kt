package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.engine.BadgeLevel
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.ui.theme.Amber
import ai.vipseat.cargoconcierge.ui.theme.Coral
import ai.vipseat.cargoconcierge.ui.theme.Hairline
import ai.vipseat.cargoconcierge.ui.theme.Ink
import ai.vipseat.cargoconcierge.ui.theme.Leaf
import ai.vipseat.cargoconcierge.ui.theme.Line
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.MintDeep
import ai.vipseat.cargoconcierge.ui.theme.Mist
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.Panel
import ai.vipseat.cargoconcierge.ui.theme.PanelHigh
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun badgeColor(level: BadgeLevel): Color = when (level) {
    BadgeLevel.OK -> Leaf
    BadgeLevel.WARN -> Amber
    BadgeLevel.OVER -> Coral
}

fun featureLabel(f: CabinFeature): String = when (f) {
    CabinFeature.TEMPERATURE -> "설정 온도"
    CabinFeature.FAN -> "풍량"
    CabinFeature.AIRFLOW -> "송풍 방향"
    CabinFeature.SEAT_HEATER -> "시트 열선"
    CabinFeature.SEAT_VENT -> "시트 통풍"
    CabinFeature.REAR_TEMPERATURE -> "뒷좌석 온도"
}

fun statusLabel(s: FeatureStatus): String = when (s) {
    FeatureStatus.APPLIED -> "적용됨"
    FeatureStatus.UNCHANGED -> "유지"
    FeatureStatus.UNSUPPORTED -> "차량 미지원"
    FeatureStatus.PERMISSION_DENIED -> "권한 없음"
    FeatureStatus.BLOCKED_BY_OCCUPANCY -> "빈 좌석 감지로 차단"
    FeatureStatus.SKIPPED_NO_CONSENT -> "설정에서 허용 필요"
    FeatureStatus.DRIVING_RESTRICTED -> "주행 중 제한"
    FeatureStatus.FAILED -> "실패"
}

fun statusColor(s: FeatureStatus): Color = when (s) {
    FeatureStatus.APPLIED, FeatureStatus.UNCHANGED -> Leaf
    FeatureStatus.BLOCKED_BY_OCCUPANCY, FeatureStatus.SKIPPED_NO_CONSENT, FeatureStatus.DRIVING_RESTRICTED -> Amber
    else -> Coral
}

@DrawableRes
fun statusIcon(s: FeatureStatus): Int = when (s) {
    FeatureStatus.APPLIED -> R.drawable.ic_check
    FeatureStatus.UNCHANGED -> R.drawable.ic_restore
    FeatureStatus.BLOCKED_BY_OCCUPANCY, FeatureStatus.SKIPPED_NO_CONSENT, FeatureStatus.DRIVING_RESTRICTED -> R.drawable.ic_bell
    else -> R.drawable.ic_close
}

@DrawableRes
fun featureIcon(f: CabinFeature): Int = when (f) {
    CabinFeature.TEMPERATURE, CabinFeature.REAR_TEMPERATURE -> R.drawable.ic_thermo
    CabinFeature.FAN, CabinFeature.AIRFLOW -> R.drawable.ic_wave
    CabinFeature.SEAT_HEATER, CabinFeature.SEAT_VENT -> R.drawable.ic_seat
}

/** Pill chip; optional leading icon or custom glyph. */
@Composable
fun Chip(text: String, color: Color = Mint, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) leading() else if (icon != null) LineIcon(icon, tint = color, size = 20.dp)
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OutcomeChip(o: FeatureOutcome) = Chip("${featureLabel(o.feature)} · ${statusLabel(o.status)}", statusColor(o.status), icon = statusIcon(o.status))

@Composable
fun SectionTitle(text: String, @DrawableRes icon: Int? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) LineIcon(icon, tint = Mint, size = 22.dp)
        Text(text, style = MaterialTheme.typography.titleSmall, color = MistDim, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** Primary (mint gradient) or tonal (hairline) action, 76 dp tall, optional leading icon. */
@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tonal: Boolean = false,
    @DrawableRes icon: Int? = null, height: Dp = 76.dp,
) {
    val shape = RoundedCornerShape(22.dp)
    val base = modifier.heightIn(min = height).clip(shape)
    val surfaced = if (tonal) base.background(Mist.copy(alpha = 0.04f)).border(1.dp, Mist.copy(alpha = 0.14f), shape)
    else base.background(Brush.horizontalGradient(listOf(Mint, MintDeep)))
    Row(
        surfaced.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (tonal) Mist else Ink
        if (icon != null) { LineIcon(icon, tint = fg, size = 26.dp); Spacer(Modifier.width(12.dp)) }
        Text(text, style = MaterialTheme.typography.titleLarge, color = fg, fontWeight = if (tonal) FontWeight.SemiBold else FontWeight.Bold)
    }
}

/** Compact icon+label pill for header actions (설정 · 닫기 · 없음). */
@Composable
fun IconPill(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(Mist.copy(alpha = 0.05f)).border(1.dp, Mist.copy(alpha = 0.14f), shape).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineIcon(icon, tint = Mist, size = 24.dp)
        Text(label, style = MaterialTheme.typography.titleMedium, color = Mist)
    }
}

/** Card: panel colour + hairline, 28 dp radius. `padding = 0.dp` for edge-to-edge art. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 28.dp, color: Color = Panel, content: @Composable () -> Unit) {
    Box(modifier.cardSurface(28.dp, color).padding(padding)) { content() }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, @DrawableRes icon: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) LineIcon(icon, tint = MistDim, size = 22.dp)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MistDim)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
    }
}

/** Report tile: icon, small label, big value. */
@Composable
fun StatTile(label: String, value: String, @DrawableRes icon: Int, modifier: Modifier = Modifier, valueColor: Color = Mist) {
    Column(modifier.cardSurface(20.dp, PanelHigh).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(icon, tint = MistDim, size = 20.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MistDim)
        }
        Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
    }
}

@Composable
fun KeyValue(label: String, value: String, @DrawableRes icon: Int? = null, valueColor: Color = Mist) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp)) { if (icon != null) LineIcon(icon, tint = MistDim, size = 20.dp) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MistDim, modifier = Modifier.width(150.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Thin rounded progress bar with a mint gradient fill. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Mint) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(PanelHigh)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f)))),
        )
    }
}

/** Tiny coloured dot, used in log/status lines. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
