package ai.vipseat.cargoconcierge.core.physics

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

data class GEstimate(
    val lateralG: Double,
    val longitudinalG: Double,
    val speedMps: Double,
) {
    val totalG: Double get() = hypot(lateralG, longitudinalG)
    fun ratioTo(limitG: Double): Double = if (limitG <= 0.0) 0.0 else totalG / limitG
}

/**
 * Estimates the acceleration the cargo feels from signals every car exposes
 * (speed, steering-wheel angle, brake pedal) — no IMU required.
 *
 * Lateral (kinematic bicycle model):
 *   δ_road = δ_wheel / steeringRatio                     (road-wheel angle, rad)
 *   κ      = tan(δ_road) / L                              (path curvature, 1/m; L = wheelbase)
 *   a_lat  = v² · κ                                       (m/s²)
 * With L = 2.9 m and ratio 15: 30 km/h and 90° on the wheel → δ_road = 6° → κ = 0.0362 → 0.26 g.
 * The kinematic model ignores tyre slip / understeer, so at higher speed it slightly
 * over-estimates — acceptable for a conservative cargo guard.
 *
 * Longitudinal:
 *   a_long = dv/dt smoothed with an EMA (alpha = [smoothing]).
 *   If the source reports brake-pedal travel (0..100 %), braking is refined with
 *   a_brake = −(pct/100) · [maxBrakeDecelMps2] (full pedal ≈ 0.8 g) and the larger magnitude wins.
 *   A boolean-only brake signal (Pleos SDK 2.3.2) only attributes the sign; dv/dt stays primary.
 */
class GEstimator(
    val wheelbaseM: Double = DEFAULT_WHEELBASE_M,
    val steeringRatio: Double = DEFAULT_STEERING_RATIO,
    val smoothing: Double = 0.5,
    val maxBrakeDecelMps2: Double = 8.0,
) {
    private var lastSpeed: Double? = null
    private var lastTimestampMs: Long? = null
    private var emaLong = 0.0

    fun lateralG(speedMps: Double, steeringWheelDeg: Double): Double {
        val roadWheelRad = Math.toRadians(steeringWheelDeg / steeringRatio)
        val curvature = tan(roadWheelRad) / wheelbaseM
        return abs(speedMps * speedMps * curvature) / G
    }

    fun update(
        speedMps: Double,
        steeringWheelDeg: Double?,
        brakePressed: Boolean?,
        brakePercent: Double?,
        timestampMs: Long,
    ): GEstimate {
        val speed = speedMps.coerceAtLeast(0.0)
        var aLong = 0.0
        val ls = lastSpeed
        val lt = lastTimestampMs
        if (ls != null && lt != null) {
            val dt = (timestampMs - lt) / 1000.0
            when {
                dt > MAX_GAP_S -> emaLong = 0.0 // stale sample: restart smoothing
                dt >= MIN_DT_S -> {
                    val raw = (speed - ls) / dt
                    emaLong = smoothing * raw + (1 - smoothing) * emaLong
                }
            }
            aLong = emaLong
        }
        lastSpeed = speed
        lastTimestampMs = timestampMs

        if (brakePressed == true && brakePercent != null && brakePercent > 0.0) {
            val aBrake = -(brakePercent.coerceIn(0.0, 100.0) / 100.0) * maxBrakeDecelMps2
            if (abs(aBrake) > abs(aLong)) aLong = aBrake
        }
        val lat = if (steeringWheelDeg != null) lateralG(speed, steeringWheelDeg) else 0.0
        return GEstimate(lateralG = lat, longitudinalG = aLong / G, speedMps = speed)
    }

    fun reset() {
        lastSpeed = null
        lastTimestampMs = null
        emaLong = 0.0
    }

    companion object {
        const val G = 9.80665
        /** 그랜저 GN7 wheelbase 2,895 mm (manufacturer spec) — swapped per model when the car reports one. */
        const val DEFAULT_WHEELBASE_M = 2.9
        /** Overall steering ratio: passenger cars 12–20:1, sedans typically 13–16:1; 15 is the midpoint. Not published per model → 10/28 question. */
        const val DEFAULT_STEERING_RATIO = 15.0
        /**
         * Radius assumed for a "sharp" turn when the navigation app gives no geometry:
         * 도로의 구조·시설 기준에 관한 규칙 제19조 — minimum horizontal-curve radius for design speed 30 km/h is 30 m.
         */
        const val SHARP_CURVE_RADIUS_M = 30.0
        private const val MIN_DT_S = 0.05
        private const val MAX_GAP_S = 5.0

        /**
         * Speed at which a curve of [radiusM] stays within [limitG]: v = sqrt(a · R).
         * Rounded to the nearest 5 km/h so it can be spoken naturally ("조금 줄여 부드럽게").
         */
        fun recommendedKph(limitG: Double, radiusM: Double = SHARP_CURVE_RADIUS_M): Int {
            val v = sqrt(limitG * G * radiusM) * 3.6
            return ((v / 5.0).roundToInt() * 5).coerceAtLeast(10)
        }
    }
}
