package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.PanelHigh
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.ui.theme.itemColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/** Parked + cargo loaded: what was applied to the cabin, what was not (and why), trip so far. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoadedScreen(ui: UiState, state: CargoState.Guarding, vm: CargoViewModel) {
    val p = state.preset
    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Header(ui, vm)
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PresetArt(p, 128.dp)
                        Spacer(Modifier.width(28.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Sparkle(20.dp)
                                Text("오늘의 VIP", style = MaterialTheme.typography.labelLarge, color = Champagne)
                            }
                            // FlowRow: a voice-registered VIP on an occupied rear seat carries four chips — they wrap instead of squeezing.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.displayName, style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterVertically))
                                Chip(p.kind.label, color = itemColor(p.kind), modifier = Modifier.align(Alignment.CenterVertically))
                                Chip(state.spot.label, color = Mint, icon = if (state.spot == CargoSpot.TRUNK) R.drawable.ic_trunk else R.drawable.ic_seat, modifier = Modifier.align(Alignment.CenterVertically))
                                if (p.isCustom) Chip("내 VIP", color = Champagne, icon = R.drawable.ic_sparkle, modifier = Modifier.align(Alignment.CenterVertically))
                                if (state.heldByPassenger) Chip("동승자가 들고 있어요", color = Champagne, icon = R.drawable.ic_seat, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                            Text(
                                "추정 코칭 기준 ${CargoStateMachine.formatG(p.limitG)}G · ${p.tagline}",
                                style = MaterialTheme.typography.bodyLarge, color = MistDim,
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    SectionTitle("차량 설정 확인 결과", icon = R.drawable.ic_thermo)
                    val cabin = state.cabin
                    if (cabin == null) {
                        Text("적용 중…", style = MaterialTheme.typography.bodyLarge, color = MistDim)
                    } else if (cabin.outcomes.isEmpty()) {
                        Text(
                            if (state.spot == CargoSpot.TRUNK) "트렁크는 공조 영역이 아니에요 · 관성 가드와 도착 챙김만 동작해요" else "이 분류는 공조를 바꾸지 않아요 (가드만 동작)",
                            style = MaterialTheme.typography.bodyLarge, color = MistDim,
                        )
                    } else {
                        FlowChips(cabin.outcomes)
                        Spacer(Modifier.height(10.dp))
                        Text("설정 후 조회 기록이에요. 출발 전 공조와 짐 상태를 확인해 주세요.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                        Text("냉방 효과와 짐 온도는 측정하지 않아요.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                    }
                    if (ui.cabinRestorePending) {
                        Text(ui.cabinNotice ?: "복원 상태 확인 필요", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                        IconPill(R.drawable.ic_restore, "P 정차 후 복원 다시 확인", onClick = vm::retryRestore)
                    }
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("분류 바꾸기 · 정차 중에만 · 한계와 공조가 바뀌어요", icon = R.drawable.ic_sliders)
                    ReclassifyRow(p, vm)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(2f).cardSurface(22.dp, PanelHigh).padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            LineIcon(R.drawable.ic_shield, tint = Mint, size = 28.dp)
                            Column {
                                Text("코칭 시작됨 · 설정 결과 확인", style = MaterialTheme.typography.titleLarge)
                                Text("주행 중에는 배지 하나만 보이고, 안내는 음성으로 해요", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                            }
                        }
                        BigButton("내렸어요 (종료)", onClick = vm::endTrip, modifier = Modifier.weight(1f), tonal = true, icon = R.drawable.ic_door)
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("이번 이동", icon = R.drawable.ic_route)
                    val t = state.trip
                    StatRow("최대 추정 G", "${CargoStateMachine.formatG(t.maxG)}G", badgeColor(state.badge), icon = R.drawable.ic_speed)
                    StatRow("효과음 / 음성 요청", "${t.warnCount}회 / ${t.coachingCount}회", icon = R.drawable.ic_bell)
                    StatRow("평균 설정 온도", t.avgTempC?.let { String.format(java.util.Locale.US, "%.1f℃", it) } ?: "—", icon = R.drawable.ic_thermo)
                    state.lastEstimate?.let { e ->
                        StatRow("마지막 추정 (횡 / 종)", "${CargoStateMachine.formatG(e.lateralG)} / ${CargoStateMachine.formatG(e.longitudinalG)} G", icon = R.drawable.ic_steering)
                    }
                }
            }
            DemoPanel(ui, vm)
        }
        StatusColumn(ui, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReclassifyRow(p: CargoPreset, vm: CargoViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CargoKind.entries.filter { it != p.kind }.forEach { k ->
            val t = CargoPreset.of(k)
            Chip("${k.label} ${t.limitG}G", color = itemColor(k), leading = { PresetGlyph(t, 22.dp) }, modifier = Modifier.clickable { vm.reclassify(p, k) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(outcomes: List<FeatureOutcome>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        outcomes.forEach { OutcomeChip(it) }
    }
}
