package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.itemColor
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Home: VIP card + demo on the left, the cabin map (pick the VIP's spot) on the right, the event log along the bottom. */
@Composable
fun HomeScreen(ui: UiState, vm: CargoViewModel) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Header(ui, vm)
                HeroCard(ui, vm)
                DemoPanel(ui, vm)
            }
            CabinMapPanel(ui, Modifier.weight(1f).fillMaxHeight(), onSelect = vm::selectSpot)
        }
        EventLogStrip(ui, Modifier.fillMaxWidth().height(150.dp))
    }
}

@Composable
fun Header(ui: UiState, vm: CargoViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(Modifier.weight(1f))
        if (ui.cabinHistoryAvailable) IconPill(R.drawable.ic_restore, "이전 실행 기록 보기", onClick = { vm.showCabinHistory(true) })
        IconPill(R.drawable.ic_sliders, "설정", onClick = { vm.toggleSettings(true) })
    }
}

/** Hero: the one question + illustration on top, the two ways to answer it below, presets as glyph chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(ui: UiState, vm: CargoViewModel) {
    Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sparkle(22.dp)
                Text("오늘의 VIP · ${ui.spot.label}", style = MaterialTheme.typography.labelLarge, color = Champagne)
            }
            Text("${ui.spot.label}에 무엇을 실으셨나요?", style = MaterialTheme.typography.headlineLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "이미 실었다면 아래 VIP로 바로 시작하세요.\n자리를 고르거나 “조수석에 케이크 실었어”라고 말해요.",
                    style = MaterialTheme.typography.bodyLarge, color = MistDim, modifier = Modifier.weight(1f),
                )
                HeroIllustration(Modifier.width(380.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton("프리셋 고르기", onClick = vm::openOffer, modifier = Modifier.weight(1f), icon = R.drawable.ic_sparkle)
                BigButton(
                    if (ui.listening) "듣는 중…" else "말하기", onClick = vm::startListening, modifier = Modifier.weight(1f),
                    tonal = true, enabled = !ui.listening, icon = R.drawable.ic_mic,
                )
            }
            ui.cabinNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MistDim) }
            if (ui.cabinRestorePending) {
                Text(ui.cabinNotice ?: "복원 상태 확인 필요", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                IconPill(R.drawable.ic_restore, "P 정차 후 복원 다시 확인", onClick = vm::retryRestore)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (ui.recent.isEmpty()) "추천 VIP" else "최근 VIP", style = MaterialTheme.typography.labelLarge, color = MistDim, modifier = Modifier.padding(top = 10.dp, end = 4.dp))
                (if (ui.recent.isEmpty()) CargoPreset.SUGGESTIONS else ui.recent.take(6)).forEach { p ->
                    Chip("${p.displayName} ${p.limitG}G", color = itemColor(p.kind), leading = { PresetGlyph(p, 24.dp) }, modifier = Modifier.clickable { vm.selectPreset(p) })
                }
            }
        }
    }
}
