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
import ai.vipseat.cargoconcierge.runtime.CargoSession
import ai.vipseat.cargoconcierge.runtime.GuardForegroundService
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import android.app.Application

/** Hand-rolled DI: one object graph per process, built lazily by [ai.vipseat.cargoconcierge.CargoApp]. */
class AppGraph(private val app: Application) {
    val settings = SettingsStore(app)
    val cabinHistory = ai.vipseat.cargoconcierge.platform.CabinHistoryStore(app).recorder
    /** VIPs the user named or picked before — shown first on the sheet, consulted first by the voice parser. */
    val recents = RecentVipStore(app)
    val mockVehicle = MockVehicle()
    val mockNavi = MockNavi()
    private val sources: Sources = FlavorSources.create(app, mockVehicle, mockNavi)

    val vehicle: VehiclePort get() = sources.vehicle
    val navi: NaviPort get() = sources.navi
    val speech: SpeechPort get() = sources.speech
    val voice: VoiceInputPort get() = sources.voice
    val runtimePermissions: List<String> get() = sources.runtimePermissions +
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    val sourceDescription: String get() = sources.description

    val chime: ChimePort = ChimePlayer { settings.settings.value.chimeEnabled }
    val uxMonitor = UxRestrictionsMonitor(app)
    val demoBridge = DemoBridge(mockVehicle, mockNavi)
    val session: CargoSession by lazy { CargoSession(this) }

    fun locationAccessAvailable(): Boolean = runCatching { sources.locationAccessAvailable() }.getOrDefault(false)
    fun setLocationAccessAllowed(allowed: Boolean) {
        runCatching { sources.setLocationAccessAllowed(allowed) }
    }

    fun startGuardService(requestId: Long): Boolean = runCatching {
        ContextCompat.startForegroundService(app, Intent(app, GuardForegroundService::class.java)
            .putExtra(GuardForegroundService.REQUEST_ID, requestId))
        true
    }.getOrDefault(false)

    fun stopGuardService() {
        app.stopService(Intent(app, GuardForegroundService::class.java))
    }
}
