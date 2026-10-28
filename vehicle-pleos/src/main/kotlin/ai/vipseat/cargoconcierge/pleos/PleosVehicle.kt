package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.vipseat.cargoconcierge.core.model.AirflowMode
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CabinZone
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 2.3.2 adapter.
 *
 * Every SDK symbol used here was verified with `javap` against Vehicle-2.3.2.aar (see README,
 * "SDK calls: verified vs assumed"). Call pattern follows the official sample
 * (pleos-sample-vehicle-app/VehicleClient.kt): Vehicle(context) → initialize() → getDoor() …
 * → check*Capabilities / getCurrent* / set* / register*(area, listener).
 *
 * Not available in SDK 2.3.2 and therefore NOT used: vehicle speed (no typed API), a driving-mode
 * *setter* (DrivingMode is read-only), brake pedal *percentage* (boolean only).
 * Speed is derived from FusedLocation fixes by [PleosLocationSpeed] and merged into [signals].
 */
class PleosVehicle(context: Context) : VehiclePort {
    override val name: String = "Pleos Vehicle SDK 2.3.2"

    private val app = context.applicationContext
    private val vehicle = Vehicle(app)
    private val locationSpeed = PleosLocationSpeed(app)
    private val _signals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 256)
    override val signals: Flow<VehicleSignal> = merge(_signals, locationSpeed.signals)

    private val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)
    private val rearSeats = SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
    /** Seat area per VIP spot (SeatValue names verified with javap against Vehicle-2.3.2.aar). */
    private val seatAreas = mapOf(
        CargoSpot.FRONT_PASSENGER to passengerSeat,
        CargoSpot.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
        CargoSpot.REAR_CENTER to SeatArea(SeatValue.ROW_2_CENTER),
        CargoSpot.REAR_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT),
    )
    private val doorAreas = mapOf(
        DoorId.DRIVER to DoorArea(DoorValue.ROW_1_LEFT),
        DoorId.PASSENGER to DoorArea(DoorValue.ROW_1_RIGHT),
        DoorId.REAR_LEFT to DoorArea(DoorValue.ROW_2_LEFT),
        DoorId.REAR_RIGHT to DoorArea(DoorValue.ROW_2_RIGHT),
        DoorId.TRUNK to DoorArea(DoorValue.REAR),
    )
    private val status = linkedMapOf<String, String>()
    private val occupancy: MutableMap<CargoSpot, Boolean?> = java.util.Collections.synchronizedMap(HashMap())
    @Volatile private var lastPassengerTempC: Float? = null
    /** A temperature zone that covers row 2 but no row-1 seat — only such a zone may be retuned for a rear VIP. */
    @Volatile private var rearTempArea: SeatArea? = null
    // Fan speed/direction are not per-seat on every head unit (the Pleos emulator exposes a front zone shared
    // with the driver): resolve the zone covering the passenger seat once, and never touch a shared zone.
    @Volatile private var fanArea: SeatArea? = null
    @Volatile private var dirArea: SeatArea? = null
    @Volatile private var zonesResolved = false
    private var initialized = false

    private data class SavedCabin(val spot: CargoSpot, val tempC: Float?, val fan: Int?, val direction: FanDirectionType?, val heater: Int?, val vent: Int?)
    private var saved: SavedCabin? = null

    // ------------------------------------------------------------------ listeners (verified interfaces)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { emit(VehicleSignal.GearChanged(gear.toCore())) }
        override fun onFailed(e: Exception) { fail("gear", e) }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        // The Pleos emulator re-publishes PERF_STEERING_ANGLE = 0.0 every ~3 s without a change; forwarding those repeats would
        // overwrite a steering value that another source (demo bridge, AOSP) just reported, so unchanged values are dropped.
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            angle?.let { if (it != lastSteeringDeg) { lastSteeringDeg = it; emit(VehicleSignal.SteeringAngle(it.toDouble())) } }
        }
        override fun onFailed(e: Exception) { fail("steering", e) }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { pressed?.let { emit(VehicleSignal.BrakePedal(it, null)) } }
        override fun onFailed(e: Exception) { fail("brake", e) }
    }
    private val doorListeners = doorAreas.keys.associateWith { id ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { emit(VehicleSignal.Door(id, (position ?: 0) > 0)) }
            override fun onFailed(e: Exception) { fail("door-$id", e) }
        }
    }
    private val occupancyListeners = seatAreas.keys.associateWith { spot ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) { occupancy[spot] = occupied; emit(VehicleSignal.SeatOccupancy(spot, occupied)) }
            override fun onFailed(e: Exception) { fail("occupancy-${spot.name.lowercase()}", e) }
        }
    }
    private val tempListener = object : HvacTemperatureListener {
        override fun onHvacTemperatureUpdated(celsius: Float?) { celsius?.let { lastPassengerTempC = it; emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, it.toDouble())) } }
        override fun onFailed(e: Exception) { fail("hvacTemp", e) }
    }
    private val drivingModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(mode: DrivingModeType?) { emit(VehicleSignal.DrivingModeChanged(mode.toCore())) }
        override fun onFailed(e: Exception) { fail("drivingMode", e) }
    }

    // ------------------------------------------------------------------ lifecycle

    override suspend fun connect() {
        try {
            vehicle.initialize()
            initialized = true
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle.initialize failed: ${PleosErrors.describe(e)}")
            emit(VehicleSignal.SourceStatus(false, "Pleos Vehicle Service 없음: ${PleosErrors.describe(e)}"))
            return
        }
        // ASSUMPTION: service binding completes asynchronously after initialize(); give it a moment
        // before registering (the sample registers on user action, well after initialize()).
        delay(SERVICE_BIND_GRACE_MS)

        register("gear") { vehicle.getGear().registerGearSelection(gearListener) }
        register("steering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        register("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        doorAreas.forEach { (id, area) -> register("door-$id") { vehicle.getDoor().registerDoorPosition(area, doorListeners.getValue(id)) } }
        seatAreas.forEach { (spot, area) -> register("occupancy-${spot.name.lowercase()}") { vehicle.getSeat().registerSeatOccupancy(area, occupancyListeners.getValue(spot)) } }
        register("hvacTemp") { vehicle.getHVAC().registerHvacTemperature(passengerSeat, tempListener) }
        register("drivingMode") { vehicle.getDrivingMode().registerDrivingModeState(drivingModeListener) }

        // Initial reads so the snapshot is populated before the first change event.
        runCatching { vehicle.getGear().getCurrentGearSelectionState({ emit(VehicleSignal.GearChanged(it.toCore())) }, { fail("gear.get", it) }) }
        doorAreas.forEach { (id, area) ->
            runCatching { vehicle.getDoor().getCurrentDoorPosition(area, { emit(VehicleSignal.Door(id, (it ?: 0) > 0)) }, { fail("door.get-$id", it) }) }
        }
        seatAreas.forEach { (spot, area) ->
            runCatching { vehicle.getSeat().getCurrentSeatOccupancy(area, { occupancy[spot] = it; emit(VehicleSignal.SeatOccupancy(spot, it)) }, { fail("occupancy.get-${spot.name.lowercase()}", it) }) }
        }
        runCatching { vehicle.getHVAC().getHvacTemperature(passengerSeat, TemperatureUnit.CELSIUS, { t -> t?.let { lastPassengerTempC = it; emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, it.toDouble())) } }, { fail("hvacTemp.get", it) }) }

        status["speed"] = locationSpeed.start()
        emit(VehicleSignal.SourceStatus(true, status.entries.joinToString(" ") { "${it.key}=${it.value}" }))
    }

    override suspend fun disconnect() {
        locationSpeed.stop()
        if (!initialized) return
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        doorAreas.forEach { (id, area) -> runCatching { vehicle.getDoor().unregisterDoorPosition(area, doorListeners.getValue(id)) } }
        seatAreas.forEach { (spot, area) -> runCatching { vehicle.getSeat().unregisterSeatOccupancy(area, occupancyListeners.getValue(spot)) } }
        runCatching { vehicle.getHVAC().unregisterHvacTemperature(passengerSeat, tempListener) }
        runCatching { vehicle.getDrivingMode().unregisterDrivingModeState(drivingModeListener) }
        runCatching { vehicle.release() }
        initialized = false
    }

    // ------------------------------------------------------------------ capabilities

    override suspend fun capabilities(): CabinCapabilities {
        if (!initialized) return CabinCapabilities(emptyMap(), "not connected")
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val temp = await { ok, err -> hvac.checkHvacTemperatureCapabilities({ cfg, areas -> ok(cfg.accessType.writable() && areas.keys.any { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        val rear = await { ok, err -> hvac.checkHvacTemperatureCapabilities({ cfg, areas -> ok(cfg.accessType.writable() && areas.keys.any { it.covers(SeatValue.ROW_2_LEFT) || it.covers(SeatValue.ROW_2_RIGHT) }) }, err) }
        val fan = await { ok, err -> hvac.checkHvacFanSpeedCapabilities({ cfg, _ -> ok(cfg.accessType.writable()) }, err) }
        val dir = await { ok, err -> hvac.checkHvacFanDirectionCapabilities({ cfg, _ -> ok(cfg.accessType.writable()) }, err) }
        val heater = await { ok, err -> seat.checkSeatTemperatureCapabilities({ cfg, areas -> ok(cfg.accessType.writable() && areas.keys.any { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        val vent = await { ok, err -> seat.checkSeatVentilationCapabilities({ cfg, areas -> ok(cfg.accessType.writable() && areas.keys.any { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        return CabinCapabilities(
            supported = mapOf(
                CabinFeature.TEMPERATURE to (temp == true),
                CabinFeature.FAN to (fan == true),
                CabinFeature.AIRFLOW to (dir == true),
                CabinFeature.SEAT_HEATER to (heater == true),
                CabinFeature.SEAT_VENT to (vent == true),
                CabinFeature.REAR_TEMPERATURE to (rear == true),
            ),
            detail = status.entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }

    // ------------------------------------------------------------------ cabin control

    override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot): CabinApplyResult {
        if (!initialized) return CabinApplyResult(emptyList())
        if (spot == CargoSpot.TRUNK) return CabinApplyResult.unsupported(plan, "트렁크는 공조 영역이 아니에요")
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        resolveHvacZones()
        val seatArea = seatAreas.getValue(spot)
        val rear = spot.isRear
        val tempArea: SeatArea? = if (rear) rearTempArea else passengerSeat
        val seatOccupied = occupancy[spot]
        if (saved == null) {
            saved = SavedCabin(
                spot = spot,
                // The emulator accepts set() on the passenger zone but get() reports "not available": fall back to the last listener value.
                tempC = tempArea?.let { area -> awaitValue { ok, err -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, err) } } ?: if (rear) null else lastPassengerTempC,
                fan = if (rear) null else fanArea?.takeIf { it.passengerOnly() }?.let { area -> awaitValue { ok, err -> hvac.getHvacFanSpeed(area, ok, err) } },
                direction = if (rear) null else dirArea?.takeIf { it.passengerOnly() }?.let { area -> awaitValue { ok, err -> hvac.getHvacFanDirection(area, ok, err) } },
                heater = awaitValue { ok, err -> seat.getHvacSeatTemperature(seatArea, ok, err) },
                vent = awaitValue { ok, err -> seat.getHvacSeatVentilationState(seatArea, ok, err) },
            )
        }
        val out = ArrayList<FeatureOutcome>()
        plan.temperatureC?.let { t ->
            out += if (tempArea == null) FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.UNSUPPORTED, "뒷좌석 단독 존 없음")
            else outcome(CabinFeature.TEMPERATURE) { ok, err -> hvac.setHvacTemperature(tempArea, t.toFloat(), TemperatureUnit.CELSIUS, { _, _ -> ok() }, err) }
        }
        plan.fanLevel?.let { f ->
            out += if (rear) FeatureOutcome(CabinFeature.FAN, FeatureStatus.UNSUPPORTED, "뒷좌석 송풍 제어 없음")
            else zoneOutcome(CabinFeature.FAN, fanArea) { area, ok, err -> hvac.setHvacFanSpeed(area, f, { _, _ -> ok() }, err) }
        }
        if (plan.airflow != AirflowMode.UNCHANGED && rear) {
            out += FeatureOutcome(CabinFeature.AIRFLOW, FeatureStatus.UNSUPPORTED, "뒷좌석 송풍 제어 없음")
        } else when (plan.airflow) {
            AirflowMode.DIRECT_FACE -> out += zoneOutcome(CabinFeature.AIRFLOW, dirArea) { area, ok, err -> hvac.setHvacFanDirection(area, FanDirectionType.FACE, { _, _ -> ok() }, err) }
            AirflowMode.INDIRECT_FLOOR -> out += zoneOutcome(CabinFeature.AIRFLOW, dirArea) { area, ok, err -> hvac.setHvacFanDirection(area, FanDirectionType.FLOOR, { _, _ -> ok() }, err) }
            AirflowMode.OFF -> out += zoneOutcome(CabinFeature.AIRFLOW, fanArea) { area, ok, err -> hvac.setHvacFanSpeed(area, 0, { _, _ -> ok() }, err) }
            AirflowMode.UNCHANGED -> Unit
        }
        plan.seatHeaterLevel?.let { level ->
            out += if (level > 0 && seatOccupied != true && !consent.emptySeatHeaterAllowed) {
                FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.SKIPPED_NO_CONSENT, "empty seat, no consent")
            } else {
                val r = outcome(CabinFeature.SEAT_HEATER) { ok, err -> seat.setHvacSeatTemperature(seatArea, level, { _, _ -> ok() }, err) }
                if (r.status == FeatureStatus.APPLIED && level > 0 && seatOccupied == false) {
                    // Occupant classification may silently refuse: read back and report honestly.
                    val readBack = awaitValue { ok, err -> seat.getHvacSeatTemperature(seatArea, ok, err) }
                    if (readBack == 0) FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.BLOCKED_BY_OCCUPANCY, "read-back 0 on empty seat") else r
                } else r
            }
        }
        plan.seatVentLevel?.let { level ->
            out += outcome(CabinFeature.SEAT_VENT) { ok, err -> seat.setHvacSeatVentilation(seatArea, level, { _, _ -> ok() }, err) }
        }
        plan.rearZoneTemperatureC?.let { t ->
            val area = rearTempArea
            out += if (area == null) FeatureOutcome(CabinFeature.REAR_TEMPERATURE, FeatureStatus.UNSUPPORTED, "후석 단독 존 없음")
            else outcome(CabinFeature.REAR_TEMPERATURE) { ok, err -> hvac.setHvacTemperature(area, t.toFloat(), TemperatureUnit.CELSIUS, { _, _ -> ok() }, err) }
        }
        return CabinApplyResult(out)
    }

    override suspend fun restoreCabin(): CabinApplyResult {
        val s = saved ?: return CabinApplyResult.EMPTY
        saved = null
        if (!initialized) return CabinApplyResult.EMPTY
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val seatArea = seatAreas[s.spot] ?: passengerSeat
        val tempArea = if (s.spot.isRear) rearTempArea else passengerSeat
        val out = ArrayList<FeatureOutcome>()
        s.tempC?.let { v -> tempArea?.let { area -> out += outcome(CabinFeature.TEMPERATURE) { ok, err -> hvac.setHvacTemperature(area, v, TemperatureUnit.CELSIUS, { _, _ -> ok() }, err) } } }
        s.fan?.let { v -> out += zoneOutcome(CabinFeature.FAN, fanArea) { area, ok, err -> hvac.setHvacFanSpeed(area, v, { _, _ -> ok() }, err) } }
        s.direction?.let { v -> out += zoneOutcome(CabinFeature.AIRFLOW, dirArea) { area, ok, err -> hvac.setHvacFanDirection(area, v, { _, _ -> ok() }, err) } }
        s.heater?.let { v -> out += outcome(CabinFeature.SEAT_HEATER) { ok, err -> seat.setHvacSeatTemperature(seatArea, v, { _, _ -> ok() }, err) } }
        s.vent?.let { v -> out += outcome(CabinFeature.SEAT_VENT) { ok, err -> seat.setHvacSeatVentilation(seatArea, v, { _, _ -> ok() }, err) } }
        return CabinApplyResult(out)
    }

    // ------------------------------------------------------------------ helpers

    /** Bridges the SDK's (onComplete, onFailed) callback pair into a suspend call with a timeout. */
    private suspend fun outcome(feature: CabinFeature, call: (ok: () -> Unit, err: (Exception) -> Unit) -> Unit): FeatureOutcome {
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<FeatureOutcome> { cont ->
                try {
                    call({ if (cont.isActive) cont.resume(FeatureOutcome(feature, FeatureStatus.APPLIED)) }) { e ->
                        if (cont.isActive) cont.resume(FeatureOutcome(feature, PleosErrors.classify(e), PleosErrors.describe(e)))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(FeatureOutcome(feature, PleosErrors.classify(e), PleosErrors.describe(e)))
                }
            }
        }
        return result ?: FeatureOutcome(feature, FeatureStatus.FAILED, "timeout")
    }

    private suspend fun <T> awaitValue(call: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit): T? = withTimeoutOrNull(CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine<T?> { cont ->
            try {
                call({ v -> if (cont.isActive) cont.resume(v) }) { e -> Log.w(TAG, "read failed: ${PleosErrors.describe(e)}"); if (cont.isActive) cont.resume(null) }
            } catch (e: Exception) {
                Log.w(TAG, "read threw: ${PleosErrors.describe(e)}"); if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun await(call: (ok: (Boolean) -> Unit, err: (Exception) -> Unit) -> Unit): Boolean? = awaitValue(call)

    private inline fun register(label: String, block: () -> Unit) {
        status[label] = try { block(); "ok" } catch (e: Exception) { "error:${PleosErrors.describe(e)}" }
    }

    private fun fail(label: String, e: Exception) {
        status[label] = "failed:${PleosErrors.describe(e)}"
        Log.w(TAG, "$label failed: ${PleosErrors.describe(e)}")
        emit(VehicleSignal.SourceStatus(initialized, status.entries.joinToString(" ") { "${it.key}=${it.value}" }))
    }

    private var lastSteeringDeg: Float? = null
    private fun emit(signal: VehicleSignal) { _signals.tryEmit(signal) }

    private fun SeatArea.covers(v: SeatValue) = values.contains(v)
    private fun SeatArea.passengerOnly() = values.size == 1 && covers(SeatValue.ROW_1_RIGHT)

    /** Finds the fan-speed / fan-direction zones that cover the passenger seat (cached per connection). */
    private suspend fun resolveHvacZones() {
        if (zonesResolved) return
        val hvac = vehicle.getHVAC()
        fanArea = awaitValue<SeatArea?> { ok, err -> hvac.checkHvacFanSpeedCapabilities({ _, areas -> ok(areas.keys.firstOrNull { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        dirArea = awaitValue<SeatArea?> { ok, err -> hvac.checkHvacFanDirectionCapabilities({ _, areas -> ok(areas.keys.firstOrNull { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        rearTempArea = awaitValue<SeatArea?> { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ _, areas ->
                ok(areas.keys.firstOrNull { (it.covers(SeatValue.ROW_2_LEFT) || it.covers(SeatValue.ROW_2_RIGHT)) && !it.covers(SeatValue.ROW_1_LEFT) && !it.covers(SeatValue.ROW_1_RIGHT) })
            }, err)
        }
        zonesResolved = true
        Log.i(TAG, "hvac zones: passenger fan=${fanArea?.values?.joinToString()} direction=${dirArea?.values?.joinToString()} rearTemp=${rearTempArea?.values?.joinToString() ?: "none"}")
    }

    /** Applies a zone control only when the zone is the passenger seat alone; a zone shared with the driver is left untouched. */
    private suspend fun zoneOutcome(feature: CabinFeature, area: SeatArea?, call: (SeatArea, ok: () -> Unit, err: (Exception) -> Unit) -> Unit): FeatureOutcome = when {
        area == null -> FeatureOutcome(feature, FeatureStatus.UNSUPPORTED, "조수석 존 없음")
        !area.passengerOnly() -> FeatureOutcome(feature, FeatureStatus.UNCHANGED, "운전석과 공유하는 존이라 유지")
        else -> outcome(feature) { ok, err -> call(area, ok, err) }
    }

    private fun ai.pleos.playground.vehicle.api.model.capability.AccessType.writable() =
        this == ai.pleos.playground.vehicle.api.model.capability.AccessType.WRITE || this == ai.pleos.playground.vehicle.api.model.capability.AccessType.READ_WRITE

    // SDK listeners/callbacks deliver nullable values (verified by the compiler against the AAR metadata).
    private fun VehicleGear?.toCore(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private fun DrivingModeType?.toCore(): DrivingMode = when (this) {
        DrivingModeType.NORMAL -> DrivingMode.NORMAL
        DrivingModeType.ECO -> DrivingMode.ECO
        DrivingModeType.SPORT -> DrivingMode.SPORT
        null -> DrivingMode.UNKNOWN
    }

    private companion object {
        const val TAG = "PleosVehicle"
        const val SERVICE_BIND_GRACE_MS = 800L
        const val CALL_TIMEOUT_MS = 3_000L
    }
}
