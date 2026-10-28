package ai.vipseat.cargoconcierge.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mirrors the platform's own driving-restriction state (CarUxRestrictionsManager) so the UI
 * collapses to the static badge exactly when AAOS says it must — in addition to our own
 * gear/speed-derived UiMode. Safe on non-automotive builds (everything is try/catch'ed).
 */
class UxRestrictionsMonitor(private val context: Context) {
    private val _requiresDistractionOptimization = MutableStateFlow(false)
    val requiresDistractionOptimization: StateFlow<Boolean> = _requiresDistractionOptimization
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            val c = Car.createCar(context.applicationContext) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            m.registerListener { r -> _requiresDistractionOptimization.value = r.isRequiresDistractionOptimization }
            m.currentCarUxRestrictions?.let { _requiresDistractionOptimization.value = it.isRequiresDistractionOptimization }
        } catch (t: Throwable) {
            Log.w(TAG, "UX restrictions unavailable: ${t.message}")
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private companion object { const val TAG = "UxRestrictions" }
}
