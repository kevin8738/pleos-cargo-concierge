package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.port.CabinFeature
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(ui: UiState, vm: CargoViewModel) {
    val s = ui.settings
    Column(Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LineIcon(R.drawable.ic_sliders, tint = Mint, size = 40.dp)
                Text("설정", style = MaterialTheme.typography.displayMedium)
            }
            IconPill(R.drawable.ic_close, "닫기", onClick = { vm.toggleSettings(false) })
        }
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("안전 · 동의", icon = R.drawable.ic_shield)
                Text("음식 보온 열선 · 검증 대기", style = MaterialTheme.typography.titleLarge)
                Text("음식·용기·시트의 온도 검증 전에는 열선을 켜지 않아요. 기존 허용 설정이 있어도 적용하지 않습니다.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                Divider(); Spacer(Modifier.height(16.dp))
                ToggleRow("음성 코칭 (TTS)", "추정 기준 초과·급커브·도착 안내 (주행 중 공통 횟수·간격 제한)", s.ttsEnabled) { v -> vm.updateSettings { it.copy(ttsEnabled = v) } }
                Divider(); Spacer(Modifier.height(16.dp))
                ToggleRow("경고음", "추정 기준 80 %에서 짧은 알림음 (음성과 공통 예산)", s.chimeEnabled) { v -> vm.updateSettings { it.copy(chimeEnabled = v) } }
                Divider(); Spacer(Modifier.height(16.dp))
                Text("자동 제안 억제 ${ui.mutedOfferCount}개", style = MaterialTheme.typography.titleLarge)
                Text("3회 연속 응답하지 않은 문 제안을 다시 받을 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
                IconPill(R.drawable.ic_restore, "자동 제안 다시 받기", onClick = vm::resetOfferSuppression)
                Divider(); Spacer(Modifier.height(16.dp))
                ToggleRow("데모 급커브 주입 허용", "내비가 곡률 정보를 주지 않을 때 \"100 m 앞 급커브\" 데모 이벤트를 허용", s.demoCurveInjection) { v -> vm.updateSettings { it.copy(demoCurveInjection = v) } }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("최근 VIP · 말로 등록한 것부터 먼저 떠요", icon = R.drawable.ic_clock, trailing = {
                    if (ui.recent.isNotEmpty()) IconPill(R.drawable.ic_close, "모두 지우기", onClick = vm::clearRecents)
                })
                if (ui.recent.isEmpty()) {
                    Text("아직 없어요. “조수석에 도자기 실었어”처럼 말하면 등록되고, 다음부터 카드에 먼저 보여요.", style = MaterialTheme.typography.bodyLarge, color = MistDim)
                } else {
                    ui.recent.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PresetGlyph(p, 36.dp)
                            Text(p.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(260.dp))
                            Chip(p.kind.label, color = ai.vipseat.cargoconcierge.ui.theme.itemColor(p.kind))
                            Text("추정 기준 ${p.limitG}G · ${p.tagline}", style = MaterialTheme.typography.bodyMedium, color = MistDim, modifier = Modifier.weight(1f))
                            IconPill(R.drawable.ic_close, "지우기", onClick = { vm.removeRecent(p.displayName) })
                        }
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("차량 모델 (G 추정)", icon = R.drawable.ic_steering)
                Text("a_lat = v² · tan(δ_wheel / ratio) / L  ·  L = ${s.wheelbaseM} m, ratio = ${s.steeringRatio}", style = MaterialTheme.typography.bodyLarge)
                Text("시제품 가정값 · 차종별 보정·실측 전. 적재물 손상 여부를 판정하지 않습니다.", style = MaterialTheme.typography.bodyMedium, color = MistDim)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("연결된 소스", icon = R.drawable.ic_route)
                KeyValue("차량", ui.vehicleSource, R.drawable.ic_car)
                KeyValue("내비", ui.naviSource, R.drawable.ic_nav)
                KeyValue("TTS", ui.speechSource, R.drawable.ic_wave)
                KeyValue("STT", ui.voiceSource, R.drawable.ic_mic)
                KeyValue("플레이버", ui.sourceDescription, R.drawable.ic_sliders)
                Spacer(Modifier.height(12.dp))
                SectionTitle("캐빈 제어 가능 여부", icon = R.drawable.ic_thermo)
                val caps = ui.capabilities
                if (caps == null) Text("조회 중…", color = MistDim) else {
                    CabinFeature.entries.forEach { f -> KeyValue(featureLabel(f), if (caps.supports(f)) "가능" else "불가/미확인", featureIcon(f), if (caps.supports(f)) Leaf else MistDim) }
                    Text(caps.detail, style = MaterialTheme.typography.bodyMedium, color = MistDim)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MistDim)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
