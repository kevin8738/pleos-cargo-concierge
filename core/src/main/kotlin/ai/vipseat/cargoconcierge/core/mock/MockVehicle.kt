package ai.vipseat.cargoconcierge.core.mock

import ai.vipseat.cargoconcierge.core.model.AirflowMode
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CabinZone
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.MotionProvenance
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.model.withMotionProvenance
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Scriptable fake car. Drives unit tests, the in-app demo scenario and the `adb am broadcast`
 * demo bridge. It also simulates the cabin so apply/restore can be exercised end-to-end, per spot:
 * the front passenger seat has its own zone, heater and vent; rear left/right have heaters (and a
 * rear zone only on 3-zone cars); the rear centre seat and the trunk have nothing to control.
 */
class MockVehicle : VehiclePort {
    override val name: String = "Mock vehicle"

    private val _signals = MutableSharedFlow<VehicleSignal>(replay = 0, extraBufferCapacity = 256)
    override val signals: SharedFlow<VehicleSignal> = _signals

    // --- capability / failure knobs (set before applyCabin) ---
    var supportsTemperature = true
    var supportsFan = true
    var supportsAirflow = true
    var supportsSeatHeater = true
    var supportsSeatVent = true
    var supportsRearZone = false
    var supportsRearSeatHeater = true
    /** Occupant-classification behaviour: an empty seat refuses the heater. */
    var occupancyBlocksHeater = true
    var deniedFeatures: Set<CabinFeature> = emptySet()
    var restrictWhileDriving = true

    // --- simulated cabin ---
    data class Cabin(
        val tempC: Double = 22.0,
        val fan: Int = 2,
        val airflow: AirflowMode = AirflowMode.DIRECT_FACE,
        val heater: Int = 0,
        val vent: Int = 0,
        val rearTempC: Double = 22.0,
        val rearHeaterLeft: Int = 0,
        val rearHeaterRight: Int = 0,
    )
    var cabin: Cabin = Cabin()
        private set
    private var saved: Cabin? = null

    // --- simulated vehicle state ---
    var gear: Gear = Gear.PARK; private set
    var speedMps: Double = 0.0; private set
    private val occupancy: MutableMap<CargoSpot, Boolean?> = CargoSpot.SEATS.associateWith { false }.toMutableMap()
    val passengerOccupied: Boolean? get() = occupancy[CargoSpot.FRONT_PASSENGER]
    fun occupied(spot: CargoSpot): Boolean? = occupancy[spot]

    val appliedPlans = mutableListOf<CabinPlan>()
    val appliedSpots = mutableListOf<CargoSpot>()
    var restoreCount = 0; private set
    var connected = false; private set

    override suspend fun connect() = connect(emitInitialState = true)

    /** A real-source demo bridge must not overwrite observed state with fake startup defaults. */
    suspend fun connect(emitInitialState: Boolean) {
        connected = true
        if (!emitInitialState) return
        emit(VehicleSignal.SourceStatus(true, "mock"))
        emit(VehicleSignal.GearChanged(gear))
        CargoSpot.SEATS.forEach { emit(VehicleSignal.SeatOccupancy(it, occupancy[it])) }
        emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, cabin.tempC))
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun capabilities() = CabinCapabilities(
        supported = mapOf(
            CabinFeature.TEMPERATURE to supportsTemperature,
            CabinFeature.FAN to supportsFan,
            CabinFeature.AIRFLOW to supportsAirflow,
            CabinFeature.SEAT_HEATER to supportsSeatHeater,
            CabinFeature.SEAT_VENT to supportsSeatVent,
            CabinFeature.REAR_TEMPERATURE to supportsRearZone,
        ),
        detail = "mock capabilities",
    )

    override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot): CabinApplyResult {
        appliedPlans += plan
        appliedSpots += spot
        if (spot == CargoSpot.TRUNK) return CabinApplyResult.unsupported(plan, "트렁크는 공조 영역이 아니에요")
        if (saved == null) saved = cabin
        val out = ArrayList<FeatureOutcome>()
        val seatOccupied = occupancy[spot]
        val rear = spot.isRear
        fun attempt(feature: CabinFeature, supported: Boolean, requested: Boolean, why: String = "", block: () -> Unit) {
            if (!requested) return
            val status = when {
                feature in deniedFeatures -> FeatureStatus.PERMISSION_DENIED
                !supported -> FeatureStatus.UNSUPPORTED
                restrictWhileDriving && speedMps > 0.5 && feature in SEAT_FEATURES -> FeatureStatus.DRIVING_RESTRICTED
                else -> { block(); FeatureStatus.APPLIED }
            }
            out += FeatureOutcome(feature, status, if (status == FeatureStatus.UNSUPPORTED) why else "")
        }
        plan.temperatureC?.let { t ->
            if (!rear) attempt(CabinFeature.TEMPERATURE, supportsTemperature, true) { cabin = cabin.copy(tempC = t) }
            else attempt(CabinFeature.TEMPERATURE, supportsRearZone, true, "뒷좌석 단독 존 없음") { cabin = cabin.copy(rearTempC = t) }
        }
        attempt(CabinFeature.FAN, supportsFan && !rear, plan.fanLevel != null, "뒷좌석 송풍 제어 없음") { cabin = cabin.copy(fan = plan.fanLevel!!) }
        attempt(CabinFeature.AIRFLOW, supportsAirflow && !rear, plan.airflow != AirflowMode.UNCHANGED, "뒷좌석 송풍 제어 없음") {
            cabin = cabin.copy(airflow = plan.airflow, fan = if (plan.airflow == AirflowMode.OFF) 0 else cabin.fan)
        }
        plan.seatHeaterLevel?.let { level ->
            val (supported, why) = when (spot) {
                CargoSpot.FRONT_PASSENGER -> supportsSeatHeater to ""
                CargoSpot.REAR_CENTER -> false to "가운데 좌석 열선 없음"
                else -> supportsRearSeatHeater to "뒷좌석 열선 없음"
            }
            val status = when {
                CabinFeature.SEAT_HEATER in deniedFeatures -> FeatureStatus.PERMISSION_DENIED
                !supported -> FeatureStatus.UNSUPPORTED
                restrictWhileDriving && speedMps > 0.5 -> FeatureStatus.DRIVING_RESTRICTED
                level > 0 && seatOccupied != true && !consent.emptySeatHeaterAllowed -> FeatureStatus.SKIPPED_NO_CONSENT
                level > 0 && seatOccupied == false && occupancyBlocksHeater -> FeatureStatus.BLOCKED_BY_OCCUPANCY
                else -> {
                    cabin = when (spot) {
                        CargoSpot.REAR_LEFT -> cabin.copy(rearHeaterLeft = level)
                        CargoSpot.REAR_RIGHT -> cabin.copy(rearHeaterRight = level)
                        else -> cabin.copy(heater = level)
                    }
                    FeatureStatus.APPLIED
                }
            }
            out += FeatureOutcome(CabinFeature.SEAT_HEATER, status, if (status == FeatureStatus.UNSUPPORTED) why else "")
        }
        attempt(CabinFeature.SEAT_VENT, supportsSeatVent && !rear, plan.seatVentLevel != null, "뒷좌석 통풍 없음") {
            cabin = cabin.copy(vent = plan.seatVentLevel!!)
        }
        attempt(CabinFeature.REAR_TEMPERATURE, supportsRearZone, plan.rearZoneTemperatureC != null, "후석 존 없음") {
            cabin = cabin.copy(rearTempC = plan.rearZoneTemperatureC!!)
        }
        emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, cabin.tempC))
        return CabinApplyResult(out)
    }

    override suspend fun restoreCabin(): CabinApplyResult {
        restoreCount++
        val s = saved ?: return CabinApplyResult.EMPTY
        cabin = s
        saved = null
        emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, cabin.tempC))
        return CabinApplyResult(listOf(FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED, "restored")))
    }

    // ------------------------------------------------------------ scripting API

    fun setGear(g: Gear) { gear = g; emit(VehicleSignal.GearChanged(g)) }
    fun setSpeedKph(kph: Double) { speedMps = kph / 3.6; emit(VehicleSignal.Speed(speedMps)) }
    fun setSpeedMps(mps: Double) { speedMps = mps; emit(VehicleSignal.Speed(mps)) }
    fun setSteering(deg: Double) = emit(VehicleSignal.SteeringAngle(deg))
    fun setBrake(pressed: Boolean, percent: Double? = null) = emit(VehicleSignal.BrakePedal(pressed, percent))
    fun setDoor(id: DoorId, open: Boolean) = emit(VehicleSignal.Door(id, open))
    fun setOccupied(spot: CargoSpot, occupied: Boolean?) { occupancy[spot] = occupied; emit(VehicleSignal.SeatOccupancy(spot, occupied)) }
    fun setPassengerOccupied(occupied: Boolean?) = setOccupied(CargoSpot.FRONT_PASSENGER, occupied)
    fun setCabinTemp(c: Double) { cabin = cabin.copy(tempC = c); emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, c)) }
    fun setDrivingMode(m: DrivingMode) = emit(VehicleSignal.DrivingModeChanged(m))

    fun emit(signal: VehicleSignal) { _signals.tryEmit(signal.withMotionProvenance(MotionProvenance.SYNTHETIC)) }

    companion object {
        private val SEAT_FEATURES = setOf(CabinFeature.SEAT_HEATER, CabinFeature.SEAT_VENT)
    }
}
