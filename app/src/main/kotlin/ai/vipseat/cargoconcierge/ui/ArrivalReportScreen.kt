package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.Coral
import ai.vipseat.cargoconcierge.ui.theme.Leaf
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Requirement 5: arrival reminder + trip report (max G, avg temp, coaching count). */
@Composable
fun ArrivalReportScreen(ui: UiState, state: CargoState.Arrived, vm: CargoViewModel) {
    val r = state.report
    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        LineIcon(R.drawable.ic_flag, tint = Champagne, size = 40.dp)
                        Column {
                            Text("${r.spot.label} ${r.preset.arrivalNoun} 챙기세요", style = MaterialTheme.typography.displayMedium, color = Mint)
                            Text("${state.reason} · ${r.verdict}", style = MaterialTheme.typography.titleLarge, color = MistDim)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    RouteArt(Modifier.padding(horizontal = 8.dp))
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PresetArt(r.preset, 150.dp)
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile("최대 추정 G · 기준 ${CargoStateMachine.formatG(r.preset.limitG)}G", if (r.gSamples == 0) "—" else "${CargoStateMachine.formatG(r.maxG)}G", R.drawable.ic_speed, Modifier.weight(1f), if (r.gSamples == 0) MistDim else if (r.withinLimit) Leaf else Coral)
                                StatTile("평균 설정 온도", r.avgTempC?.let { String.format(java.util.Locale.US, "%.1f℃", it) } ?: "—", R.drawable.ic_thermo, Modifier.weight(1f))
                                StatTile("이동 시간", formatDuration(r.durationMs), R.drawable.ic_clock, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile("음성 요청", "${r.coachingCount}회", R.drawable.ic_wave, Modifier.weight(1f))
                                StatTile("효과음 요청", "${r.warnCount}회", R.drawable.ic_bell, Modifier.weight(1f))
                                StatTile("추정 기준", if (r.gSamples == 0) "기록 없음" else if (r.withinLimit) "이내" else "초과", R.drawable.ic_shield, Modifier.weight(1f), if (r.gSamples == 0) MistDim else if (r.withinLimit) Leaf else Coral)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineIcon(R.drawable.ic_restore, tint = Mint, size = 24.dp)
                        Text(ui.cabinNotice ?: "캐빈 복원 결과를 확인하고 있어요.", style = MaterialTheme.typography.bodyLarge, color = MistDim)
                    }
                    Text("설정값 복원 결과예요. 실제 실내·짐 온도와 냉방 효과는 확인하지 않아요.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                    Spacer(Modifier.height(24.dp))
                    if (ui.cabinRestorePending) {
                        Text(ui.cabinNotice ?: "복원 상태 확인 필요", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                        IconPill(R.drawable.ic_restore, "P 정차 후 복원 다시 확인", onClick = vm::retryRestore)
                    }
                    BigButton("잠시 정차했어요 · 같은 VIP 다시 시작", onClick = vm::continueWithSameVip, modifier = Modifier.fillMaxWidth(), tonal = true, icon = R.drawable.ic_restore)
                    Text("새 이동으로 기록합니다. 적재물 상태와 차량 설정을 확인해 주세요.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                    Spacer(Modifier.height(12.dp))
                    BigButton("확인", onClick = vm::acknowledgeReport, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_check)
                }
            }
        }
        StatusColumn(ui, Modifier.weight(1f))
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "${s}초" else "${s / 60}분 ${s % 60}초"
}
