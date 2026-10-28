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

/** Normalised vehicle events. Every adapter (mock, AOSP, Pleos) emits exactly these. */
sealed interface VehicleSignal {
    data class GearChanged(val gear: Gear) : VehicleSignal
    /** Vehicle speed in m/s (never negative). */
    data class Speed(val mps: Double) : VehicleSignal
    /** Steering *wheel* angle in degrees (sign = direction; magnitude is what matters). */
    data class SteeringAngle(val degrees: Double) : VehicleSignal
    /** Brake pedal; [percent] is optional (0..100) and only some sources provide it. */
    data class BrakePedal(val pressed: Boolean, val percent: Double? = null) : VehicleSignal
    data class Door(val id: DoorId, val open: Boolean) : VehicleSignal
    /** Occupant classification for one seat; the one-argument form is the front passenger seat. */
    data class SeatOccupancy(val seat: CargoSpot, val occupied: Boolean?) : VehicleSignal {
        constructor(passengerOccupied: Boolean?) : this(CargoSpot.FRONT_PASSENGER, passengerOccupied)
    }
    data class CabinTemperature(val zone: CabinZone, val celsius: Double) : VehicleSignal
    data class DrivingModeChanged(val mode: DrivingMode) : VehicleSignal
    data class SourceStatus(val connected: Boolean, val detail: String) : VehicleSignal
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
) {
    fun apply(signal: VehicleSignal): VehicleSnapshot = when (signal) {
        is VehicleSignal.GearChanged -> copy(gear = signal.gear)
        is VehicleSignal.Speed -> copy(speedMps = signal.mps.coerceAtLeast(0.0))
        is VehicleSignal.SteeringAngle -> copy(steeringDeg = signal.degrees)
        is VehicleSignal.BrakePedal -> copy(brakePressed = signal.pressed, brakePercent = signal.percent)
        is VehicleSignal.Door -> copy(doors = doors + (signal.id to signal.open))
        is VehicleSignal.SeatOccupancy -> copy(occupancy = occupancy + (signal.seat to signal.occupied))
        is VehicleSignal.CabinTemperature ->
            if (signal.zone == CabinZone.FRONT_PASSENGER) copy(passengerTempC = signal.celsius) else this
        is VehicleSignal.DrivingModeChanged -> copy(drivingMode = signal.mode)
        is VehicleSignal.SourceStatus -> copy(connected = signal.connected, sourceDetail = signal.detail)
    }

    val passengerOccupied: Boolean? get() = occupancy[CargoSpot.FRONT_PASSENGER]

    fun occupied(spot: CargoSpot): Boolean = occupancy[spot] == true

    /** Unknown speed counts as stationary: a parked car with no speed feed must still trigger. */
    val isStationary: Boolean get() = (speedMps ?: 0.0) < STATIONARY_MPS

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
