package ai.vipseat.cargoconcierge.di

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.port.ChimePort
import ai.vipseat.cargoconcierge.core.port.NaviPort
import ai.vipseat.cargoconcierge.core.port.SpeechPort
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import ai.vipseat.cargoconcierge.core.port.VoiceInputPort
import ai.vipseat.cargoconcierge.demo.DemoBridge
import ai.vipseat.cargoconcierge.platform.ChimePlayer
import ai.vipseat.cargoconcierge.platform.RecentVipStore
import ai.vipseat.cargoconcierge.platform.SettingsStore
import ai.vipseat.cargoconcierge.platform.UxRestrictionsMonitor
import android.app.Application

/** Hand-rolled DI: one object graph per process, built lazily by [ai.vipseat.cargoconcierge.CargoApp]. */
class AppGraph(app: Application) {
    val settings = SettingsStore(app)
    /** VIPs the user named or picked before — shown first on the sheet, consulted first by the voice parser. */
    val recents = RecentVipStore(app)
    val mockVehicle = MockVehicle()
    val mockNavi = MockNavi()
    private val sources: Sources = FlavorSources.create(app, mockVehicle, mockNavi)

    val vehicle: VehiclePort get() = sources.vehicle
    val navi: NaviPort get() = sources.navi
    val speech: SpeechPort get() = sources.speech
    val voice: VoiceInputPort get() = sources.voice
    val runtimePermissions: List<String> get() = sources.runtimePermissions
    val sourceDescription: String get() = sources.description

    val chime: ChimePort = ChimePlayer { settings.settings.value.chimeEnabled }
    val uxMonitor = UxRestrictionsMonitor(app)
    val demoBridge = DemoBridge(mockVehicle, mockNavi)
}
