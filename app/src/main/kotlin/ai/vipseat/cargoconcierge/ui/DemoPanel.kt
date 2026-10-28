package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.PanelHigh
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Parked-only demo controls: one-button scripted scenario, manual signal buttons, and a text box
 * that feeds the same voice-intent path as STT / Gleo. Mirrors demo/demo_broadcast.sh.
 */
@Composable
fun DemoPanel(ui: UiState, vm: CargoViewModel) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("데모 · 시연 도구", icon = R.drawable.ic_play, trailing = {
                IconPill(R.drawable.ic_chevron, if (ui.showDemoPanel) "접기" else "펼치기", onClick = { vm.toggleDemoPanel() })
            })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(if (ui.demoRunning) "시나리오 중지" else "케이크 시나리오 (70초)", onClick = { if (ui.demoRunning) vm.stopDemo() else vm.startDemo() }, modifier = Modifier.weight(1f), icon = if (ui.demoRunning) R.drawable.ic_close else R.drawable.ic_play)
            }
            if (ui.demoRunning || ui.demoCaption != null) {
                Spacer(Modifier.height(12.dp))
                ProgressBar(ui.demoProgress)
                Text(ui.demoCaption ?: "", style = MaterialTheme.typography.bodyLarge, color = MistDim)
            }
            if (!ui.showDemoPanel) return@Column
            Spacer(Modifier.height(20.dp))
            SectionTitle("수동 신호")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("P", { vm.demoGear(Gear.PARK) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("D", { vm.demoGear(Gear.DRIVE) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("0 km/h", { vm.demoSpeed(0.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("30 km/h", { vm.demoSpeed(30.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("60 km/h", { vm.demoSpeed(60.0) }, tonal = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("핸들 0°", { vm.demoSteering(0.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("핸들 45°", { vm.demoSteering(45.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("핸들 100°", { vm.demoSteering(100.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("급제동", { vm.demoBrake(60.0); vm.demoSpeed(12.0) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("브레이크 해제", { vm.demoBrake(0.0) }, tonal = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("조수석 열림", { vm.demoDoor(DoorId.PASSENGER, true) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("조수석 닫힘", { vm.demoDoor(DoorId.PASSENGER, false) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("트렁크 열림/닫힘", { vm.demoDoor(DoorId.TRUNK, true); vm.demoDoor(DoorId.TRUNK, false) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("운전석 열림", { vm.demoDoor(DoorId.DRIVER, true) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("운전석 닫힘", { vm.demoDoor(DoorId.DRIVER, false) }, tonal = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("착좌 있음", { vm.demoOccupied(true) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("착좌 없음", { vm.demoOccupied(false) }, tonal = true, modifier = Modifier.weight(1f))
                BigButton("내비: 급커브 100 m", vm::demoCurve, tonal = true, modifier = Modifier.weight(1f))
                BigButton("내비: 목적지 도착", vm::demoArrived, tonal = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            SectionTitle("음성 명령 텍스트 (STT / Gleo 훅)")
            var text by remember { mutableStateOf("케이크 실었어") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.voiceText(text) }),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                BigButton("전송", { vm.voiceText(text) }, modifier = Modifier.width(140.dp))
            }
            ui.lastVoiceText?.let { Text("마지막 인식: \"$it\"", style = MaterialTheme.typography.bodyMedium, color = MistDim) }
        }
    }
}
