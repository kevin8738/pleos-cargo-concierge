package ai.vipseat.cargoconcierge.vm

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
import ai.vipseat.cargoconcierge.core.model.VehicleSnapshot
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.voice.VoiceIntent
import ai.vipseat.cargoconcierge.core.voice.VoiceIntentParser
import ai.vipseat.cargoconcierge.demo.DemoPlayer
import ai.vipseat.cargoconcierge.demo.UiCommand
import ai.vipseat.cargoconcierge.di.AppGraph
import ai.vipseat.cargoconcierge.platform.Settings
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LogLine(val atMs: Long, val text: String)

data class UiState(
    val state: CargoState = CargoState.Idle,
    val uiMode: UiMode = UiMode.PARKED,
    val uxRestricted: Boolean = false,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val settings: Settings = Settings(),
    val offerRemainingMs: Long = 0,
    val log: List<LogLine> = emptyList(),
    val vehicleSource: String = "",
    val naviSource: String = "",
    val speechSource: String = "",
    val voiceSource: String = "",
    val sourceDescription: String = "",
    val capabilities: CabinCapabilities? = null,
    val navigating: Boolean = false,
    val demoRunning: Boolean = false,
    val demoCaption: String? = null,
    val demoProgress: Float = 0f,
    val listening: Boolean = false,
    val lastVoiceText: String? = null,
    val showSettings: Boolean = false,
    val showDemoPanel: Boolean = false,
    /** VIPs this driver used before, most recent first (sheet row 1, voice parser tier 1). */
    val recent: List<CargoPreset> = emptyList(),
    /** Where the VIP rides: the active session's spot, else the one picked on the cabin map. */
    val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
    val mutedOfferCount: Int = 0,
    val cabinNotice: String? = null,
    val cabinRestorePending: Boolean = false,
) {
    /** The only question the UI asks: may it show more than the static badge? */
    val interactive: Boolean get() = uiMode == UiMode.PARKED && !uxRestricted
}

/**
 * Host of the pure state machine: feeds it vehicle/navi signals and a 250 ms tick on the main
 * thread, and executes the effects it returns (TTS, chime, cabin commands) through the ports.
 */
class CargoViewModel(private val graph: AppGraph) : ViewModel() {

    private val sm: CargoStateMachine
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val demoPlayer: DemoPlayer
    /** Cabin commands run strictly in order (a spot move is restore → apply). */
    private val cabinMutex = Mutex()
    private val cabinGate = CabinCommandGate()
    private var restoreBlocked = false

    init {
        val s = graph.settings.settings.value
        sm = CargoStateMachine(GEstimator(wheelbaseM = s.wheelbaseM.toDouble(), steeringRatio = s.steeringRatio.toDouble()))
        demoPlayer = DemoPlayer(viewModelScope, DemoActors(graph.mockVehicle, graph.mockNavi) { kind -> dispatch(Action.SelectPreset(CargoPreset.of(kind))) })
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
        viewModelScope.launch { graph.settings.settings.collect { st -> _ui.update { it.copy(settings = st) } } }
        viewModelScope.launch { graph.recents.recents.collect { r -> _ui.update { it.copy(recent = r.map { v -> v.toPreset() }) } } }
        viewModelScope.launch { graph.uxMonitor.requiresDistractionOptimization.collect { r -> _ui.update { it.copy(uxRestricted = r) } } }
        viewModelScope.launch { graph.vehicle.signals.collect { dispatch(Action.Vehicle(it)) } }
        viewModelScope.launch {
            graph.navi.events.collect { e ->
                if (e is NaviEvent.Status) _ui.update { it.copy(navigating = e.navigating) }
                dispatch(Action.Navi(e))
            }
        }
        viewModelScope.launch { graph.demoBridge.uiCommands.collect(::onUiCommand) }
        viewModelScope.launch { demoPlayer.progress.collect { p -> _ui.update { it.copy(demoRunning = p.running, demoCaption = p.caption, demoProgress = if (p.totalMs == 0L) 0f else p.elapsedMs.toFloat() / p.totalMs) } } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.vehicle.connect() }.onFailure { Log.w(TAG, "vehicle connect failed", it) } }
            withContext(Dispatchers.IO) { runCatching { graph.navi.connect() }.onFailure { Log.w(TAG, "navi connect failed", it) } }
            val caps = withContext(Dispatchers.IO) { runCatching { graph.vehicle.capabilities() }.getOrNull() }
            _ui.update { it.copy(capabilities = caps, vehicleSource = graph.vehicle.name, speechSource = graph.speech.name, voiceSource = graph.voice.name) }
            log("연결: ${graph.vehicle.name} / ${graph.navi.name}")
        }
        viewModelScope.launch {
            while (true) {
                delay(250)
                dispatch(Action.Tick)
                _ui.update { it.copy(speechSource = graph.speech.name, voiceSource = graph.voice.name) }
            }
        }
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
        viewModelScope.launch {
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

    private fun dispatch(action: Action) {
        val t = now()
        val wasParked = _ui.value.interactive
        val effects = sm.dispatch(action, t)
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
    }

    private fun handle(effect: Effect, cabinTicket: Long) {
        when (effect) {
            is Effect.Speak -> speak(effect.text, effect.priority)
            is Effect.Chime -> graph.chime.play(effect.kind)
            is Effect.ApplyCabin -> viewModelScope.launch {
                cabinMutex.withLock {
                    val active = sm.state as? CargoState.Guarding
                    if (!cabinGate.isCurrent(cabinTicket) || active?.preset != effect.preset || active.spot != effect.spot) {
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
            Effect.RestoreCabin -> viewModelScope.launch {
                cabinMutex.withLock {
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
        viewModelScope.launch {
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

    override fun onCleared() {
        cabinGate.advance()
        demoPlayer.stop()
        viewModelScope.launch { runCatching { graph.vehicle.disconnect() }; runCatching { graph.navi.disconnect() } }
        graph.speech.stop()
        super.onCleared()
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CargoViewModel(graph) as T
    }

    private companion object { const val TAG = "CargoVM" }
}
