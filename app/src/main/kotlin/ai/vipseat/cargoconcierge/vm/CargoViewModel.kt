package ai.vipseat.cargoconcierge.vm

import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.UiMode
import ai.vipseat.cargoconcierge.core.model.*
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.di.AppGraph
import ai.vipseat.cargoconcierge.platform.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

data class LogLine(val atMs: Long, val text: String)

data class UiState(
    val state: CargoState = CargoState.Idle,
    val uiMode: UiMode = UiMode.PARKED,
    val uxRestricted: Boolean = false,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val settings: Settings = Settings(),
    val offerRemainingMs: Long = 0,
    val log: List<LogLine> = emptyList(),
    val vehicleSource: String = "",
    val naviSource: String = "",
    val speechSource: String = "",
    val voiceSource: String = "",
    val sourceDescription: String = "",
    val capabilities: CabinCapabilities? = null,
    val navigating: Boolean = false,
    val demoRunning: Boolean = false,
    val demoCaption: String? = null,
    val demoProgress: Float = 0f,
    val listening: Boolean = false,
    val lastVoiceText: String? = null,
    val showSettings: Boolean = false,
    val showDemoPanel: Boolean = false,
    /** VIPs this driver used before, most recent first (sheet row 1, voice parser tier 1). */
    val recent: List<CargoPreset> = emptyList(),
    /** Where the VIP rides: the active session's spot, else the one picked on the cabin map. */
    val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
    val mutedOfferCount: Int = 0,
    val cabinNotice: String? = null,
    val cabinRestorePending: Boolean = false,
    val cabinHistory: ai.vipseat.cargoconcierge.core.history.CabinHistoryRecorder.View = ai.vipseat.cargoconcierge.core.history.CabinHistoryRecorder.View(),
    val cabinHistoryAvailable: Boolean = false,
    val showCabinHistory: Boolean = false,
) {
    /** The only question the UI asks: may it show more than the static badge? */
    val interactive: Boolean get() = uiMode == UiMode.PARKED && !uxRestricted
}

/** UI adapter; AppGraph owns the single running session across Activity/ViewModel recreation. */
class CargoViewModel(graph: AppGraph) : ViewModel() {
    private val session = graph.session
    val ui = session.ui

    fun selectPreset(preset: CargoPreset) = session.selectPreset(preset)
    fun dismissOffer() = session.dismissOffer()
    fun openOffer() = session.openOffer()
    fun selectSpot(spot: CargoSpot) = session.selectSpot(spot)
    fun externalOffer(reason: String) = session.externalOffer(reason)
    fun acknowledgeReport() = session.acknowledgeReport()
    fun showCabinHistory(show: Boolean) = session.showCabinHistory(show)
    fun retryRestore() = session.retryRestore()
    fun resetOfferSuppression() = session.resetOfferSuppression()
    fun continueWithSameVip() = session.continueWithSameVip()
    fun endTrip() = session.endTrip()
    fun toggleSettings(show: Boolean = !ui.value.showSettings) = session.toggleSettings(show)
    fun toggleDemoPanel(show: Boolean = !ui.value.showDemoPanel) = session.toggleDemoPanel(show)
    fun updateSettings(transform: (Settings) -> Settings) = session.updateSettings(transform)
    fun reclassify(preset: CargoPreset, kind: CargoKind) = session.reclassify(preset, kind)
    fun removeRecent(name: String) = session.removeRecent(name)
    fun clearRecents() = session.clearRecents()
    fun voiceText(text: String) = session.voiceText(text)
    fun startListening() = session.startListening()
    fun startDemo() = session.startDemo()
    fun stopDemo() = session.stopDemo()
    fun demoGear(g: Gear) = session.demoGear(g)
    fun demoSpeed(kph: Double) = session.demoSpeed(kph)
    fun demoSteering(deg: Double) = session.demoSteering(deg)
    fun demoBrake(pct: Double) = session.demoBrake(pct)
    fun demoDoor(id: DoorId, open: Boolean) = session.demoDoor(id, open)
    fun demoOccupied(v: Boolean?, spot: CargoSpot = CargoSpot.FRONT_PASSENGER) = session.demoOccupied(v, spot)
    fun demoCurve() = session.demoCurve()
    fun demoArrived() = session.demoArrived()

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CargoViewModel(graph) as T
    }
}
