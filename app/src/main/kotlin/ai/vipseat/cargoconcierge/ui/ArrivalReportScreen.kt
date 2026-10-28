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
                                StatTile("최대 G · 허용 ${CargoStateMachine.formatG(r.preset.limitG)}G", "${CargoStateMachine.formatG(r.maxG)}G", R.drawable.ic_speed, Modifier.weight(1f), if (r.withinLimit) Leaf else Coral)
                                StatTile("조수석 존 평균 온도", r.avgTempC?.let { String.format(java.util.Locale.US, "%.1f℃", it) } ?: "—", R.drawable.ic_thermo, Modifier.weight(1f))
                                StatTile("이동 시간", formatDuration(r.durationMs), R.drawable.ic_clock, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile("음성 코칭", "${r.coachingCount}회", R.drawable.ic_wave, Modifier.weight(1f))
                                StatTile("경고음", "${r.warnCount}회", R.drawable.ic_bell, Modifier.weight(1f))
                                StatTile("한계 판정", if (r.withinLimit) "이내" else "초과", R.drawable.ic_shield, Modifier.weight(1f), if (r.withinLimit) Leaf else Coral)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineIcon(R.drawable.ic_restore, tint = Mint, size = 24.dp)
                        Text("공조와 시트는 출발 전 상태로 되돌렸어요.", style = MaterialTheme.typography.bodyLarge, color = MistDim)
                    }
                    Spacer(Modifier.height(24.dp))
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
