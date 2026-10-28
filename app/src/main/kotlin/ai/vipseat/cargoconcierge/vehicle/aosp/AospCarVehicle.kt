package ai.vipseat.cargoconcierge.vehicle.aosp

import ai.vipseat.cargoconcierge.core.model.AirflowMode
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CabinZone
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import android.car.Car
import android.car.VehicleAreaSeat
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Stock Android Automotive adapter built on the public `android.car` API (CarPropertyManager).
 * This is what reacts to `adb shell cmd car_service inject-vhal-event / set-property-value` on
 * the standard AAOS emulator. Every property is handled independently: a missing permission
 * (most car permissions are privileged for third-party apps) degrades that one feature only.
 *
 * Pleos documents its Vehicle Service as CarPropertyManager-compatible; the Pleos flavor uses the
 * typed Pleos SDK instead (see :vehicle-pleos).
 */
class AospCarVehicle private constructor(private val context: Context, private val car: Car) : VehiclePort {
    override val name: String = "AOSP CarPropertyManager"

    private val pm: CarPropertyManager? = runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
    private val _signals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 256)
    override val signals: SharedFlow<VehicleSignal> = _signals

    private val subscriptionStatus = linkedMapOf<String, String>()
    private var saved: MutableMap<Pair<Int, Int>, Any>? = null
    private val occupancy: MutableMap<CargoSpot, Boolean?> = java.util.Collections.synchronizedMap(HashMap())

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            toSignal(value)?.let { _signals.tryEmit(it) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "property error prop=0x${Integer.toHexString(propId)} zone=$zone")
        }
    }

    private fun toSignal(v: CarPropertyValue<*>): VehicleSignal? = when (v.propertyId) {
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v.value as? Float)?.let { VehicleSignal.Speed(abs(it.toDouble())) }
        VehiclePropertyIds.GEAR_SELECTION -> (v.value as? Int)?.let { VehicleSignal.GearChanged(gearOf(it)) }
        VehiclePropertyIds.PERF_STEERING_ANGLE -> (v.value as? Float)?.let { VehicleSignal.SteeringAngle(it.toDouble()) }
        VehiclePropertyIds.DOOR_POS -> doorOf(v.areaId)?.let { VehicleSignal.Door(it, ((v.value as? Int) ?: 0) > 0) }
        VehiclePropertyIds.SEAT_OCCUPANCY -> spotOfSeatArea(v.areaId)?.let { spot ->
            val occ = when (v.value as? Int) { SEAT_OCCUPIED -> true; SEAT_VACANT -> false; else -> null }
            occupancy[spot] = occ
            VehicleSignal.SeatOccupancy(spot, occ)
        }
        VehiclePropertyIds.HVAC_TEMPERATURE_SET ->
            if (v.areaId and VehicleAreaSeat.SEAT_ROW_1_RIGHT != 0) (v.value as? Float)?.let { VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, it.toDouble()) } else null
        BRAKE_PEDAL_COMPRESSION_PERCENTAGE -> (v.value as? Float)?.let { VehicleSignal.BrakePedal(it > 1f, it.toDouble()) }
        else -> null
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val manager = pm
        if (manager == null) {
            _signals.tryEmit(VehicleSignal.SourceStatus(false, "CarPropertyManager unavailable"))
            return@withContext
        }
        val wanted = listOf(
            "speed" to (VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_UI),
            "gear" to (VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE),
            "steering" to (VehiclePropertyIds.PERF_STEERING_ANGLE to CarPropertyManager.SENSOR_RATE_UI),
            "door" to (VehiclePropertyIds.DOOR_POS to CarPropertyManager.SENSOR_RATE_ONCHANGE),
            "occupancy" to (VehiclePropertyIds.SEAT_OCCUPANCY to CarPropertyManager.SENSOR_RATE_ONCHANGE),
            "hvacTemp" to (VehiclePropertyIds.HVAC_TEMPERATURE_SET to CarPropertyManager.SENSOR_RATE_ONCHANGE),
            "brakePct" to (BRAKE_PEDAL_COMPRESSION_PERCENTAGE to CarPropertyManager.SENSOR_RATE_UI),
        )
        for ((label, spec) in wanted) {
            val (prop, rate) = spec
            subscriptionStatus[label] = try {
                if (manager.getCarPropertyConfig(prop) == null) "absent"
                else if (manager.registerCallback(callback, prop, rate)) "ok" else "refused"
            } catch (e: SecurityException) { "no-permission" } catch (e: Exception) { "error:${e.javaClass.simpleName}" }
        }
        // Initial values so the snapshot is not empty before the first change event.
        runCatching { _signals.tryEmit(VehicleSignal.GearChanged(gearOf(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)))) }
        runCatching { _signals.tryEmit(VehicleSignal.Speed(abs(manager.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).toDouble()))) }
        for (door in listOf(DOOR_ROW_1_LEFT, DOOR_ROW_1_RIGHT, DOOR_ROW_2_LEFT, DOOR_ROW_2_RIGHT, DOOR_REAR)) {
            runCatching { _signals.tryEmit(VehicleSignal.Door(doorOf(door)!!, manager.getIntProperty(VehiclePropertyIds.DOOR_POS, door) > 0)) }
        }
        val detail = subscriptionStatus.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i(TAG, "subscriptions: $detail")
        _signals.tryEmit(VehicleSignal.SourceStatus(true, detail))
    }

    override suspend fun disconnect() {
        runCatching { pm?.unregisterCallback(callback) }
        runCatching { car.disconnect() }
    }

    override suspend fun capabilities(): CabinCapabilities = withContext(Dispatchers.IO) {
        val manager = pm ?: return@withContext CabinCapabilities(emptyMap(), "no CarPropertyManager")
        fun has(prop: Int) = runCatching { manager.getCarPropertyConfig(prop) != null }.getOrDefault(false)
        CabinCapabilities(
            supported = mapOf(
                CabinFeature.TEMPERATURE to has(VehiclePropertyIds.HVAC_TEMPERATURE_SET),
                CabinFeature.FAN to has(VehiclePropertyIds.HVAC_FAN_SPEED),
                CabinFeature.AIRFLOW to has(VehiclePropertyIds.HVAC_FAN_DIRECTION),
                CabinFeature.SEAT_HEATER to has(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE),
                CabinFeature.SEAT_VENT to has(VehiclePropertyIds.HVAC_SEAT_VENTILATION),
                CabinFeature.REAR_TEMPERATURE to (areaIdContaining(VehiclePropertyIds.HVAC_TEMPERATURE_SET, VehicleAreaSeat.SEAT_ROW_2_RIGHT) != null),
            ),
            detail = subscriptionStatus.entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }

    override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot): CabinApplyResult = withContext(Dispatchers.IO) {
        val manager = pm ?: return@withContext CabinApplyResult(emptyList())
        if (spot == CargoSpot.TRUNK) return@withContext CabinApplyResult.unsupported(plan, "트렁크는 공조 영역이 아니에요")
        if (saved == null) saved = mutableMapOf()
        val out = ArrayList<FeatureOutcome>()
        val seatBit = seatBitOf(spot)
        // A rear VIP must not retune a zone the driver sits in: only a zone without ROW_1 seats counts as "rear".
        fun zoneFor(prop: Int): Int? = areaIdContaining(prop, seatBit)?.takeUnless { spot.isRear && it and VehicleAreaSeat.SEAT_ROW_1_LEFT != 0 }
        val frontArea = zoneFor(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
        val fanArea = zoneFor(VehiclePropertyIds.HVAC_FAN_SPEED)
        val dirArea = zoneFor(VehiclePropertyIds.HVAC_FAN_DIRECTION)
        val seatArea = seatBit
        val seatOccupied = occupancy[spot]

        plan.temperatureC?.let { t ->
            out += setFloat(CabinFeature.TEMPERATURE, manager, VehiclePropertyIds.HVAC_TEMPERATURE_SET, frontArea, t.toFloat())
        }
        plan.fanLevel?.let { f ->
            out += setInt(CabinFeature.FAN, manager, VehiclePropertyIds.HVAC_FAN_SPEED, fanArea, f)
        }
        when (plan.airflow) {
            AirflowMode.DIRECT_FACE -> out += setInt(CabinFeature.AIRFLOW, manager, VehiclePropertyIds.HVAC_FAN_DIRECTION, dirArea, FAN_DIRECTION_FACE)
            AirflowMode.INDIRECT_FLOOR -> out += setInt(CabinFeature.AIRFLOW, manager, VehiclePropertyIds.HVAC_FAN_DIRECTION, dirArea, FAN_DIRECTION_FLOOR)
            AirflowMode.OFF -> out += setInt(CabinFeature.AIRFLOW, manager, VehiclePropertyIds.HVAC_FAN_SPEED, fanArea, 0)
            AirflowMode.UNCHANGED -> Unit
        }
        plan.seatHeaterLevel?.let { level ->
            out += if (level > 0 && seatOccupied != true && !consent.emptySeatHeaterAllowed) {
                FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.SKIPPED_NO_CONSENT, "empty seat, no consent")
            } else {
                val r = setInt(CabinFeature.SEAT_HEATER, manager, VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, seatArea, level)
                if (r.status == FeatureStatus.APPLIED && level > 0 && seatOccupied == false) {
                    val readBack = runCatching { manager.getIntProperty(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, seatArea) }.getOrNull()
                    if (readBack == 0) FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.BLOCKED_BY_OCCUPANCY, "occupant classification") else r
                } else r
            }
        }
        plan.seatVentLevel?.let { level ->
            out += setInt(CabinFeature.SEAT_VENT, manager, VehiclePropertyIds.HVAC_SEAT_VENTILATION, seatArea, level)
        }
        plan.rearZoneTemperatureC?.let { t ->
            val rearArea = areaIdContaining(VehiclePropertyIds.HVAC_TEMPERATURE_SET, VehicleAreaSeat.SEAT_ROW_2_RIGHT)
            out += if (rearArea == null || rearArea == frontArea) FeatureOutcome(CabinFeature.REAR_TEMPERATURE, FeatureStatus.UNSUPPORTED, "no separate rear zone")
            else setFloat(CabinFeature.REAR_TEMPERATURE, manager, VehiclePropertyIds.HVAC_TEMPERATURE_SET, rearArea, t.toFloat())
        }
        CabinApplyResult(out)
    }

    override suspend fun restoreCabin(): CabinApplyResult = withContext(Dispatchers.IO) {
        val manager = pm ?: return@withContext CabinApplyResult.EMPTY
        val s = saved ?: return@withContext CabinApplyResult.EMPTY
        saved = null
        val out = ArrayList<FeatureOutcome>()
        for ((key, value) in s) {
            val (prop, area) = key
            val status = try {
                when (value) {
                    is Float -> manager.setFloatProperty(prop, area, value)
                    is Int -> manager.setIntProperty(prop, area, value)
                }
                FeatureStatus.APPLIED
            } catch (e: Exception) { classify(e) }
            out += FeatureOutcome(CabinFeature.TEMPERATURE, status, "restore 0x${Integer.toHexString(prop)}")
        }
        CabinApplyResult(out)
    }

    // ------------------------------------------------------------------ helpers

    private fun setFloat(feature: CabinFeature, m: CarPropertyManager, prop: Int, area: Int?, value: Float): FeatureOutcome {
        if (area == null) return FeatureOutcome(feature, FeatureStatus.UNSUPPORTED, "no area for 0x${Integer.toHexString(prop)}")
        return try {
            saved?.putIfAbsent(prop to area, m.getFloatProperty(prop, area))
            m.setFloatProperty(prop, area, value)
            FeatureOutcome(feature, FeatureStatus.APPLIED, "$value")
        } catch (e: Exception) { FeatureOutcome(feature, classify(e), e.javaClass.simpleName) }
    }

    private fun setInt(feature: CabinFeature, m: CarPropertyManager, prop: Int, area: Int?, value: Int): FeatureOutcome {
        if (area == null) return FeatureOutcome(feature, FeatureStatus.UNSUPPORTED, "no area for 0x${Integer.toHexString(prop)}")
        return try {
            saved?.putIfAbsent(prop to area, m.getIntProperty(prop, area))
            m.setIntProperty(prop, area, value)
            FeatureOutcome(feature, FeatureStatus.APPLIED, "$value")
        } catch (e: Exception) { FeatureOutcome(feature, classify(e), e.javaClass.simpleName) }
    }

    /** HVAC zones are bitmasks (e.g. ROW_1_RIGHT|ROW_2_RIGHT); find the configured area that contains [seatBit]. */
    private fun areaIdContaining(prop: Int, seatBit: Int): Int? = runCatching {
        pm?.getCarPropertyConfig(prop)?.areaIds?.firstOrNull { it and seatBit != 0 }
    }.getOrNull()

    private fun classify(e: Exception): FeatureStatus = when (e) {
        is SecurityException -> FeatureStatus.PERMISSION_DENIED
        is IllegalArgumentException -> FeatureStatus.UNSUPPORTED
        else -> FeatureStatus.FAILED
    }

    private fun gearOf(v: Int): Gear = when (v) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // DRIVE or any forward gear
    }

    private fun doorOf(areaId: Int): DoorId? = when {
        areaId and DOOR_ROW_1_LEFT != 0 -> DoorId.DRIVER
        areaId and DOOR_ROW_1_RIGHT != 0 -> DoorId.PASSENGER
        areaId and DOOR_ROW_2_LEFT != 0 -> DoorId.REAR_LEFT
        areaId and DOOR_ROW_2_RIGHT != 0 -> DoorId.REAR_RIGHT
        areaId and DOOR_REAR != 0 -> DoorId.TRUNK
        else -> null
    }

    private fun seatBitOf(spot: CargoSpot): Int = when (spot) {
        CargoSpot.FRONT_PASSENGER -> VehicleAreaSeat.SEAT_ROW_1_RIGHT
        CargoSpot.REAR_LEFT -> VehicleAreaSeat.SEAT_ROW_2_LEFT
        CargoSpot.REAR_CENTER -> VehicleAreaSeat.SEAT_ROW_2_CENTER
        CargoSpot.REAR_RIGHT -> VehicleAreaSeat.SEAT_ROW_2_RIGHT
        CargoSpot.TRUNK -> 0
    }

    private fun spotOfSeatArea(areaId: Int): CargoSpot? = when {
        areaId and VehicleAreaSeat.SEAT_ROW_1_RIGHT != 0 -> CargoSpot.FRONT_PASSENGER
        areaId and VehicleAreaSeat.SEAT_ROW_2_LEFT != 0 -> CargoSpot.REAR_LEFT
        areaId and VehicleAreaSeat.SEAT_ROW_2_CENTER != 0 -> CargoSpot.REAR_CENTER
        areaId and VehicleAreaSeat.SEAT_ROW_2_RIGHT != 0 -> CargoSpot.REAR_RIGHT
        else -> null
    }

    companion object {
        private const val TAG = "AospCarVehicle"
        // android.car.VehicleAreaDoor is @hide in the public SDK; values from hardware/interfaces/automotive/vehicle.
        const val DOOR_ROW_1_LEFT = 0x1
        const val DOOR_ROW_1_RIGHT = 0x4
        const val DOOR_ROW_2_LEFT = 0x10
        const val DOOR_ROW_2_RIGHT = 0x40
        const val DOOR_REAR = 0x20000000
        // VehicleSeatOccupancyState
        const val SEAT_VACANT = 1
        const val SEAT_OCCUPIED = 2
        // CarHvacFanDirection (hidden): FACE=1, FLOOR=2, FACE_AND_FLOOR=3, DEFROST=4
        const val FAN_DIRECTION_FACE = 1
        const val FAN_DIRECTION_FLOOR = 2
        /** VehiclePropertyIds.BRAKE_PEDAL_COMPRESSION_PERCENTAGE (API 35, 0x1160030E); absent on API 34 images. */
        const val BRAKE_PEDAL_COMPRESSION_PERCENTAGE = 291504910

        fun createIfAvailable(context: Context): AospCarVehicle? {
            val app = context.applicationContext
            if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return null
            return try {
                val car = Car.createCar(app) ?: return null
                AospCarVehicle(app, car)
            } catch (t: Throwable) {
                Log.w(TAG, "android.car unavailable: ${t.message}")
                null
            }
        }
    }
}
