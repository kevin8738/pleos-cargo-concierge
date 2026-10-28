package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
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
        val color = badgeColor(guarding.badge)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GuardBadge(guarding.preset, color, size = 360.dp, label = "${CargoStateMachine.formatG(guarding.preset.limitG)}G 가드")
            Spacer(Modifier.height(24.dp))
            Text(
                when (guarding.badge) {
                    BadgeLevel.OK -> "${guarding.preset.arrivalNoun} 편안함"
                    BadgeLevel.WARN -> "조금만 부드럽게"
                    BadgeLevel.OVER -> "${guarding.preset.arrivalNoun} 기준 초과"
                },
                style = MaterialTheme.typography.displayMedium,
                color = color,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "주행 중에는 음성으로만 안내해요",
                style = MaterialTheme.typography.titleMedium,
                color = MistDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
