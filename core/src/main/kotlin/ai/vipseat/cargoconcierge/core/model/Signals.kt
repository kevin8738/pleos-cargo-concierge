package ai.vipseat.cargoconcierge.core.model

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** Doors we care about. DRIVER=ROW_1_LEFT, PASSENGER=ROW_1_RIGHT, REAR_LEFT/RIGHT=ROW_2_*, TRUNK=REAR. */
enum class DoorId { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT, TRUNK }

/**
 * Where the VIP rides. The acceleration budget does not depend on the spot; the cabin plan is mapped
 * onto the spot's HVAC zone and seat by the vehicle adapter (a trunk has neither: guard + arrival
 * reminder only). Doors map to spots so a rear door closing offers the sheet for that rear seat.
 */
enum class CargoSpot(val label: String, val short: String, val door: DoorId?, val hasSeat: Boolean) {
    FRONT_PASSENGER("조수석", "조수석", DoorId.PASSENGER, true),
    REAR_LEFT("뒷좌석 왼쪽", "뒤 왼쪽", DoorId.REAR_LEFT, true),
    REAR_CENTER("뒷좌석 가운데", "뒤 가운데", null, true),
    REAR_RIGHT("뒷좌석 오른쪽", "뒤 오른쪽", DoorId.REAR_RIGHT, true),
    TRUNK("트렁크", "트렁크", DoorId.TRUNK, false),
    ;

    val isRear: Boolean get() = this == REAR_LEFT || this == REAR_CENTER || this == REAR_RIGHT

    companion object {
        val DEFAULT = FRONT_PASSENGER
        val SEATS: List<CargoSpot> = entries.filter { it.hasSeat }
        /** The spot a load door belongs to; null for the driver door. */
        fun forDoor(door: DoorId): CargoSpot? = entries.firstOrNull { it.door == door }
    }
}

enum class DrivingMode { NORMAL, ECO, SPORT, UNKNOWN }

/** Input origin, not a sensor-accuracy or real-vehicle certification. */
enum class MotionProvenance { UNKNOWN, SYNTHETIC }

/** Normalised vehicle events. Every adapter (mock, AOSP, Pleos) emits exactly these. */
sealed interface VehicleSignal {
    data class GearChanged(val gear: Gear) : VehicleSignal
    /**
     * Vehicle speed in m/s (never negative). [sourceId] identifies one stream within a merged
     * vehicle port; null is the single-source adapter default. Different streams must not share
     * a longitudinal derivative baseline, even when both samples are fresh.
     */
    data class Speed @JvmOverloads constructor(val mps: Double, val sourceId: Int? = null, val provenance: MotionProvenance = MotionProvenance.UNKNOWN) : VehicleSignal
    /** Steering *wheel* angle in degrees (sign = direction; magnitude is what matters). */
    data class SteeringAngle @JvmOverloads constructor(val degrees: Double, val provenance: MotionProvenance = MotionProvenance.UNKNOWN) : VehicleSignal
    /** Brake pedal; [percent] is optional (0..100) and only some sources provide it. */
    data class BrakePedal @JvmOverloads constructor(val pressed: Boolean, val percent: Double? = null, val provenance: MotionProvenance = MotionProvenance.UNKNOWN) : VehicleSignal
    data class Door(val id: DoorId, val open: Boolean) : VehicleSignal
    /** Occupant classification for one seat; the one-argument form is the front passenger seat. */
    data class SeatOccupancy(val seat: CargoSpot, val occupied: Boolean?) : VehicleSignal {
        constructor(passengerOccupied: Boolean?) : this(CargoSpot.FRONT_PASSENGER, passengerOccupied)
    }
    data class CabinTemperature(val zone: CabinZone, val celsius: Double) : VehicleSignal
    data class DrivingModeChanged(val mode: DrivingMode) : VehicleSignal
    data class SourceStatus(val connected: Boolean, val detail: String) : VehicleSignal
}

/** UNKNOWN cannot erase an established synthetic origin. Cabin authorization is independent. */
fun VehicleSignal.withMotionProvenance(provenance: MotionProvenance): VehicleSignal {
    if (provenance == MotionProvenance.UNKNOWN) return this
    return when (this) {
        is VehicleSignal.Speed -> copy(provenance = provenance)
        is VehicleSignal.SteeringAngle -> copy(provenance = provenance)
        is VehicleSignal.BrakePedal -> copy(provenance = provenance)
        else -> this
    }
}

/** Last-known value of every signal; the state machine folds signals into this. */
data class VehicleSnapshot(
    val gear: Gear = Gear.UNKNOWN,
    val speedMps: Double? = null,
    val steeringDeg: Double? = null,
    val brakePressed: Boolean? = null,
    val brakePercent: Double? = null,
    val doors: Map<DoorId, Boolean> = emptyMap(),
    /** Per-seat occupancy (null = unknown / not reported). */
    val occupancy: Map<CargoSpot, Boolean?> = emptyMap(),
    val passengerTempC: Double? = null,
    val drivingMode: DrivingMode = DrivingMode.UNKNOWN,
    val connected: Boolean = false,
    val sourceDetail: String = "",
    val speedProvenance: MotionProvenance = MotionProvenance.UNKNOWN,
    val steeringProvenance: MotionProvenance = MotionProvenance.UNKNOWN,
    val brakeProvenance: MotionProvenance = MotionProvenance.UNKNOWN,
) {
    fun apply(signal: VehicleSignal): VehicleSnapshot = when (signal) {
        is VehicleSignal.GearChanged -> copy(gear = signal.gear)
        is VehicleSignal.Speed -> copy(speedMps = signal.mps.takeIf { it.isFinite() && it >= 0.0 }, speedProvenance = signal.provenance)
        is VehicleSignal.SteeringAngle -> copy(steeringDeg = signal.degrees.takeIf { it.isFinite() }, steeringProvenance = signal.provenance)
        is VehicleSignal.BrakePedal -> copy(brakePressed = signal.pressed, brakePercent = signal.percent?.takeIf { it.isFinite() && it in 0.0..100.0 }, brakeProvenance = signal.provenance)
        is VehicleSignal.Door -> copy(doors = doors + (signal.id to signal.open))
        is VehicleSignal.SeatOccupancy -> copy(occupancy = occupancy + (signal.seat to signal.occupied))
        is VehicleSignal.CabinTemperature ->
            if (signal.zone == CabinZone.FRONT_PASSENGER) copy(passengerTempC = signal.celsius) else this
        is VehicleSignal.DrivingModeChanged -> copy(drivingMode = signal.mode)
        is VehicleSignal.SourceStatus -> if (signal.connected) copy(connected = true, sourceDetail = signal.detail)
            else copy(connected = false, sourceDetail = signal.detail, gear = Gear.UNKNOWN,
                speedMps = null, steeringDeg = null, brakePressed = null, brakePercent = null)
    }

    val passengerOccupied: Boolean? get() = occupancy[CargoSpot.FRONT_PASSENGER]

    fun occupied(spot: CargoSpot): Boolean = occupancy[spot] == true

    /** Configuration requires a known finite speed; missing signals do not prove the car is still. */
    val isStationary: Boolean get() = speedMps?.let { it.isFinite() && it >= 0.0 && it < STATIONARY_MPS } == true

    val isParkedStill: Boolean get() = gear == Gear.PARK && isStationary

    /** Moving, or in a driving gear. Gear UNKNOWN with no speed is treated as parked (usable UI). */
    val isDriving: Boolean
        get() = (speedMps ?: 0.0) >= MOVING_MPS || gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL

    fun doorOpen(id: DoorId): Boolean = doors[id] == true

    companion object {
        const val STATIONARY_MPS = 0.3
        const val MOVING_MPS = 0.5
    }
}
