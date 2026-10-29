package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.LocationSpeedEstimator
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Speed feed for the Pleos flavor. Vehicle SDK 2.3.2 has no speed API, so we differentiate
 * FusedLocation fixes (FusedLocation-1.1.3: isSupported / initialize / registerFusedLocationCallback;
 * LocationData has lat/lon/altitude/heading/confidence/timestampMillis — no speed field).
 * Requires a caller-owned visible/location-FGS lease, standard location consent, location ON,
 * and pleos.car.permission.FUSED_LOCATION. It starts disabled and never manufactures zero speed.
 */
class PleosLocationSpeed(
    private val context: Context,
    private val accessAvailable: () -> Boolean = { false },
    private val onInvalidated: () -> Unit = {},
) {
    private data class Observation(val signal: VehicleSignal.Speed, val generation: Long)
    private val observations = MutableSharedFlow<Observation>(extraBufferCapacity = 64)
    val signals: Flow<VehicleSignal> = observations.mapNotNull { observation ->
        if (!hasAccess()) null else synchronized(lock) {
            observation.signal.takeIf { subscription?.generation == observation.generation && allowed }
        }
    }
    private val lock = Any()
    private val estimator = LocationSpeedEstimator()
    private class Subscription(val fused: FusedLocation, val listener: FusedLocationListener, val generation: Long) {
        // The start caller owns cleanup until registration finishes; stop never races release
        // against initialize/register. Late callbacks are rejected immediately by identity.
        var ready = false
    }
    private var subscription: Subscription? = null
    private var generation = 0L
    private var allowed = false
    private var lastFixTimestamp: Long? = null
    private data class SpeedReading(val mps: Double, val receivedAtMs: Long)
    private var latestSpeed: SpeedReading? = null

    private fun listener(forGeneration: Long) = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (!hasAccess()) return
            val l = location ?: return
            synchronized(lock) {
                if (!allowed || subscription?.generation != forGeneration || subscription?.ready != true) return
                if (!l.latitude.isFinite() || !l.longitude.isFinite() || l.latitude !in -90.0..90.0 || l.longitude !in -180.0..180.0) {
                    clearSpeed()
                    onInvalidated()
                    return
                }
                val previous = lastFixTimestamp
                // Duplicate/backward and too-close fixes cannot make an old speed estimate fresh.
                if (previous != null && l.timestampMillis - previous < MIN_FIX_INTERVAL_MS) return
                if (previous != null && l.timestampMillis - previous > MAX_FIX_GAP_MS) {
                    clearSpeed()
                    onInvalidated()
                }
                lastFixTimestamp = l.timestampMillis
                estimator.update(l.latitude, l.longitude, l.timestampMillis)?.takeIf { it.isFinite() && it >= 0 }?.let {
                    latestSpeed = SpeedReading(it, SystemClock.elapsedRealtime())
                    observations.tryEmit(Observation(VehicleSignal.Speed(it), forGeneration))
                }
            }
        }
        override fun onError(exception: Exception) {
            synchronized(lock) {
                if (subscription?.generation != forGeneration) return
                clearSpeed()
                onInvalidated()
            }
            // SDK exception text can include a fix; never log coordinates.
            Log.w(TAG, "fused location error: ${exception.javaClass.simpleName}")
        }
    }

    fun freshSpeedMps(maxAgeMs: Long): Double? {
        if (!hasAccess()) return null
        return synchronized(lock) {
            latestSpeed?.takeIf { allowed && SystemClock.elapsedRealtime() - it.receivedAtMs in 0..maxAgeMs }?.mps
        }
    }

    /** Rechecks revocation even when no new fixes arrive. Revocation requires a new caller lease. */
    fun hasAccess(): Boolean {
        val isAllowed = synchronized(lock) { allowed }
        if (!isAllowed) return false
        if (runCatching(accessAvailable).getOrDefault(false)) return true
        stop()
        return false
    }

    fun setAccessAllowed(value: Boolean) {
        if (!value) stop() else synchronized(lock) { allowed = true }
    }

    /** Idempotent; the vehicle adapter calls this only after its SDK connection is ready. */
    fun start(): String {
        if (!hasAccess()) return "fusedlocation-disabled"
        val next = synchronized(lock) {
            if (!allowed) return "fusedlocation-disabled"
            subscription?.let { return if (it.ready) "fusedlocation-derived" else "fusedlocation-starting" }
            generation += 1
            try {
                Subscription(FusedLocation(context.applicationContext), listener(generation), generation).also { subscription = it }
            } catch (e: Exception) {
                Log.w(TAG, "FusedLocation unavailable: ${e.javaClass.simpleName}")
                return "fusedlocation-error:${e.javaClass.simpleName}"
            }
        }
        return try {
            if (!next.fused.isSupported()) {
                abandon(next)
                "fusedlocation-unsupported"
            } else {
                next.fused.initialize()
                // A stop during initialization invalidates the ticket without releasing an SDK
                // object still in use by this start call.
                val mayRegister = hasAccess() && synchronized(lock) { subscription === next }
                if (!mayRegister) {
                    abandon(next)
                    "fusedlocation-disabled"
                } else {
                    next.fused.registerFusedLocationCallback(next.listener)
                    val keep = hasAccess() && synchronized(lock) {
                        (subscription === next && allowed).also { if (it) next.ready = true }
                    }
                    if (keep) {
                        Log.i(TAG, "location speed subscription active")
                        "fusedlocation-derived"
                    } else {
                        abandon(next)
                        "fusedlocation-disabled"
                    }
                }
            }
        } catch (e: Exception) {
            abandon(next)
            Log.w(TAG, "FusedLocation unavailable: ${e.javaClass.simpleName}")
            "fusedlocation-error:${e.javaClass.simpleName}"
        }
    }

    fun stop() {
        val previous = synchronized(lock) {
            if (!allowed && subscription == null && latestSpeed == null && lastFixTimestamp == null) return
            allowed = false
            generation += 1
            val old = subscription
            subscription = null
            clearSpeed()
            onInvalidated()
            old?.takeIf { it.ready }
        }
        previous?.let {
            release(it)
            Log.i(TAG, "location speed subscription stopped")
        }
    }

    private fun abandon(value: Subscription) {
        synchronized(lock) {
            if (subscription === value) {
                subscription = null
                clearSpeed()
                onInvalidated()
            }
        }
        release(value)
    }

    private fun release(value: Subscription) {
        runCatching { value.fused.unregisterFusedLocationCallback(value.listener) }
        runCatching { value.fused.release() }
    }

    /** Caller holds lock. Clearing the anchors makes a restart require two new usable fixes. */
    private fun clearSpeed() {
        latestSpeed = null
        lastFixTimestamp = null
        estimator.reset()
    }

    private companion object {
        const val TAG = "PleosLocationSpeed"
        const val MIN_FIX_INTERVAL_MS = 200L
        const val MAX_FIX_GAP_MS = 10_000L
    }
}
