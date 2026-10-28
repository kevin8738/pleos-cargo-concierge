package ai.vipseat.cargoconcierge.core.physics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Derives speed from consecutive positions (haversine distance / Δt). Used when the vehicle
 * source has no speed property — Pleos Vehicle SDK 2.3.2 exposes gear, doors, steering and brake,
 * but not vehicle speed, so FusedLocation fixes (≈1 Hz) are the fallback speed feed.
 */
class LocationSpeedEstimator(private val smoothing: Double = 0.6) {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTs: Long? = null
    private var ema: Double? = null

    /** Returns the smoothed speed in m/s, or null until two usable fixes exist. */
    fun update(latitude: Double, longitude: Double, timestampMs: Long): Double? {
        val pLat = lastLat
        val pLon = lastLon
        val pTs = lastTs
        var result: Double? = ema
        if (pLat != null && pLon != null && pTs != null) {
            val dt = (timestampMs - pTs) / 1000.0
            if (dt >= MIN_DT_S) {
                val raw = haversineM(pLat, pLon, latitude, longitude) / dt
                val prev = ema
                ema = if (prev == null || dt > RESET_GAP_S) raw else smoothing * raw + (1 - smoothing) * prev
                result = ema
            } else {
                return ema // too close in time: keep previous estimate, do not advance anchors
            }
        }
        lastLat = latitude
        lastLon = longitude
        lastTs = timestampMs
        return result
    }

    fun reset() {
        lastLat = null; lastLon = null; lastTs = null; ema = null
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val MIN_DT_S = 0.2
        private const val RESET_GAP_S = 10.0

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
