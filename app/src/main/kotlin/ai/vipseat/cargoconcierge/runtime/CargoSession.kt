package ai.vipseat.cargoconcierge.runtime

import ai.vipseat.cargoconcierge.core.engine.CabinCommandGate
import ai.vipseat.cargoconcierge.core.port.CabinCommandPolicy
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.engine.UiMode
import ai.vipseat.cargoconcierge.core.mock.DemoActors
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.voice.VoiceIntent
import ai.vipseat.cargoconcierge.core.voice.VoiceIntentParser
import ai.vipseat.cargoconcierge.demo.DemoPlayer
import ai.vipseat.cargoconcierge.demo.UiCommand
import ai.vipseat.cargoconcierge.di.AppGraph
import ai.vipseat.cargoconcierge.platform.Settings
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import ai.vipseat.cargoconcierge.vm.LogLine
import ai.vipseat.cargoconcierge.vm.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host of the pure state machine: feeds it vehicle/navi signals and a 250 ms tick on the main
 * thread, and executes the effects it returns (TTS, chime, cabin commands) through the ports.
 */
class CargoSession(private val graph: AppGraph) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val starts = GuardStartGate<Action>()
    private var uiVisible = false
    private var locationLost = false
    private var runtimeJob: Job? = null
    private val connectionMutex = Mutex()
    private val cabinJobs = mutableSetOf<Job>()
    private var finishJob: Job? = null
    private var startTimeout: Job? = null
    private var lastRuntimeTraceMs = 0L
    private var sm: CargoStateMachine
    private var motionEstimator: GEstimator
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val demoPlayer: DemoPlayer
    /** Cabin commands run strictly in order (a spot move is restore → apply). */
    private val cabinMutex = Mutex()
    private val cabinGate = CabinCommandGate()
    private var restoreBlocked = false

    init {
        val s = graph.settings.settings.value
        motionEstimator = GEstimator(wheelbaseM = s.wheelbaseM.toDouble(), steeringRatio = s.steeringRatio.toDouble())
        sm = CargoStateMachine(motionEstimator)
        demoPlayer = DemoPlayer(scope, DemoActors(graph.mockVehicle, graph.mockNavi) { kind -> dispatch(Action.SelectPreset(CargoPreset.of(kind))) })
        _ui.update {
            it.copy(
                settings = s,
                cabinNotice = if (graph.settings.cabinSessionPending) "이전 실행의 공조 복원을 확인하지 못했어요. 차량 설정을 직접 확인해 주세요." else null,
                vehicleSource = graph.vehicle.name,
                naviSource = graph.navi.name,
                speechSource = graph.speech.name,
                voiceSource = graph.voice.name,
                sourceDescription = graph.sourceDescription,
            )
        }
        scope.launch { graph.settings.settings.collect { st -> _ui.update { it.copy(settings = st) } } }
        scope.launch { graph.recents.recents.collect { r -> _ui.update { it.copy(recent = r.map { v -> v.toPreset() }) } } }
        scope.launch { graph.uxMonitor.requiresDistractionOptimization.collect { r -> _ui.update { it.copy(uxRestricted = r) } } }
        scope.launch { graph.demoBridge.uiCommands.collect(::onUiCommand) }
        scope.launch { demoPlayer.progress.collect { p -> _ui.update { it.copy(demoRunning = p.running, demoCaption = p.caption, demoProgress = if (p.totalMs == 0L) 0f else p.elapsedMs.toFloat() / p.totalMs) } } }
    }

    /** Activity callbacks affect visibility only, never replace the running engine. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) {
            // A request not yet promoted cannot acquire a while-in-use location exemption later.
            starts.pending?.let { foregroundStartFailed(it.id) }
        }
        refreshLocationAccess()
        updateRuntime()
    }

    fun refreshLocationAccess() {
        val available = graph.locationAccessAvailable()
        if (starts.active?.location == true && !available) locationLost = true
        val allowed = when {
            starts.active != null -> starts.active?.location == true && !locationLost && available
            starts.pending != null -> false
            else -> uiVisible && available
        }
        graph.setLocationAccessAllowed(allowed)
    }

    val requiresForegroundService: Boolean get() = starts.requiresService

    /** Checked again by the service immediately before startForeground. */
    fun pendingForegroundRequest(id: Long): Boolean? = starts.pending
        ?.takeIf { it.id == id && uiVisible && (!it.location || graph.locationAccessAvailable()) }
        ?.location

    fun isActiveForegroundRequest(id: Long): Boolean = starts.active?.id == id

    fun foregroundReady(id: Long): Boolean {
        if (!uiVisible) return false
        val request = starts.ready(id) ?: return false
        startTimeout?.cancel()
        locationLost = false
        refreshLocationAccess() // Only now can a frozen location choice use the FGS exemption.
        log("가드 서비스 준비: 요청 $id")
        reduce(request.payload)
        traceRuntime("ready")
        if (sm.state !is CargoState.Guarding) {
            starts.end()
            finishAfterCabinWork()
        }
        return true
    }

    fun foregroundStartFailed(id: Long) {
        if (!starts.failed(id)) return
        startTimeout?.cancel()
        graph.setLocationAccessAllowed(false)
        _ui.update { it.copy(cabinNotice = "가드 실행을 시작하지 못했어요. 앱을 연 상태에서 다시 선택해 주세요.") }
        log("가드 시작 실패: 요청 $id · 새 캐빈 적용 없음")
        graph.stopGuardService()
        refreshLocationAccess()
        updateRuntime()
    }

    /** No EndTrip dispatch here: service loss must not create a restore/write. */
    fun foregroundLost(id: Long? = null) {
        if (id != null && starts.active?.id != id && starts.pending?.id != id) return
        if (!starts.requiresService) return
        val uncertainCabin = graph.settings.cabinSessionPending || cabinJobs.isNotEmpty()
        traceRuntime("lost")
        starts.abandon()
        startTimeout?.cancel()
        finishJob?.cancel()
        cabinGate.advance()
        cabinJobs.toList().forEach { it.cancel() }
        demoPlayer.stop()
        graph.speech.stop()
        graph.setLocationAccessAllowed(false)
        val settings = graph.settings.settings.value
        motionEstimator = GEstimator(wheelbaseM = settings.wheelbaseM.toDouble(), steeringRatio = settings.steeringRatio.toDouble())
        sm = CargoStateMachine(motionEstimator)
        if (uncertainCabin) {
            restoreBlocked = true
            graph.settings.setCabinSessionPending(true)
        }
        _ui.update { it.copy(state = CargoState.Idle, snapshot = sm.snapshot, uiMode = sm.uiMode,
            cabinRestorePending = uncertainCabin,
            cabinNotice = if (uncertainCabin) "가드 실행이 중단됐어요. 공조 복원을 확인하지 못해 차량 설정 확인이 필요해요."
                else "가드 실행이 중단됐어요. 앱에서 다시 시작해 주세요.") }
        log("가드 서비스 중단 · 자동 재시작/복원 없음")
        updateRuntime()
    }

    private fun updateRuntime() {
        val needed = uiVisible || starts.requiresService
        if (needed && runtimeJob == null) {
            runtimeJob = scope.launch(start = CoroutineStart.LAZY) {
                connectionMutex.withLock {
                    coroutineScope {
                        // Collect before connecting. Ports use replay=0: old speed is not reissued as fresh.
                        launch(start = CoroutineStart.UNDISPATCHED) { graph.vehicle.signals.collect { dispatch(Action.Vehicle(it)) } }
                        launch(start = CoroutineStart.UNDISPATCHED) { graph.navi.events.collect { e ->
                            if (e is NaviEvent.Status) _ui.update { it.copy(navigating = e.navigating) }
                            dispatch(Action.Navi(e))
                        } }
                        launch {
                            while (true) {
                                delay(250)
                                refreshLocationAccess()
                                dispatch(Action.Tick)
                                if (starts.active != null && Log.isLoggable("GuardRuntime", Log.DEBUG) && now() - lastRuntimeTraceMs >= 1_000) {
                                    lastRuntimeTraceMs = now()
                                    traceRuntime("tick")
                                }
                                _ui.update { it.copy(speechSource = graph.speech.name, voiceSource = graph.voice.name) }
                            }
                        }
                        withContext(Dispatchers.IO) { runCatching { graph.vehicle.connect() }.onFailure { Log.w(TAG, "vehicle connect failed", it) } }
                        withContext(Dispatchers.IO) { runCatching { graph.navi.connect() }.onFailure { Log.w(TAG, "navi connect failed", it) } }
                        val caps = withContext(Dispatchers.IO) { runCatching { graph.vehicle.capabilities() }.getOrNull() }
                        _ui.update { it.copy(capabilities = caps, vehicleSource = graph.vehicle.name, speechSource = graph.speech.name, voiceSource = graph.voice.name) }
                        log("연결: ${graph.vehicle.name} / ${graph.navi.name}")
                    }
                }
            }
            runtimeJob?.start()
        } else if (!needed && runtimeJob != null) {
            val old = runtimeJob!!
            runtimeJob = null
            old.cancel()
            graph.setLocationAccessAllowed(false)
            demoPlayer.stop()
            graph.speech.stop()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connectionMutex.withLock {
                    old.join()
                    withContext(Dispatchers.IO) {
                        runCatching { graph.vehicle.disconnect() }
                        runCatching { graph.navi.disconnect() }
                    }
                }
            }
        }
    }

    private fun requestGuard(action: Action) {
        if (!uiVisible || starts.ending) return
        if (starts.active != null) {
            reduce(action) // Reclassification keeps the same service and engine.
            return
        }
        val request = starts.request(uiVisible, graph.locationAccessAvailable(), action) ?: return
        graph.setLocationAccessAllowed(false)
        updateRuntime()
        _ui.update { it.copy(cabinNotice = "가드 실행을 준비하고 있어요") }
        if (!graph.startGuardService(request.id)) {
            foregroundStartFailed(request.id)
            return
        }
        startTimeout?.cancel()
        startTimeout = scope.launch {
            delay(5_000)
            foregroundStartFailed(request.id)
        }
    }

    private fun finishAfterCabinWork() {
        val id = starts.active?.id ?: return
        if (!starts.ending || finishJob?.isActive == true) return
        finishJob = scope.launch {
            val drained = withTimeoutOrNull(15_000) {
                drainCabinJobs(cabinJobs)
                true
            } == true
            if (!drained) {
                restoreBlocked = true
                cabinGate.advance()
                cabinJobs.toList().forEach { it.cancel() }
                graph.settings.setCabinSessionPending(true)
                _ui.update { it.copy(cabinRestorePending = true, cabinNotice = "공조 응답을 확인하지 못했어요. 차량 설정을 직접 확인해 주세요.") }
            }
            traceRuntime("finished")
            if (starts.finished(id)) {
                graph.setLocationAccessAllowed(false)
                graph.stopGuardService()
                refreshLocationAccess()
                updateRuntime()
                log("가드 서비스 종료: 요청 $id · 공조 처리 ${if (drained) "정리" else "미확정"}")
            }
        }
    }

    /** Track cabin work before starting it; a synchronous fake may finish inside launch. */
    private fun launchCabin(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        cabinJobs += job
        job.invokeOnCompletion { scope.launch { cabinJobs -= job } }
        job.start()
    }

    // ------------------------------------------------------------------ user intents

    fun selectPreset(preset: CargoPreset) = dispatch(Action.SelectPreset(preset))
    fun dismissOffer() = dispatch(Action.DismissOffer)
    fun openOffer() = dispatch(Action.OpenOffer)
    /** Cabin map / sheet chips: where the VIP rides. An occupied seat is allowed — the passenger holds it. */
    fun selectSpot(spot: CargoSpot) = dispatch(Action.SelectSpot(spot))
    /** Host-side F1 ②/③ triggers (pre-boot door history, pickup-store geofence) — obeys the reducer's suppression rules. */
    fun externalOffer(reason: String) = dispatch(Action.ExternalOffer(reason))
    fun acknowledgeReport() = dispatch(Action.AcknowledgeReport)
    fun retryRestore() {
        if (!_ui.value.interactive || !sm.canControlCabin(now())) return
        if (sm.state is CargoState.Guarding) dispatch(Action.EndTrip)
        else handle(Effect.RestoreCabin, cabinGate.advance())
    }
    fun resetOfferSuppression() = dispatch(Action.ResetOfferSuppression)
    fun continueWithSameVip() {
        val arrived = sm.state as? CargoState.Arrived ?: return
        if (!_ui.value.interactive || !sm.canControlCabin(now())) return
        dispatch(Action.AcknowledgeReport)
        dispatch(Action.SelectSpot(arrived.spot))
        dispatch(Action.SelectPreset(arrived.preset))
    }
    fun endTrip() = dispatch(Action.EndTrip)
    fun toggleSettings(show: Boolean = !_ui.value.showSettings) = _ui.update { it.copy(showSettings = show) }
    fun toggleDemoPanel(show: Boolean = !_ui.value.showDemoPanel) = _ui.update { it.copy(showDemoPanel = show) }
    fun updateSettings(transform: (Settings) -> Settings) = graph.settings.update(transform)

    /** 분류 바꾸기 (parked): same name under another category — guard restarts, remembered for next time. */
    fun reclassify(preset: CargoPreset, kind: CargoKind) {
        graph.recents.reclassify(preset.displayName, kind)
        log("분류 변경: ${preset.displayName} → ${kind.label}")
        dispatch(Action.SelectPreset(CargoPreset.named(preset.displayName, kind)))
    }
    fun removeRecent(name: String) = graph.recents.remove(name)
    fun clearRecents() = graph.recents.clear()

    /** Text path of the Gleo/STT hook: "케이크 실었어" → preset. */
    fun voiceText(text: String) {
        _ui.update { it.copy(lastVoiceText = text) }
        when (val intent = VoiceIntentParser.parse(text, graph.recents.presets)) {
            is VoiceIntent.LoadPreset -> {
                val p = intent.preset
                val where = intent.spot?.let { " · ${it.label}" } ?: ""
                log(if (intent.isNew) "음성: \"$text\" → 새 VIP '${p.displayName}' (${p.kind.label}, ${p.limitG}G)$where" else "음성: \"$text\" → ${p.displayName} (${p.kind.label})$where")
                dispatch(Action.VoicePreset(p, intent.spot))
            }
            VoiceIntent.EndTrip -> { log("음성: \"$text\" → 종료"); dispatch(Action.EndTrip) }
            VoiceIntent.OpenSheet -> { log("음성: \"$text\" → 프리셋"); dispatch(Action.OpenOffer) }
            is VoiceIntent.Unknown -> { log("음성: \"$text\" → 인식 못함"); if (_ui.value.interactive && sm.canControlCabin(now())) speak("무엇을 실었는지 못 알아들었어요. 조수석에 도자기 실었어, 처럼 말씀해 주세요") }
        }
    }

    fun startListening() {
        if (_ui.value.listening || !_ui.value.interactive || !sm.canControlCabin(now())) return
        scope.launch {
            _ui.update { it.copy(listening = true) }
            val text = runCatching { graph.voice.listenOnce() }.getOrNull()
            _ui.update { it.copy(listening = false) }
            if (text.isNullOrBlank()) { log("음성 입력 없음 (${graph.voice.name})"); if (_ui.value.interactive && sm.canControlCabin(now())) speak("잘 못 들었어요") } else voiceText(text)
        }
    }

    fun startDemo() { log("데모 시나리오 시작"); demoPlayer.start() }
    fun stopDemo() { demoPlayer.stop(); log("데모 시나리오 중지") }

    // Manual demo controls (parked UI only) → mock vehicle
    fun demoGear(g: Gear) = graph.mockVehicle.setGear(g)
    fun demoSpeed(kph: Double) = graph.mockVehicle.setSpeedKph(kph)
    fun demoSteering(deg: Double) = graph.mockVehicle.setSteering(deg)
    fun demoBrake(pct: Double) = graph.mockVehicle.setBrake(pct > 0, pct)
    fun demoDoor(id: DoorId, open: Boolean) = graph.mockVehicle.setDoor(id, open)
    fun demoOccupied(v: Boolean?, spot: CargoSpot = CargoSpot.FRONT_PASSENGER) = graph.mockVehicle.setOccupied(spot, v)
    fun demoCurve() = graph.mockNavi.sharpCurve(100)
    fun demoArrived() = graph.mockNavi.arrived()

    // ------------------------------------------------------------------ internals

    private fun onUiCommand(c: UiCommand) {
        when (c) {
            is UiCommand.VoiceText -> voiceText(c.text)
            is UiCommand.Preset -> dispatch(Action.SelectPreset(CargoPreset.of(c.kind)))
            is UiCommand.SelectSpot -> selectSpot(c.spot)
            is UiCommand.SeedRecents -> { if (c.spec.isBlank()) graph.recents.clear() else graph.recents.seed(c.spec); log("최근 VIP 목록: ${c.spec.ifBlank { "비움" }}") }
            is UiCommand.Scenario -> if (c.start) startDemo() else stopDemo()
            UiCommand.OpenSheet -> dispatch(Action.OpenOffer)
            is UiCommand.ExternalOffer -> dispatch(Action.ExternalOffer(c.reason))
            is UiCommand.AcknowledgeReport -> acknowledgeReport()
            is UiCommand.ShowSettings -> toggleSettings(c.show)
            UiCommand.EndTrip -> dispatch(Action.EndTrip)
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun traceRuntime(event: String) {
        Log.i("GuardRuntime", JSONObject().put("event", event).put("elapsedMs", now())
            .put("requestId", starts.active?.id ?: JSONObject.NULL)
            .put("startedMs", (sm.state as? CargoState.Guarding)?.trip?.startedMs ?: JSONObject.NULL)
            .put("visible", uiVisible).put("ending", starts.ending)
            .put("guarding", sm.state is CargoState.Guarding).toString())
    }

    /** Runtime opt-in diagnostics: numeric motion only, never user text or navigation payloads. */
    private fun traceMotion(action: Action, t: Long, previousState: CargoState) {
        if (!Log.isLoggable("MotionTrace", Log.DEBUG)) return
        val signal = (action as? Action.Vehicle)?.signal
        val state = sm.state
        val arrived = (state as? CargoState.Arrived)?.takeIf { previousState !is CargoState.Arrived }
        if (signal !is VehicleSignal.Speed && signal !is VehicleSignal.SteeringAngle &&
            signal !is VehicleSignal.BrakePedal && arrived == null) return
        // Invalid numeric inputs remain inspectable without allowing diagnostics to break dispatch.
        fun number(value: Double?): Any = when {
            value == null -> JSONObject.NULL
            value.isFinite() -> value
            else -> value.toString()
        }
        runCatching {
            val raw = JSONObject()
            when (signal) {
                is VehicleSignal.Speed -> raw.put("kind", "speed").put("mps", number(signal.mps))
                    .put("sourceId", signal.sourceId ?: JSONObject.NULL).put("provenance", signal.provenance.name)
                is VehicleSignal.SteeringAngle -> raw.put("kind", "steering").put("degrees", number(signal.degrees))
                    .put("sourceId", JSONObject.NULL).put("provenance", signal.provenance.name)
                is VehicleSignal.BrakePedal -> raw.put("kind", "brake").put("pressed", signal.pressed)
                    .put("percent", number(signal.percent)).put("sourceId", JSONObject.NULL).put("provenance", signal.provenance.name)
                else -> raw.put("kind", "other").put("sourceId", JSONObject.NULL)
            }
            val snapshot = sm.snapshot
            val row = JSONObject().put("schema", 1).put("tMs", t)
                .put("event", if (arrived == null) "motion" else "arrival")
                .put("raw", raw).put("uiMode", sm.uiMode.name)
                .put("previousGuarding", previousState is CargoState.Guarding)
                .put("model", JSONObject().put("wheelbaseM", motionEstimator.wheelbaseM)
                    .put("steeringRatio", motionEstimator.steeringRatio).put("smoothing", motionEstimator.smoothing)
                    .put("gravityMps2", GEstimator.G).put("maxBrakeDecelMps2", motionEstimator.maxBrakeDecelMps2))
                .put("snapshot", JSONObject().put("gear", snapshot.gear.name)
                    .put("speedMps", number(snapshot.speedMps)).put("steeringDeg", number(snapshot.steeringDeg))
                    .put("brakePressed", snapshot.brakePressed ?: JSONObject.NULL).put("brakePercent", number(snapshot.brakePercent))
                    .put("speedProvenance", snapshot.speedProvenance.name).put("steeringProvenance", snapshot.steeringProvenance.name)
                    .put("brakeProvenance", snapshot.brakeProvenance.name).put("parkedStill", snapshot.isParkedStill))
                .put("state", when (state) {
                    CargoState.Idle -> "IDLE"
                    is CargoState.Offering -> "OFFERING"
                    is CargoState.Guarding -> "GUARDING"
                    is CargoState.Arrived -> "ARRIVED"
                })
            if (state is CargoState.Guarding) {
                val trip = state.trip
                row.put("presetKind", state.preset.kind.name).put("limitG", state.preset.limitG)
                    .put("motionStatus", state.motionStatus.name).put("badge", state.badge.name)
                    .put("trip", JSONObject().put("startedMs", trip.startedMs).put("maxG", trip.maxG)
                        .put("gSamples", trip.gSamples).put("freshLongitudinalSamples", trip.freshLongitudinalSamples)
                        .put("drivingSpeechCount", trip.drivingSpeechCount).put("drivingChimeCount", trip.drivingChimeCount)
                        .put("coachingCount", trip.coachingCount).put("warnCount", trip.warnCount)
                        .put("provenance", JSONArray(trip.motionProvenance.map { it.name }.sorted()))
                        .put("quality", JSONArray(trip.qualityReasons.map { it.name }.sorted())))
                val e = state.lastEstimate
                row.put("estimate", if (e == null) JSONObject.NULL else JSONObject()
                    .put("lateralG", number(e.lateralG)).put("longitudinalG", number(e.longitudinalG))
                    .put("totalG", number(e.totalG)).put("speedMps", number(e.speedMps))
                    .put("lateralAvailable", e.lateralAvailable).put("longitudinalAvailable", e.longitudinalAvailable)
                    .put("longitudinalFresh", e.longitudinalFresh).put("hasData", e.hasData)
                    .put("quality", JSONArray(e.qualityReasons.map { it.name }.sorted())))
            }
            if (arrived != null) {
                val report = arrived.report
                row.put("presetKind", arrived.preset.kind.name).put("report", JSONObject()
                    .put("maxG", report.maxG).put("durationMs", report.durationMs)
                    .put("gSamples", report.gSamples).put("freshLongitudinalSamples", report.freshLongitudinalSamples)
                    .put("drivingSpeechCount", report.drivingSpeechCount).put("drivingChimeCount", report.drivingChimeCount)
                    .put("coachingCount", report.coachingCount).put("warnCount", report.warnCount)
                    .put("audioSuppressionCounts", JSONObject(report.audioSuppressionCounts.mapKeys { it.key.name }))
                    .put("provenance", JSONArray(report.motionProvenance.map { it.name }.sorted()))
                    .put("quality", JSONArray(report.qualityReasons.map { it.name }.sorted()))
                    .put("hasSyntheticInput", report.hasSyntheticInput).put("canCompare", report.canCompare))
            }
            Log.d("MotionTrace", row.toString())
        }.onFailure { Log.w("MotionTrace", "diagnostic_serialization_failed") }
    }

    private fun dispatch(action: Action) {
        if (action is Action.SelectPreset || action is Action.VoicePreset) {
            requestGuard(action)
            return
        }
        if (action == Action.EndTrip) {
            starts.end()
            startTimeout?.cancel()
            if (starts.active == null) graph.stopGuardService()
        }
        reduce(action)
    }

    private fun reduce(action: Action) {
        val t = now()
        val wasParked = _ui.value.interactive
        val previousState = sm.state
        val effects = sm.dispatch(action, t)
        traceMotion(action, t, previousState)
        if (wasParked && sm.uiMode == UiMode.DRIVING) graph.speech.stop()
        val state = sm.state
        if ((action is Action.SelectPreset || action is Action.VoicePreset) && state is CargoState.Guarding && effects.any { it is Effect.ApplyCabin }) graph.recents.record(state.preset)
        // logcat-only (never the on-screen event log): the demo video's G gauge is driven by the app's own estimate.
        if (action is Action.Vehicle && state is CargoState.Guarding) state.lastEstimate?.let { e ->
            Log.d(TAG, String.format(java.util.Locale.US, "G lat=%.3f lon=%.3f total=%.3f limit=%.2f badge=%s kph=%.0f",
                e.lateralG, e.longitudinalG, e.totalG, state.preset.limitG, state.badge, e.speedMps * 3.6))
        }
        _ui.update {
            it.copy(
                state = state,
                uiMode = sm.uiMode,
                snapshot = sm.snapshot,
                offerRemainingMs = (state as? CargoState.Offering)?.let { o -> (o.deadlineMs - t).coerceAtLeast(0) } ?: 0,
                spot = sm.currentSpot,
                mutedOfferCount = sm.mutedTriggerCount,
            )
        }
        val cabinTicket = if (effects.any { it is Effect.ApplyCabin || it == Effect.RestoreCabin }) cabinGate.advance() else 0L
        effects.forEach { handle(it, cabinTicket) }
        if (previousState is CargoState.Guarding && state !is CargoState.Guarding) starts.end()
        if (starts.ending) finishAfterCabinWork()
        updateRuntime()
    }

    private fun handle(effect: Effect, cabinTicket: Long) {
        when (effect) {
            is Effect.Speak -> speak(effect.text, effect.priority)
            is Effect.Chime -> graph.chime.play(effect.kind)
            is Effect.ApplyCabin -> launchCabin {
                cabinMutex.withLock {
                    val active = sm.state as? CargoState.Guarding
                    if (starts.active == null || !cabinGate.isCurrent(cabinTicket) || active?.preset != effect.preset || active.spot != effect.spot) {
                        log("지난 세션의 대기 공조 요청 취소")
                        return@withLock
                    }
                    if (restoreBlocked) {
                        log("이전 캐빈 복원 미확인 → 새 설정 적용 보류")
                        graph.settings.setCabinSessionPending(true)
                        _ui.update { it.copy(cabinNotice = "이전 설정 복원을 확인하지 못해 새 설정 적용을 보류했어요.", cabinRestorePending = true) }
                        val blocked = CabinApplyResult.unsupported(effect.preset.cabin, "이전 설정 복원을 먼저 확인해 주세요")
                        dispatch(Action.CabinApplied(CabinApplyResult(blocked.outcomes.map { it.copy(status = FeatureStatus.FAILED) })))
                        return@withLock
                    }
                    if (!_ui.value.interactive || !sm.canControlCabin(now())) {
                        log("공조 실행 시 P·정차 조건 미충족 → 적용하지 않음")
                        val blocked = CabinApplyResult.unsupported(effect.preset.cabin, "P·정차 확인 필요")
                        dispatch(Action.CabinApplied(CabinApplyResult(blocked.outcomes.map { it.copy(status = FeatureStatus.DRIVING_RESTRICTED) })))
                        return@withLock
                    }
                    val consent = ApplyConsent(emptySeatHeaterAllowed = false)
                    val request = CabinCommandPolicy.prepare(effect.preset.cabin,
                        effect.heldByPassenger || (effect.spot.hasSeat && sm.snapshot.occupied(effect.spot)))
                    _ui.update { it.copy(cabinNotice = "캐빈 설정 결과를 확인하고 있어요", cabinRestorePending = false) }
                    graph.settings.setCabinSessionPending(true)
                    log("캐빈 적용 → ${effect.spot.label}")
                    val result = withContext(Dispatchers.IO) {
                        runCatching { graph.vehicle.applyCabin(request.plan, consent, effect.spot) }
                            .onFailure { Log.w(TAG, "applyCabin failed", it) }
                            .getOrElse { CabinApplyResult(CabinApplyResult.unsupported(request.plan, "제어 응답 실패").outcomes.map { o -> o.copy(status = FeatureStatus.FAILED) }) }
                    }
                    val reported = CabinApplyResult(result.outcomes + request.withheld)
                    reported.outcomes.forEach { o -> log("  ${o.feature}: ${o.status}${if (o.detail.isNotBlank()) " (${o.detail})" else ""}") }
                    if (cabinGate.isCurrent(cabinTicket)) {
                        _ui.update { it.copy(cabinNotice = "종료 시 앱이 바꾼 값만 조건을 확인해 복원해요") }
                        dispatch(Action.CabinApplied(reported))
                    }
                }
            }
            Effect.RestoreCabin -> launchCabin {
                cabinMutex.withLock {
                    if (!cabinGate.isCurrent(cabinTicket)) return@withLock
                    val result = withContext(Dispatchers.IO) {
                        runCatching { graph.vehicle.restoreCabin() }
                            .onFailure { Log.w(TAG, "restoreCabin failed", it) }.getOrNull()
                    }
                    result?.outcomes?.forEach { o -> log("원복 ${o.feature}: ${o.status} ${o.detail}") }
                    val failed = result == null || result.problems.isNotEmpty()
                    restoreBlocked = failed
                    val notice = if (failed) "복원하지 못한 항목이 있어요. P 정차 후 차량 설정을 확인해 주세요."
                        else if (result!!.outcomes.isEmpty()) "복원할 앱 변경값이 없어요. 미적용 항목은 그대로예요."
                        else "앱 변경값의 조건부 복원을 마쳤어요. 외부에서 바뀐 값은 유지해요."
                    if (cabinGate.isCurrent(cabinTicket)) {
                        graph.settings.setCabinSessionPending(failed)
                        _ui.update { it.copy(cabinNotice = notice, cabinRestorePending = failed) }
                    }
                    log(notice)
                }
            }
            is Effect.Log -> log(effect.message)
        }
    }

    private fun speak(text: String, priority: Boolean = false) {
        log("TTS: $text")
        if (!graph.settings.settings.value.ttsEnabled) return
        val requestedWhileParked = sm.snapshot.isParkedStill
        scope.launch {
            if (requestedWhileParked && !sm.canControlCabin(now())) return@launch
            runCatching {
                graph.speech.stop() // Do not build a queue of obsolete coaching or parked status messages.
                graph.speech.speak(text, priority)
            }.onFailure { Log.w(TAG, "speak failed", it) }
        }
    }

    private fun log(text: String) {
        Log.i(TAG, text)
        _ui.update { it.copy(log = (it.log + LogLine(now(), text)).takeLast(60)) }
    }

    private companion object { const val TAG = "CargoVM" }
}
