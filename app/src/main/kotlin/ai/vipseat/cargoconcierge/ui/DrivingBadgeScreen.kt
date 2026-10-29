package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.engine.MotionSignalStatus
import ai.vipseat.cargoconcierge.core.engine.BadgeLevel
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Driving UI = one static badge. No animation, no numbers that tick, nothing to tap.
 * Colour + one short line is all a glance needs; the voice does the coaching.
 */
@Composable
fun DrivingBadgeScreen(ui: UiState) {
    val guarding = ui.state as? CargoState.Guarding
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (guarding == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_mark_mono), contentDescription = null, modifier = Modifier.size(96.dp).alpha(0.5f))
                Spacer(Modifier.height(20.dp))
                Text("VIP 조수석", style = MaterialTheme.typography.headlineLarge, color = MistDim)
                Spacer(Modifier.height(8.dp))
                Text("적재물 없음 · 정차 후 설정", style = MaterialTheme.typography.titleLarge, color = MistDim)
            }
            return@Box
        }
        val unavailable = guarding.motionStatus == MotionSignalStatus.STALE || guarding.motionStatus == MotionSignalStatus.DISCONNECTED
        val color = if (unavailable) MistDim else badgeColor(guarding.badge)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GuardBadge(guarding.preset, color, size = 360.dp, label = if (unavailable) "신호 확인 중" else "${CargoStateMachine.formatG(guarding.preset.limitG)}G 기준")
            Spacer(Modifier.height(24.dp))
            Text(
                if (unavailable) "주행 추정을 잠시 멈췄어요" else when (guarding.badge) {
                    BadgeLevel.OK -> "추정값이 코칭 기준 이내예요"
                    BadgeLevel.WARN -> "조금만 부드럽게"
                    BadgeLevel.OVER -> "${guarding.preset.arrivalNoun} 기준 초과"
                },
                style = MaterialTheme.typography.displayMedium,
                color = color,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (guarding.motionStatus == MotionSignalStatus.SPEED_ONLY) "조향 신호 미확인 · 가감속만 추정해요" else "추정값 안내 · 적재물 상태는 정차 후 확인해 주세요",
                style = MaterialTheme.typography.titleMedium,
                color = MistDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
