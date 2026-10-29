package ai.vipseat.cargoconcierge.core.physics

import ai.vipseat.cargoconcierge.core.model.GQualityReason
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

data class GEstimate(
    val lateralG: Double,
    val longitudinalG: Double,
    val speedMps: Double,
    val lateralAvailable: Boolean = true,
    val longitudinalAvailable: Boolean = true,
    /** True only for a new accepted dv/dt, never a cached EMA or brake-only estimate. */
    val longitudinalFresh: Boolean = false,
    val qualityReasons: Set<GQualityReason> = emptySet(),
) {
    val hasData: Boolean get() = lateralAvailable || longitudinalAvailable
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
 * The kinematic model ignores tyre slip, banking and vehicle-specific calibration. Its
 * error and delay have not been validated on a vehicle; this is an estimate, not a safety limit.
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
    private var lastSpeedSourceId: Int? = null
    private var emaLong = 0.0
    private var hasLongEstimate = false
    /** Retained across sub-minimum-interval samples until a valid new difference is available. */
    private var pendingBaselineReason: GQualityReason? = null

    fun lateralG(speedMps: Double, steeringWheelDeg: Double): Double {
        if (!speedMps.isFinite() || speedMps < 0.0 || !steeringWheelDeg.isFinite()) return 0.0
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
        sourceId: Int? = null,
    ): GEstimate {
        // Invalid samples never move the baseline; ordering and same-source minimum dt are checked below.
        if (!speedMps.isFinite() || speedMps < 0.0) {
            return GEstimate(0.0, 0.0, lastSpeed ?: 0.0, lateralAvailable = false, longitudinalAvailable = false,
                qualityReasons = setOf(GQualityReason.INVALID_SAMPLE))
        }
        val reasons = mutableSetOf<GQualityReason>()
        if (steeringWheelDeg?.isFinite() == false || brakePercent?.let { !it.isFinite() || it !in 0.0..100.0 } == true) {
            reasons += GQualityReason.INVALID_SAMPLE
        }
        // Fresh sources can disagree by a fixed offset. Their first sample starts a new dv/dt
        // baseline and EMA; steering-based lateral G still uses the current speed below.
        // Reordered samples must not reset an otherwise valid baseline, even across sources.
        if (sourceId != lastSpeedSourceId && lastTimestampMs?.let { timestampMs > it } == true) {
            reset()
            pendingBaselineReason = GQualityReason.SOURCE_HANDOFF
            reasons += GQualityReason.SOURCE_HANDOFF
        }
        val speed = speedMps
        var aLong = 0.0
        var longitudinalFresh = false
        val ls = lastSpeed
        val lt = lastTimestampMs
        if (ls != null && lt != null) {
            val dt = (timestampMs - lt) / 1000.0
            if (dt < MIN_DT_S) return GEstimate(
                lateralG = steeringWheelDeg?.let { lateralG(ls, it) } ?: 0.0,
                longitudinalG = emaLong / G,
                speedMps = ls,
                lateralAvailable = steeringWheelDeg?.isFinite() == true,
                longitudinalAvailable = hasLongEstimate,
                qualityReasons = reasons + listOfNotNull(pendingBaselineReason) +
                    if (dt < 0.0) setOf(GQualityReason.REORDERED_SAMPLE) else emptySet(),
            )
            when {
                dt > MAX_GAP_S -> {
                    emaLong = 0.0
                    hasLongEstimate = false
                    pendingBaselineReason = null
                    reasons += GQualityReason.INPUT_GAP
                }
                dt >= MIN_DT_S -> {
                    val raw = (speed - ls) / dt
                    emaLong = smoothing * raw + (1 - smoothing) * emaLong
                    hasLongEstimate = true
                    longitudinalFresh = raw.isFinite() && emaLong.isFinite()
                    if (!longitudinalFresh) reasons += GQualityReason.INVALID_SAMPLE
                    else pendingBaselineReason = null
                }
            }
            aLong = emaLong
        } else if (GQualityReason.SOURCE_HANDOFF !in reasons) {
            pendingBaselineReason = GQualityReason.INITIAL_BASELINE
            reasons += GQualityReason.INITIAL_BASELINE
        }
        lastSpeed = speed
        lastTimestampMs = timestampMs
        lastSpeedSourceId = sourceId

        val hasBrakeEstimate = brakePressed == true && brakePercent != null && brakePercent.isFinite() && brakePercent > 0.0
        if (hasBrakeEstimate) {
            val aBrake = -(brakePercent!!.coerceIn(0.0, 100.0) / 100.0) * maxBrakeDecelMps2
            if (abs(aBrake) > abs(aLong)) aLong = aBrake
        }
        val lat = if (steeringWheelDeg != null) lateralG(speed, steeringWheelDeg) else 0.0
        if (!lat.isFinite() || !aLong.isFinite()) reasons += GQualityReason.INVALID_SAMPLE
        return GEstimate(lateralG = lat, longitudinalG = aLong / G, speedMps = speed,
            lateralAvailable = steeringWheelDeg?.isFinite() == true,
            longitudinalAvailable = hasLongEstimate || hasBrakeEstimate,
            longitudinalFresh = longitudinalFresh, qualityReasons = reasons)
    }

    fun reset() {
        lastSpeed = null
        lastTimestampMs = null
        lastSpeedSourceId = null
        emaLong = 0.0
        hasLongEstimate = false
        pendingBaselineReason = null
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
