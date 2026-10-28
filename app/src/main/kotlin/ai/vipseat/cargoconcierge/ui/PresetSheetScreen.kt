package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.Leaf
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.MintDeep
import ai.vipseat.cargoconcierge.ui.theme.Mist
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.Panel
import ai.vipseat.cargoconcierge.ui.theme.itemColor
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Requirement 1: the 10-second sheet. Row 1 = say it (any item) + the VIPs this driver used before
 * (or five suggestions on first use); row 2 = the nine protection categories for something new.
 * One tap, auto-dismiss.
 */
@Composable
fun PresetSheetScreen(ui: UiState, state: CargoState.Offering, vm: CargoViewModel) {
    val total = (state.deadlineMs - state.startedMs).coerceAtLeast(1)
    val fraction = ui.offerRemainingMs.toFloat() / total
    val firstRow = if (ui.recent.isEmpty()) CargoPreset.SUGGESTIONS else ui.recent
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sparkle(24.dp)
                    Text("오늘의 VIP는 누구인가요?", style = MaterialTheme.typography.labelLarge, color = Champagne)
                }
                Text(
                    "${state.spot.label}에 무엇을 실으셨나요?",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    "말하거나 골라 주세요 · 이전에 쓴 VIP가 먼저 보여요 · ${(ui.offerRemainingMs / 1000) + 1}초 후 자동 닫힘",
                    style = MaterialTheme.typography.titleMedium, color = MistDim,
                )
                // Where the VIP rides — the trigger door's spot by default; occupied seats stay selectable (held by the passenger).
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CargoSpot.entries.forEach { sp ->
                        val occ = ui.snapshot.occupied(sp)
                        Chip(
                            sp.short + if (occ) " · 탑승" else "",
                            color = when { sp == state.spot -> Mint; occ -> Leaf; else -> MistDim },
                            icon = if (sp == CargoSpot.TRUNK) R.drawable.ic_trunk else R.drawable.ic_seat,
                            modifier = Modifier.clickable { vm.selectSpot(sp) },
                        )
                    }
                }
            }
            IconPill(R.drawable.ic_close, "없음", onClick = vm::dismissOffer)
        }
        ProgressBar(fraction)

        SectionTitle(if (ui.recent.isEmpty()) "말로 등록 · 추천 VIP" else "말로 등록 · 최근 VIP", icon = if (ui.recent.isEmpty()) R.drawable.ic_sparkle else R.drawable.ic_clock)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            item { VoiceTile(ui.listening, onClick = vm::startListening) }
            items(firstRow, key = { it.displayName + it.kind.name }) { p -> VipTile(p, big = true) { vm.selectPreset(p) } }
        }

        SectionTitle("새 VIP · 보호 분류로 고르기", icon = R.drawable.ic_sliders)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(CargoPreset.ALL, key = { "t-" + it.kind.name }) { p -> VipTile(p, big = false) { vm.selectPreset(p) } }
        }
    }
}

/** "조수석에 도자기 실었어" — any item; unknown ones register as 일반 짐 and can be reclassified. */
@Composable
private fun VoiceTile(listening: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.width(300.dp).height(380.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Mint.copy(alpha = 0.22f), Panel)))
            .border(1.5.dp, Mint.copy(alpha = 0.55f), shape)
            .clickable(enabled = !listening, onClick = onClick).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(132.dp).height(132.dp).clip(RoundedCornerShape(50)).background(Brush.radialGradient(listOf(Mint, MintDeep))),
            contentAlignment = Alignment.Center,
        ) { LineIcon(R.drawable.ic_mic, tint = ai.vipseat.cargoconcierge.ui.theme.Ink, size = 60.dp) }
        Spacer(Modifier.height(24.dp))
        Text(if (listening) "듣는 중…" else "말로 등록", style = MaterialTheme.typography.headlineMedium, color = Mist)
        Spacer(Modifier.height(8.dp))
        Text("“트렁크에 수박 실었어”\n자리와 물건을 한 번에 · 처음 보는 물건도 돼요", style = MaterialTheme.typography.bodyLarge, color = MistDim, textAlign = TextAlign.Center)
    }
}

/** A VIP card. Big = row 1 (used before / suggested), compact = category template. */
@Composable
fun VipTile(preset: CargoPreset, big: Boolean, onClick: () -> Unit) {
    val c = itemColor(preset.kind)
    val shape = RoundedCornerShape(if (big) 28.dp else 22.dp)
    Box(
        Modifier.width(if (big) 300.dp else 200.dp).height(if (big) 380.dp else 212.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.20f), Panel)))
            .border(1.dp, c.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(if (big) 20.dp else 14.dp),
    ) {
        Chip("${preset.limitG}G", color = c, modifier = Modifier.align(Alignment.TopStart))
        if (big && preset.isCustom) Chip("내 VIP", color = Champagne, modifier = Modifier.align(Alignment.TopEnd))
        Column(Modifier.align(Alignment.Center).padding(top = if (big) 28.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PresetArt(preset, if (big) 148.dp else 84.dp)
            Spacer(Modifier.height(if (big) 20.dp else 10.dp))
            Text(preset.displayName, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(if (big) 6.dp else 2.dp))
            Text(
                if (big) preset.tagline else preset.kind.label,
                style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MistDim, textAlign = TextAlign.Center, maxLines = 2,
            )
        }
    }
}
