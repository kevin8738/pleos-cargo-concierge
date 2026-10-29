package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.vm.CargoViewModel
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import ai.vipseat.cargoconcierge.ui.theme.Ink
import ai.vipseat.cargoconcierge.ui.theme.InkSoft

/**
 * Single routing point. Distraction rule first: while driving (our gear/speed derivation OR the
 * platform's UX restrictions) nothing but the static badge is composed — no lists, no buttons.
 */
@Composable
fun CargoRoute(vm: CargoViewModel) {
    val ui: UiState by vm.ui.collectAsState()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(InkSoft, Ink)))) {
        when {
            !ui.interactive -> DrivingBadgeScreen(ui)
            ui.showCabinHistory && ui.cabinHistoryAvailable -> CabinHistoryScreen(ui, onClose = { vm.showCabinHistory(false) })
            ui.showSettings -> SettingsScreen(ui, vm)
            else -> when (val s = ui.state) {
                is CargoState.Idle -> HomeScreen(ui, vm)
                is CargoState.Offering -> PresetSheetScreen(ui, s, vm)
                is CargoState.Guarding -> LoadedScreen(ui, s, vm)
                is CargoState.Arrived -> ArrivalReportScreen(ui, s, vm)
            }
        }
    }
}
