package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.LocationSpeedEstimator
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Speed feed for the Pleos flavor. Vehicle SDK 2.3.2 has no speed API, so we differentiate
 * FusedLocation fixes (FusedLocation-1.1.3: isSupported / initialize / registerFusedLocationCallback;
 * LocationData has lat/lon/altitude/heading/confidence/timestampMillis — no speed field).
 * Requires the runtime permission pleos.car.permission.FUSED_LOCATION.
 */
class PleosLocationSpeed(private val context: Context) {
    private val _signals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 64)
    val signals: SharedFlow<VehicleSignal> = _signals
    private val estimator = LocationSpeedEstimator()
    private var fused: FusedLocation? = null

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val l = location ?: return
            estimator.update(l.latitude, l.longitude, l.timestampMillis)?.let { _signals.tryEmit(VehicleSignal.Speed(it)) }
        }
        override fun onError(exception: Exception) {
            Log.w(TAG, "fused location error: ${exception.message}")
        }
    }

    /** Returns a short status string for the source detail line. */
    fun start(): String = try {
        val f = FusedLocation(context.applicationContext)
        if (!f.isSupported()) "fusedlocation-unsupported" else {
            f.initialize()
            f.registerFusedLocationCallback(listener)
            fused = f
            "fusedlocation-derived"
        }
    } catch (e: Exception) {
        Log.w(TAG, "FusedLocation unavailable: ${e.message}")
        "fusedlocation-error:${e.javaClass.simpleName}"
    }

    fun stop() {
        fused?.let { f ->
            runCatching { f.unregisterFusedLocationCallback(listener) }
            runCatching { f.release() }
        }
        fused = null
        estimator.reset()
    }

    private companion object { const val TAG = "PleosLocationSpeed" }
}
