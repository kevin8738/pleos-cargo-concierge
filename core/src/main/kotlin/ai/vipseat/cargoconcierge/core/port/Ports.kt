package ai.vipseat.cargoconcierge.core.port

import ai.vipseat.cargoconcierge.core.model.AirflowMode
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import kotlinx.coroutines.flow.Flow

enum class CabinFeature { TEMPERATURE, FAN, AIRFLOW, SEAT_HEATER, SEAT_VENT, REAR_TEMPERATURE }

enum class FeatureStatus {
    APPLIED,
    UNCHANGED,
    UNSUPPORTED,
    PERMISSION_DENIED,
    BLOCKED_BY_OCCUPANCY,
    SKIPPED_NO_CONSENT,
    DRIVING_RESTRICTED,
    FAILED,
}

data class FeatureOutcome(val feature: CabinFeature, val status: FeatureStatus, val detail: String = "") {
    val ok: Boolean get() = status == FeatureStatus.APPLIED || status == FeatureStatus.UNCHANGED
}

data class CabinApplyResult(val outcomes: List<FeatureOutcome>) {
    val applied: List<FeatureOutcome> get() = outcomes.filter { it.status == FeatureStatus.APPLIED }
    val problems: List<FeatureOutcome> get() = outcomes.filter { !it.ok }
    fun status(feature: CabinFeature): FeatureStatus? = outcomes.firstOrNull { it.feature == feature }?.status

    companion object {
        val EMPTY = CabinApplyResult(emptyList())

        /** Every feature [plan] asks for, reported as unsupported — e.g. a trunk, which has no HVAC zone or seat. */
        fun unsupported(plan: CabinPlan, detail: String): CabinApplyResult = CabinApplyResult(buildList {
            if (plan.temperatureC != null) add(FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.UNSUPPORTED, detail))
            if (plan.fanLevel != null) add(FeatureOutcome(CabinFeature.FAN, FeatureStatus.UNSUPPORTED, detail))
            if (plan.airflow != AirflowMode.UNCHANGED) add(FeatureOutcome(CabinFeature.AIRFLOW, FeatureStatus.UNSUPPORTED, detail))
            if (plan.seatHeaterLevel != null) add(FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.UNSUPPORTED, detail))
            if (plan.seatVentLevel != null) add(FeatureOutcome(CabinFeature.SEAT_VENT, FeatureStatus.UNSUPPORTED, detail))
            if (plan.rearZoneTemperatureC != null) add(FeatureOutcome(CabinFeature.REAR_TEMPERATURE, FeatureStatus.UNSUPPORTED, detail))
        })
    }
}

data class ApplyConsent(val emptySeatHeaterAllowed: Boolean)

data class CabinCapabilities(val supported: Map<CabinFeature, Boolean>, val detail: String) {
    fun supports(feature: CabinFeature): Boolean = supported[feature] == true
}

/** The only thing the app knows about a car. Implemented by Mock, AOSP CarPropertyManager and Pleos. */
interface VehiclePort {
    val name: String
    val signals: Flow<VehicleSignal>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun capabilities(): CabinCapabilities
    /**
     * Applies [plan] to the zone and seat of [spot], remembering what it changed for [restoreCabin].
     * Adapters report what the car cannot do for that spot (shared zone, no rear heater, trunk) as outcomes.
     */
    suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot = CargoSpot.FRONT_PASSENGER): CabinApplyResult
    suspend fun restoreCabin(): CabinApplyResult
}

interface NaviPort {
    val name: String
    val events: Flow<NaviEvent>
    suspend fun connect()
    suspend fun disconnect()
}

interface SpeechPort {
    val name: String
    val available: Boolean
    suspend fun speak(text: String, priority: Boolean = false)
    fun stop()
}

interface VoiceInputPort {
    val name: String
    val available: Boolean
    /** Listens for one utterance and returns the recognised text (null on timeout / error). */
    suspend fun listenOnce(timeoutMs: Long = 6_000): String?
}

enum class ChimeKind { SOFT, ALERT, SUCCESS }

interface ChimePort {
    fun play(kind: ChimeKind)
}
