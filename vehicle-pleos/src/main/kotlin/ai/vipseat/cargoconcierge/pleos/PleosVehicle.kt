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
import ai.vipseat.cargoconcierge.core.port.CabinSettingOwner
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.port.RealMotionObserver
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class PleosVehicle(context: Context) : VehiclePort, RealMotionObserver {
    override val name: String = "Pleos Vehicle SDK 2.3.2"

    private val app = context.applicationContext
    private val vehicle = Vehicle(app)
    private val locationSpeed = PleosLocationSpeed(app)
    private val _signals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 256)
    override val signals: Flow<VehicleSignal> = merge(_signals, locationSpeed.signals)

    private val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)
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
    private val occupancyLock = Any()
    private val occupancyRevision = mutableMapOf<CargoSpot, Long>()
    private val occupancyReceivedAt = mutableMapOf<CargoSpot, Long>()
    @Volatile private var connectionGeneration = 0L
    @Volatile private var occupancyMappingVerified = false
    @Volatile private var lastGear = Gear.UNKNOWN
    @Volatile private var lastGearAt = 0L
    /** A temperature zone that covers row 2 but no row-1 seat — only such a zone may be retuned for a rear VIP. */
    @Volatile private var rearTempArea: SeatArea? = null
    @Volatile private var passengerTempArea: SeatArea? = null
    // Fan speed/direction are not per-seat on every head unit (the Pleos emulator exposes a front zone shared
    // with the driver): resolve the zone covering the passenger seat once, and never touch a shared zone.
    @Volatile private var fanArea: SeatArea? = null
    @Volatile private var dirArea: SeatArea? = null
    @Volatile private var zonesResolved = false
    @Volatile private var initialized = false
    private val cabinMutex = Mutex()
    private val temperatureSettings = linkedMapOf<String, CabinSettingOwner<Float>>()
    private val levelSettings = linkedMapOf<String, CabinSettingOwner<Int>>()
    private val directionSettings = linkedMapOf<String, CabinSettingOwner<FanDirectionType>>()
    private var motionPolling: Job? = null
    private data class SpeedReading(val mps: Double, val receivedAtMs: Long)
    @Volatile private var realSpeed: SpeedReading? = null

    override fun observeRealMotion(signal: VehicleSignal, receivedAtMs: Long) {
        when (signal) {
            is VehicleSignal.Speed -> if (signal.mps.isFinite() && signal.mps >= 0) realSpeed = SpeedReading(signal.mps, receivedAtMs)
            is VehicleSignal.SourceStatus -> if (!signal.connected) realSpeed = null
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ listeners (verified interfaces)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { recordGear(gear) }
        override fun onFailed(e: Exception) { recordGear(null); fail("gear", e) }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            angle?.takeIf { it.isFinite() }?.let { emit(VehicleSignal.SteeringAngle(it.toDouble())) }
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
    private var occupancyListeners: Map<CargoSpot, SeatOccupancyListener> = emptyMap()

    private fun recordOccupancy(spot: CargoSpot, value: Boolean?, generation: Long) {
        synchronized(occupancyLock) {
            if (!initialized || generation != connectionGeneration) return
            occupancyRevision[spot] = (occupancyRevision[spot] ?: 0) + 1
            occupancy[spot] = value
            occupancyReceivedAt[spot] = SystemClock.elapsedRealtime()
            emit(VehicleSignal.SeatOccupancy(spot, value))
        }
    }

    private fun beginOccupancyObservations() {
        val generation = synchronized(occupancyLock) {
            connectionGeneration += 1
            occupancy.clear()
            occupancyRevision.clear()
            occupancyReceivedAt.clear()
            connectionGeneration
        }
        val info = runCatching { app.packageManager.getPackageInfo("ai.pleos.playground.service.vehicle", 0) }.getOrNull()
        occupancyMappingVerified = PleosSeatOccupancy.isVerified(info?.versionName, info?.longVersionCode, Build.PRODUCT, Build.VERSION.SDK_INT)
        status["occupancyMapping"] = if (occupancyMappingVerified) "verified-vacancy-only" else "unverified-blocked"
        occupancyListeners = seatAreas.keys.associateWith { spot ->
            object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                    recordOccupancy(spot, PleosSeatOccupancy.decodeConfirmedVacancy(occupied, occupancyMappingVerified), generation)
                }
                override fun onFailed(e: Exception) {
                    if (!initialized || generation != connectionGeneration) return
                    recordOccupancy(spot, null, generation)
                    fail("occupancy-${spot.name.lowercase()}", e)
                }
            }
        }
    }

    /** A direct getter is required at the command boundary; a newer listener invalidates it. */
    private suspend fun readConfirmedVacancy(spot: CargoSpot, area: SeatArea): Boolean? {
        val generation = connectionGeneration
        val revision = synchronized(occupancyLock) { occupancyRevision[spot] ?: 0 }
        val started = SystemClock.elapsedRealtime()
        val sdkValue = awaitValue<Boolean?> { ok, err -> vehicle.getSeat().getCurrentSeatOccupancy(area, ok, err) }
        synchronized(occupancyLock) {
            if (!initialized || generation != connectionGeneration) return null
            if (revision != (occupancyRevision[spot] ?: 0)) return null
            if (SystemClock.elapsedRealtime() - started !in 0..SIGNAL_FRESHNESS_MS) {
                recordOccupancy(spot, null, generation)
                return null
            }
            val decoded = PleosSeatOccupancy.decodeConfirmedVacancy(sdkValue, occupancyMappingVerified)
            recordOccupancy(spot, decoded, generation)
            return decoded
        }
    }
    private val tempListener = object : HvacTemperatureListener {
        override fun onHvacTemperatureUpdated(celsius: Float?) { celsius?.takeIf { it.isFinite() }?.let { emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, it.toDouble())) } }
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

        beginOccupancyObservations()

        register("gear") { vehicle.getGear().registerGearSelection(gearListener) }
        register("steering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        register("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        doorAreas.forEach { (id, area) -> register("door-$id") { vehicle.getDoor().registerDoorPosition(area, doorListeners.getValue(id)) } }
        seatAreas.forEach { (spot, area) -> register("occupancy-${spot.name.lowercase()}") { vehicle.getSeat().registerSeatOccupancy(area, occupancyListeners.getValue(spot)) } }
        register("hvacTemp") { vehicle.getHVAC().registerHvacTemperature(passengerSeat, tempListener) }
        register("drivingMode") { vehicle.getDrivingMode().registerDrivingModeState(drivingModeListener) }

        // Initial reads so the snapshot is populated before the first change event.
        runCatching { vehicle.getGear().getCurrentGearSelectionState(::recordGear, { fail("gear.get", it) }) }
        doorAreas.forEach { (id, area) ->
            runCatching { vehicle.getDoor().getCurrentDoorPosition(area, { emit(VehicleSignal.Door(id, (it ?: 0) > 0)) }, { fail("door.get-$id", it) }) }
        }
        seatAreas.forEach { (spot, area) -> readConfirmedVacancy(spot, area) }
        runCatching { vehicle.getHVAC().getHvacTemperature(passengerSeat, TemperatureUnit.CELSIUS, { t -> t?.takeIf { it.isFinite() }?.let { emit(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, it.toDouble())) } }, { fail("hvacTemp.get", it) }) }

        status["speed"] = locationSpeed.start()
        startMotionPolling()
        emit(VehicleSignal.SourceStatus(true, status.entries.joinToString(" ") { "${it.key}=${it.value}" }))
    }

    override suspend fun disconnect() = cabinMutex.withLock {
        synchronized(occupancyLock) {
            initialized = false
            connectionGeneration += 1
            occupancyMappingVerified = false
            occupancy.clear()
            occupancyRevision.clear()
            occupancyReceivedAt.clear()
            seatAreas.keys.forEach { emit(VehicleSignal.SeatOccupancy(it, null)) }
        }
        motionPolling?.cancel()
        motionPolling = null
        lastGear = Gear.UNKNOWN
        lastGearAt = 0L
        realSpeed = null
        locationSpeed.stop()
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        doorAreas.forEach { (id, area) -> runCatching { vehicle.getDoor().unregisterDoorPosition(area, doorListeners.getValue(id)) } }
        seatAreas.forEach { (spot, area) -> runCatching { vehicle.getSeat().unregisterSeatOccupancy(area, occupancyListeners.getValue(spot)) } }
        runCatching { vehicle.getHVAC().unregisterHvacTemperature(passengerSeat, tempListener) }
        runCatching { vehicle.getDrivingMode().unregisterDrivingModeState(drivingModeListener) }
        runCatching { vehicle.release() }
        zonesResolved = false
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

    override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot): CabinApplyResult = cabinMutex.withLock {
        if (!initialized) return@withLock CabinApplyResult(CabinApplyResult.unsupported(plan, "차량 연결 없음 · 변경하지 않음").outcomes.map { it.copy(status = FeatureStatus.FAILED) })
        if (spot == CargoSpot.TRUNK) return@withLock CabinApplyResult.unsupported(plan, "트렁크는 공조 영역이 아니에요")
        resolveHvacZones()
        val seatArea = seatAreas.getValue(spot)
        val tempArea = if (spot.isRear) rearTempArea?.takeIf { area -> seatArea.values.all { area.covers(it) } } else passengerTempArea
        val out = ArrayList<FeatureOutcome>()
        plan.temperatureC?.let { out += applyTemperature(CabinFeature.TEMPERATURE, tempArea, it.toFloat()) }
        // AIRFLOW.OFF and FAN address the same physical property: send and own only one final value.
        if (plan.airflow != AirflowMode.OFF) plan.fanLevel?.let {
            out += applyFan(CabinFeature.FAN, if (spot.isRear) null else fanArea, it)
        }
        when (plan.airflow) {
            AirflowMode.OFF -> {
                val result = applyFan(CabinFeature.AIRFLOW, if (spot.isRear) null else fanArea, 0)
                out += result
                if (plan.fanLevel != null) out += result.copy(feature = CabinFeature.FAN)
            }
            AirflowMode.DIRECT_FACE -> out += applyDirection(if (spot.isRear) null else dirArea, FanDirectionType.FACE)
            AirflowMode.INDIRECT_FLOOR -> out += applyDirection(if (spot.isRear) null else dirArea, FanDirectionType.FLOOR)
            AirflowMode.UNCHANGED -> Unit
        }
        plan.seatHeaterLevel?.let { level ->
            out += when {
                level > 0 && !consent.emptySeatHeaterAllowed -> FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.SKIPPED_NO_CONSENT, "빈 좌석 열선 동의 없음")
                level > 0 -> FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.PENDING_VALIDATION, "적재물 열선은 실측 검증 전 비활성")
                else -> applySeatLevel(CabinFeature.SEAT_HEATER, seatArea, level)
            }
        }
        plan.seatVentLevel?.let { out += applySeatLevel(CabinFeature.SEAT_VENT, seatArea, it) }
        plan.rearZoneTemperatureC?.let { out += applyTemperature(CabinFeature.REAR_TEMPERATURE, rearTempArea, it.toFloat()) }
        CabinApplyResult(out).also { logCabinResult("apply", it) }
    }

    override suspend fun restoreCabin(): CabinApplyResult = cabinMutex.withLock {
        val settings = allSettings().filter { it.needsRestore }
        val out = if (!initialized) settings.map {
            FeatureOutcome(it.feature, FeatureStatus.FAILED, "차량 연결 없음 · 복원 보류, 재시도 가능")
        } else settings.map { it.restore() }
        // Keep failures for an explicit retry. Successful/mismatched properties need no further write.
        if (allSettings().none { it.needsRestore }) {
            temperatureSettings.clear()
            levelSettings.clear()
            directionSettings.clear()
        }
        CabinApplyResult(out).also { logCabinResult("restore", it) }
    }

    private fun allSettings(): List<CabinSettingOwner<*>> =
        temperatureSettings.values + levelSettings.values + directionSettings.values

    private suspend fun applyTemperature(feature: CabinFeature, area: SeatArea?, target: Float): FeatureOutcome {
        if (area == null) return FeatureOutcome(feature, FeatureStatus.UNSUPPORTED, "독립 온도 존 없음")
        if (!target.isFinite()) return FeatureOutcome(feature, FeatureStatus.FAILED, "유효하지 않은 온도")
        val setting = temperatureSettings.getOrPut(area.key()) {
            CabinSettingOwner(
                feature = feature,
                read = { awaitValue<Float?> { ok, err -> vehicle.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, err) }?.takeIf { it.isFinite() } },
                write = { value -> outcome(feature) { ok, err -> vehicle.getHVAC().setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> ok() }, err) } },
                guard = { restoring -> commandGuard(feature, area, restoring) },
                matches = { a, b -> kotlin.math.abs(a - b) < 0.1f },
                maxReadAgeMs = SIGNAL_FRESHNESS_MS,
            )
        }
        return setting.apply(target).copy(feature = feature)
    }

    private suspend fun applyFan(feature: CabinFeature, area: SeatArea?, target: Int): FeatureOutcome {
        zoneRestriction(feature, area)?.let { return it }
        val zone = requireNotNull(area)
        val setting = levelSettings.getOrPut("fan:${zone.key()}") {
            CabinSettingOwner(
                feature = feature,
                read = { awaitValue<Int?> { ok, err -> vehicle.getHVAC().getHvacFanSpeed(zone, ok, err) } },
                write = { value -> outcome(feature) { ok, err -> vehicle.getHVAC().setHvacFanSpeed(zone, value, { _, _ -> ok() }, err) } },
                guard = { restoring -> commandGuard(feature, zone, restoring) },
                maxReadAgeMs = SIGNAL_FRESHNESS_MS,
            )
        }
        return setting.apply(target).copy(feature = feature)
    }

    private suspend fun applyDirection(area: SeatArea?, target: FanDirectionType): FeatureOutcome {
        val feature = CabinFeature.AIRFLOW
        zoneRestriction(feature, area)?.let { return it }
        val zone = requireNotNull(area)
        val setting = directionSettings.getOrPut(zone.key()) {
            CabinSettingOwner(
                feature = feature,
                read = { awaitValue<FanDirectionType?> { ok, err -> vehicle.getHVAC().getHvacFanDirection(zone, ok, err) } },
                write = { value -> outcome(feature) { ok, err -> vehicle.getHVAC().setHvacFanDirection(zone, value, { _, _ -> ok() }, err) } },
                guard = { restoring -> commandGuard(feature, zone, restoring) },
                maxReadAgeMs = SIGNAL_FRESHNESS_MS,
            )
        }
        return setting.apply(target)
    }

    private suspend fun applySeatLevel(feature: CabinFeature, area: SeatArea, target: Int): FeatureOutcome {
        val heater = feature == CabinFeature.SEAT_HEATER
        val setting = levelSettings.getOrPut("${feature.name}:${area.key()}") {
            CabinSettingOwner(
                feature = feature,
                read = { awaitValue<Int?> { ok, err ->
                    if (heater) vehicle.getSeat().getHvacSeatTemperature(area, ok, err)
                    else vehicle.getSeat().getHvacSeatVentilationState(area, ok, err)
                } },
                write = { value -> outcome(feature) { ok, err ->
                    if (heater) vehicle.getSeat().setHvacSeatTemperature(area, value, { _, _ -> ok() }, err)
                    else vehicle.getSeat().setHvacSeatVentilation(area, value, { _, _ -> ok() }, err)
                } },
                guard = { restoring -> commandGuard(feature, area, restoring) },
                maxReadAgeMs = SIGNAL_FRESHNESS_MS,
            )
        }
        return setting.apply(target)
    }

    /** Re-read the real vehicle immediately before every write; demo/UI state is not authority. */
    private suspend fun commandGuard(feature: CabinFeature, area: SeatArea, restoring: Boolean): FeatureOutcome? {
        if (!initialized) return FeatureOutcome(feature, FeatureStatus.FAILED, "차량 연결 없음 · 명령 보류")
        val generation = connectionGeneration
        val coveredSeats = seatAreas.filter { (_, seat) -> seat.values.any { area.covers(it) } }
        val gear = awaitValue<VehicleGear?> { ok, err -> vehicle.getGear().getCurrentGearSelectionState(ok, err) }
        recordGear(gear)
        if (gear.toCore() != Gear.PARK) return FeatureOutcome(feature, FeatureStatus.DRIVING_RESTRICTED, "P 기어 확인 불가 · 명령 보류")
        if (!restoring) {
            for ((spot, seatArea) in coveredSeats) {
                val occupied = readConfirmedVacancy(spot, seatArea)
                if (occupied != false) return FeatureOutcome(feature, FeatureStatus.BLOCKED_BY_OCCUPANCY, "${spot.short} 비착좌 확인 불가 · 변경하지 않음")
            }
        }
        val now = SystemClock.elapsedRealtime()
        val speed = realSpeed?.takeIf { now - it.receivedAtMs in 0..SIGNAL_FRESHNESS_MS }?.mps
            ?: locationSpeed.freshSpeedMps(SIGNAL_FRESHNESS_MS)
        val stillVacant = synchronized(occupancyLock) {
            coveredSeats.isNotEmpty() && coveredSeats.keys.all {
                occupancy[it] == false && occupancyReceivedAt[it]?.let { at ->
                    now - at in 0..SIGNAL_FRESHNESS_MS
                } == true
            }
        }
        return when {
            !initialized || generation != connectionGeneration -> FeatureOutcome(feature, FeatureStatus.FAILED, "차량 연결 끊김 · 명령 보류")
            !restoring && !stillVacant -> FeatureOutcome(feature, FeatureStatus.BLOCKED_BY_OCCUPANCY, "최신 비착좌 확인 상실 · 변경하지 않음")
            lastGear != Gear.PARK || now - lastGearAt !in 0..SIGNAL_FRESHNESS_MS ->
                FeatureOutcome(feature, FeatureStatus.DRIVING_RESTRICTED, "최신 P 기어 확인 불가 · 명령 보류")
            speed == null -> FeatureOutcome(feature, FeatureStatus.DRIVING_RESTRICTED, "최신 차속 확인 불가 · 명령 보류")
            speed >= 0.3 -> FeatureOutcome(feature, FeatureStatus.DRIVING_RESTRICTED, "정차 확인 불가 · 명령 보류")
            else -> null
        }
    }

    private fun zoneRestriction(feature: CabinFeature, area: SeatArea?): FeatureOutcome? = when {
        area == null -> FeatureOutcome(feature, FeatureStatus.UNSUPPORTED, "독립 조수석 존 없음")
        !area.passengerOnly() -> FeatureOutcome(feature, FeatureStatus.UNCHANGED, "운전석과 공유하는 존이라 유지")
        else -> null
    }

    private fun SeatArea.key(): String = values.map { it.name }.sorted().joinToString(",")

    private fun logCabinResult(operation: String, result: CabinApplyResult) {
        result.outcomes.forEach { Log.i(TAG, "$operation ${it.feature}: ${it.status} · ${it.detail}") }
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

    private fun emit(signal: VehicleSignal) { _signals.tryEmit(signal) }

    private fun recordGear(gear: VehicleGear?) {
        lastGear = gear.toCore()
        lastGearAt = SystemClock.elapsedRealtime()
        emit(VehicleSignal.GearChanged(lastGear))
    }

    /** Real getter observations keep ON_CHANGE signals fresh; cached values are never republished. */
    private fun startMotionPolling() {
        motionPolling?.cancel()
        motionPolling = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && initialized) {
                val gear = awaitValue<VehicleGear?> { ok, err -> vehicle.getGear().getCurrentGearSelectionState(ok, err) }
                recordGear(gear)
                awaitValue<Float?> { ok, err -> vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(ok, err) }
                    ?.takeIf { it.isFinite() }
                    ?.let { emit(VehicleSignal.SteeringAngle(it.toDouble())) }
                delay(MOTION_POLL_MS)
            }
        }
    }

    private fun SeatArea.covers(v: SeatValue) = values.contains(v)
    private fun SeatArea.passengerOnly() = values.size == 1 && covers(SeatValue.ROW_1_RIGHT)

    /** Finds the fan-speed / fan-direction zones that cover the passenger seat (cached per connection). */
    private suspend fun resolveHvacZones() {
        if (zonesResolved) return
        val hvac = vehicle.getHVAC()
        fanArea = awaitValue<SeatArea?> { ok, err -> hvac.checkHvacFanSpeedCapabilities({ _, areas -> ok(areas.keys.firstOrNull { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        dirArea = awaitValue<SeatArea?> { ok, err -> hvac.checkHvacFanDirectionCapabilities({ _, areas -> ok(areas.keys.firstOrNull { it.covers(SeatValue.ROW_1_RIGHT) }) }, err) }
        passengerTempArea = awaitValue<SeatArea?> { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ cfg, areas -> ok(areas.keys.firstOrNull { cfg.accessType.writable() && it.passengerOnly() }) }, err)
        }
        rearTempArea = awaitValue<SeatArea?> { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ cfg, areas ->
                ok(areas.keys.firstOrNull { cfg.accessType.writable() && it.values.isNotEmpty() && it.values.all { value -> value in REAR_SEATS } })
            }, err)
        }
        zonesResolved = true
        Log.i(TAG, "hvac zones: passenger temperature=${passengerTempArea?.values?.joinToString()} fan=${fanArea?.values?.joinToString()} direction=${dirArea?.values?.joinToString()} rearTemp=${rearTempArea?.values?.joinToString() ?: "none"}")
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
        const val SIGNAL_FRESHNESS_MS = 2_000L
        const val MOTION_POLL_MS = 1_000L
        val REAR_SEATS = setOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    }
}
