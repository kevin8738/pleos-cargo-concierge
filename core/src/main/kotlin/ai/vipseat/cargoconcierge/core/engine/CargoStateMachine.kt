package ai.vipseat.cargoconcierge.core.engine

import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.AudioSuppressionReason
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.TripReport
import ai.vipseat.cargoconcierge.core.model.TripStats
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.model.VehicleSnapshot
import ai.vipseat.cargoconcierge.core.physics.GEstimate
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.ChimeKind
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import kotlin.math.abs

enum class BadgeLevel { OK, WARN, OVER }

/** PARKED = full UI allowed; DRIVING = static badge only (distraction optimised). */
enum class UiMode { PARKED, DRIVING }

/** Reception freshness only; these labels do not certify sensor accuracy or transport latency. */
enum class MotionSignalStatus { FRESH, SPEED_ONLY, STALE, DISCONNECTED }

sealed interface CargoState {
    data object Idle : CargoState

    /**
     * Preset sheet is on screen; auto-dismisses at [deadlineMs].
     * [trigger] is the door that opened it (null for a manual tap or an external offer such as a
     * pickup-store geofence); [reason] names the external source and is empty for a manual tap.
     */
    data class Offering(
        val trigger: DoorId?,
        val startedMs: Long,
        val deadlineMs: Long,
        val reason: String = "",
        /** Where the VIP will ride: the trigger door's spot, or the spot picked on the cabin map. */
        val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
    ) : CargoState {
        /** Automatic offers (door / external) count towards the "3 unanswered → mute" rule; manual taps don't. */
        val isAutomatic: Boolean get() = trigger != null || reason.isNotEmpty()
        val triggerKey: String get() = trigger?.name ?: CargoStateMachine.EXTERNAL_TRIGGER
    }

    data class Guarding(
        val preset: CargoPreset,
        val trip: TripStats,
        val cabin: CabinApplyResult? = null,
        val badge: BadgeLevel = BadgeLevel.OK,
        val lastEstimate: GEstimate? = null,
        val lastWarnMs: Long = CargoStateMachine.NEVER,
        val lastCoachMs: Long = CargoStateMachine.NEVER,
        val lastCurveCoachMs: Long = CargoStateMachine.NEVER,
        val lastBumpCoachMs: Long = CargoStateMachine.NEVER,
        val lastBadgeChangeMs: Long = CargoStateMachine.NEVER,
        val sportModeNoticed: Boolean = false,
        /** All driving speech requests admitted this trip (capped by [CargoStateMachine.Config.maxVoiceCoachPerTrip]). */
        val voiceCoachCount: Int = 0,
        /** The navigation app said "arrived" while we were still in D: restoring waits for P. */
        val arrivalPending: String? = null,
        /** Where the VIP rides. */
        val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
        /** The seat was already occupied when the VIP was placed there: the passenger holds it, seat heater/vent stay untouched. */
        val heldByPassenger: Boolean = false,
        val motionStatus: MotionSignalStatus = MotionSignalStatus.STALE,
        val lastAudibleMs: Long = CargoStateMachine.NEVER,
        val lastAudioSuppression: AudioSuppressionReason? = null,
    ) : CargoState

    data class Arrived(val preset: CargoPreset, val report: TripReport, val reason: String, val spot: CargoSpot = CargoSpot.FRONT_PASSENGER) : CargoState
}

/** Side effects the host (ViewModel) must perform. The reducer itself stays pure. */
sealed interface Effect {
    data class Speak(val text: String, val priority: Boolean = false) : Effect
    data class Chime(val kind: ChimeKind) : Effect
    /** Apply [preset]'s plan at [spot]; when [heldByPassenger] the host drops seat heater/vent (never heat a person's seat for cargo). */
    data class ApplyCabin(val preset: CargoPreset, val spot: CargoSpot = CargoSpot.FRONT_PASSENGER, val heldByPassenger: Boolean = false) : Effect
    data object RestoreCabin : Effect
    data class Log(val message: String) : Effect
}

sealed interface Action {
    data class Vehicle(val signal: VehicleSignal) : Action
    data class Navi(val event: NaviEvent) : Action
    data object Tick : Action
    /** Manual tap on the mini chip / "프리셋" button. Never muted, never counted as ignored. */
    data object OpenOffer : Action
    /**
     * F1 ②/③: an automatic offer raised by the host from a signal the reducer cannot see — a
     * pre-boot passenger-door history or a saved pickup-store geofence. Obeys the same suppression
     * rules as a door trigger. [reason] is logged, e.g. "지오펜스: 단골 베이커리".
     */
    data class ExternalOffer(val reason: String) : Action
    data object DismissOffer : Action
    /**
     * Cabin map / sheet chips: where the VIP rides. Idle → remembered for the next offer; Offering → the
     * sheet switches; Guarding while parked → the VIP moves (restore, then apply at the new spot).
     */
    data class SelectSpot(val spot: CargoSpot) : Action
    /** [preset] may be a template or a VIP the user named (`CargoPreset.named`); [spot] null = the sheet's / preferred spot. */
    data class SelectPreset(val preset: CargoPreset, val spot: CargoSpot? = null) : Action
    /** From STT / Gleo: "케이크 실었어" / "트렁크에 수박 실었어". Skips the sheet. */
    data class VoicePreset(val preset: CargoPreset, val spot: CargoSpot? = null) : Action
    data class CabinApplied(val result: CabinApplyResult) : Action
    data object AcknowledgeReport : Action
    data object EndTrip : Action
    data object ResetOfferSuppression : Action
}

/**
 * The whole product in one deterministic reducer: dispatch(action, now) → effects.
 * No Android, no coroutines, no clocks — the host passes `nowMs` in, so tests can drive time.
 *
 * Policies baked in here (all unit-tested):
 *  - F1 suppression: a load door (passenger, rear left/right, trunk) offers the sheet for *its* spot; no
 *    automatic offer while that seat is occupied or within [Config.passengerExitSuppressMs] of someone
 *    sitting there (a passenger just got out); a trigger that times out [Config.ignoredOffersBeforeMute]
 *    times in a row is muted until a preset is chosen or [unmuteTriggers] is called; any sheet closes the
 *    moment the car moves. The user may still pick an occupied seat (the passenger holds the VIP).
 *  - F3 coaching: one shared audible gate across all driving speech and chimes, at most
 *    [Config.maxVoiceCoachPerTrip] spoken lines per trip, never during a high-G manoeuvre
 *    manoeuvre (≥ [Config.emergencyG]) and never for steering-dominated exceedances below
 *    [Config.minCoachSpeedMps] (parking).
 *  - F5 restore only in P: a navigation "arrived" while still in D is acknowledged and the restore
 *    waits for P; a person sitting down on the VIP's seat (empty when chosen) ends a seat-touching preset at once.
 */
class CargoStateMachine(
    private val gEstimator: GEstimator = GEstimator(),
    private val config: Config = Config(),
) {
    data class Config(
        val offerTimeoutMs: Long = 10_000,
        val warnRatio: Double = 0.8,
        val chimeCooldownMs: Long = 5_000,
        val coachCooldownMs: Long = 8_000,
        val curveCoachCooldownMs: Long = 15_000,
        val bumpCoachCooldownMs: Long = 15_000,
        val badgeHoldMs: Long = 3_000,
        /** No automatic offer this long after someone sat in / left the passenger seat. */
        val passengerExitSuppressMs: Long = 30_000,
        /** Automatic offers from one trigger go quiet after this many consecutive time-outs. */
        val ignoredOffersBeforeMute: Int = 3,
        /** All admitted driving speech requests per trip, including navigation and status. */
        val maxVoiceCoachPerTrip: Int = 3,
        /** Prototype burden policy, configurable and not validated as a safety threshold. */
        val maxAudiblePerTrip: Int = 6,
        val audibleCooldownMs: Long = 8_000,
        /** Maximum age of a received motion value. Does not measure SDK transport delay. */
        val motionFreshnessMs: Long = 2_000,
        /** Below this speed a steering-dominated exceedance is a parking manoeuvre: record, don't coach. */
        val minCoachSpeedMps: Double = 15.0 / 3.6,
        /**
         * Prototype high-G suppression threshold for all audio. It does not detect or certify an
         * emergency; calibration and real-world error/latency measurements remain outstanding.
         */
        val emergencyG: Double = 0.6,
    )

    var state: CargoState = CargoState.Idle
        private set
    var snapshot: VehicleSnapshot = VehicleSnapshot()
        private set

    /** Where the next VIP rides unless a door or the user says otherwise (the cabin map on the home screen). */
    var preferredSpot: CargoSpot = CargoSpot.FRONT_PASSENGER
        private set

    /** The spot the UI should highlight: the active session's, else the preferred one. */
    val currentSpot: CargoSpot
        get() = when (val s = state) {
            is CargoState.Guarding -> s.spot
            is CargoState.Offering -> s.spot
            is CargoState.Arrived -> s.spot
            CargoState.Idle -> preferredSpot
        }

    /** Last moment someone was known to be in (or just leaving) each seat. */
    private val lastPresenceMs = HashMap<CargoSpot, Long>()
    private val ignoredOfferStreak = HashMap<String, Int>()
    private var gearReceivedMs: Long? = null
    private var speedReceivedMs: Long? = null
    private var steeringReceivedMs: Long? = null
    private var brakeReceivedMs: Long? = null
    private var sourceDisconnected = false

    val uiMode: UiMode get() = if (snapshot.isDriving) UiMode.DRIVING else UiMode.PARKED

    /** Permission based on freshly received P and stationary speed, not merely cached values. */
    fun canControlCabin(nowMs: Long): Boolean = !sourceDisconnected && snapshot.isParkedStill &&
        isFresh(gearReceivedMs, nowMs) && isFresh(speedReceivedMs, nowMs)

    /** Triggers (DoorId name or [EXTERNAL_TRIGGER]) whose automatic offers are currently muted. */
    val mutedTriggers: Set<String>
        get() = ignoredOfferStreak.filterValues { it >= config.ignoredOffersBeforeMute }.keys

    val mutedTriggerCount: Int get() = mutedTriggers.size

    /** Settings → "자동 제안 다시 켜기". */
    fun unmuteTriggers() {
        ignoredOfferStreak.clear()
    }

    fun dispatch(action: Action, nowMs: Long): List<Effect> {
        val effects = ArrayList<Effect>(4)
        val previousGuard = state as? CargoState.Guarding
        when (action) {
            is Action.Vehicle -> onVehicle(action.signal, nowMs, effects)
            is Action.Navi -> onNavi(action.event, nowMs, effects)
            Action.Tick -> onTick(nowMs, effects)
            Action.OpenOffer -> if (state is CargoState.Idle && canControlCabin(nowMs)) {
                state = CargoState.Offering(null, nowMs, nowMs + config.offerTimeoutMs, spot = preferredSpot)
                effects += Effect.Log("프리셋 시트 열림 (수동 · ${preferredSpot.label})")
            }
            is Action.ExternalOffer -> if (state is CargoState.Idle && canControlCabin(nowMs)) {
                offerAutomatically(trigger = null, spot = preferredSpot, reason = action.reason, nowMs = nowMs, effects = effects)
            }
            Action.DismissOffer -> if (state is CargoState.Offering) {
                state = CargoState.Idle
                effects += Effect.Log("프리셋 시트 닫힘")
            }
            is Action.SelectSpot -> onSelectSpot(action.spot, nowMs, effects)
            is Action.SelectPreset -> startGuard(action.preset, action.spot, nowMs, effects, spoken = false)
            is Action.VoicePreset -> startGuard(action.preset, action.spot, nowMs, effects, spoken = true)
            is Action.CabinApplied -> onCabinApplied(action.result, effects)
            Action.AcknowledgeReport -> if (state is CargoState.Arrived) {
                state = CargoState.Idle
            }
            Action.EndTrip -> {
                if (state is CargoState.Guarding) effects += Effect.RestoreCabin
                if (state !is CargoState.Idle) effects += Effect.Log("가드 종료")
                state = CargoState.Idle
            }
            Action.ResetOfferSuppression -> {
                unmuteTriggers()
                effects += Effect.Log("자동 제안 음소거 해제")
            }
        }
        limitDrivingAudio(previousGuard, nowMs, effects)
        return effects
    }

    // ---------------------------------------------------------------- vehicle signals

    private fun onVehicle(signal: VehicleSignal, nowMs: Long, effects: MutableList<Effect>) {
        if (signal is VehicleSignal.GearChanged && gearReceivedMs?.let { nowMs < it } == true) return
        if (signal is VehicleSignal.Speed && speedReceivedMs?.let { nowMs <= it } == true) return
        if (signal is VehicleSignal.Speed && !isFresh(speedReceivedMs, nowMs)) {
            gEstimator.reset()
            (state as? CargoState.Guarding)?.let { state = it.copy(lastEstimate = null) }
        }
        val prev = snapshot
        snapshot = snapshot.apply(signal)
        when (signal) {
            is VehicleSignal.GearChanged -> gearReceivedMs = nowMs.takeIf { signal.gear != Gear.UNKNOWN }
            is VehicleSignal.Speed -> speedReceivedMs = nowMs.takeIf { snapshot.speedMps != null }
            is VehicleSignal.SteeringAngle -> steeringReceivedMs = nowMs.takeIf { snapshot.steeringDeg != null }
            is VehicleSignal.BrakePedal -> brakeReceivedMs = nowMs
            is VehicleSignal.SourceStatus -> {
                sourceDisconnected = !signal.connected
                if (!signal.connected) {
                    gearReceivedMs = null
                    speedReceivedMs = null
                    steeringReceivedMs = null
                    brakeReceivedMs = null
                    gEstimator.reset()
                }
                refreshMotionStatus(nowMs)
            }
            else -> Unit
        }

        when (signal) {
            is VehicleSignal.Door -> onDoor(prev, signal, nowMs, effects)
            is VehicleSignal.Speed, is VehicleSignal.SteeringAngle, is VehicleSignal.BrakePedal ->
                onMotion(signal, nowMs, effects)
            is VehicleSignal.SeatOccupancy -> onOccupancy(prev, signal, nowMs, effects)
            is VehicleSignal.CabinTemperature -> (state as? CargoState.Guarding)?.let { g ->
                if (signal.celsius.isFinite()) state = g.copy(trip = g.trip.copy(tempSumC = g.trip.tempSumC + signal.celsius, tempSamples = g.trip.tempSamples + 1))
            }
            is VehicleSignal.DrivingModeChanged -> (state as? CargoState.Guarding)?.let { g ->
                if (signal.mode == DrivingMode.SPORT && !g.sportModeNoticed) {
                    state = g.copy(sportModeNoticed = true)
                    effects += Effect.Speak("스포츠 모드예요. ${g.preset.arrivalNoun}를 위해 에코 모드를 권장해요")
                }
            }
            is VehicleSignal.GearChanged -> onGear(signal, nowMs, effects)
            else -> Unit
        }
        // Leaving a sheet open while the car starts moving would be a distraction: close it.
        if (state is CargoState.Offering && !snapshot.isParkedStill) {
            state = CargoState.Idle
            effects += Effect.Log("주행 시작 → 프리셋 시트 자동 닫힘")
        }
    }

    private fun onGear(signal: VehicleSignal.GearChanged, nowMs: Long, effects: MutableList<Effect>) {
        val g = state as? CargoState.Guarding ?: return
        if (signal.gear != Gear.PARK) {
            if (g.trip.departedMs == null) {
                state = g.copy(trip = g.trip.copy(departedMs = nowMs))
                effects += Effect.Log("출발 감지 (기어 ${signal.gear})")
            }
        } else if (g.arrivalPending != null && canControlCabin(nowMs)) {
            arrive(g, nowMs, reason = "내비 목적지 도착 후 P", effects = effects)
        }
    }

    private fun onOccupancy(prev: VehicleSnapshot, signal: VehicleSignal.SeatOccupancy, nowMs: Long, effects: MutableList<Effect>) {
        val spot = signal.seat
        val occupied = signal.occupied == true
        val wasOccupied = prev.occupied(spot)
        if (occupied || wasOccupied) lastPresenceMs[spot] = nowMs
        if (!occupied || wasOccupied) return // only the sitting-down edge matters below
        when (val s = state) {
            // An automatic offer for this very seat was most likely a passenger getting in, not cargo.
            is CargoState.Offering -> if (s.isAutomatic && s.spot == spot) {
                state = CargoState.Idle
                effects += Effect.Log("${spot.label} 착좌 → 프리셋 시트 닫힘")
            }
            // Someone sat down on the seat that was empty when the VIP was placed there: people first.
            // (A seat already occupied at selection means the passenger is holding the VIP — nothing to do.)
            is CargoState.Guarding -> if (s.spot == spot && !s.heldByPassenger && s.preset.cabin.touchesSeat) {
                state = CargoState.Idle
                effects += Effect.Speak("${spot.label}에 사람이 탔어요. 공조와 열선을 원래대로 돌리고 VIP 모드를 종료할게요")
                effects += Effect.RestoreCabin
                effects += Effect.Log("${spot.label} 착좌 감지 → 사람 우선, 가드 종료")
            }
            else -> Unit
        }
    }

    /** Cabin map / sheet chips. Moving a guarded VIP re-applies the plan at the new spot (parked only). */
    private fun onSelectSpot(spot: CargoSpot, nowMs: Long, effects: MutableList<Effect>) {
        if (!canControlCabin(nowMs)) {
            effects += Effect.Log("기어 P와 정차 신호를 확인한 뒤 VIP 자리를 바꿀 수 있어요")
            return
        }
        preferredSpot = spot
        when (val s = state) {
            is CargoState.Offering -> {
                state = s.copy(spot = spot)
                effects += Effect.Log("VIP 자리: ${spot.label}")
            }
            is CargoState.Guarding -> if (s.spot != spot) {
                val held = spot.hasSeat && snapshot.occupied(spot)
                state = s.copy(spot = spot, heldByPassenger = held, cabin = null)
                effects += Effect.RestoreCabin
                effects += Effect.ApplyCabin(s.preset, spot, held)
                effects += Effect.Log("VIP 자리 이동: ${s.spot.label} → ${spot.label}${if (held) " (동승자가 들고 있음)" else ""}")
            }
            else -> effects += Effect.Log("VIP 자리: ${spot.label}")
        }
    }

    private fun onDoor(prev: VehicleSnapshot, signal: VehicleSignal.Door, nowMs: Long, effects: MutableList<Effect>) {
        val closedEdge = !signal.open && prev.doorOpen(signal.id)
        val spot = CargoSpot.forDoor(signal.id)

        // F1 ①: P + speed 0 + a load door (passenger, rear left/right, trunk) open→closed, subject to the suppression rules.
        if (closedEdge && spot != null && canControlCabin(nowMs) && state is CargoState.Idle) {
            offerAutomatically(trigger = signal.id, spot = spot, reason = "", nowMs = nowMs, effects = effects)
            return
        }
        // F5: P + driver door opens while guarding.
        val g = state as? CargoState.Guarding ?: return
        if (signal.id == DoorId.DRIVER && signal.open && canControlCabin(nowMs)) {
            arrive(g, nowMs, reason = "운전석 도어 열림", effects = effects)
        }
    }

    /**
     * Shows the preset sheet for an automatic trigger unless an F1 suppression rule applies: someone is
     * (or moments ago was) in the seat at [spot], or this trigger has been ignored
     * [Config.ignoredOffersBeforeMute] times in a row. The trunk has no seat, so it skips the seat rules.
     */
    private fun offerAutomatically(trigger: DoorId?, spot: CargoSpot, reason: String, nowMs: Long, effects: MutableList<Effect>) {
        val key = trigger?.name ?: EXTERNAL_TRIGGER
        val label = if (reason.isNotEmpty()) reason else "$key 닫힘"
        val seatMatters = spot.hasSeat
        when {
            seatMatters && snapshot.occupied(spot) ->
                effects += Effect.Log("$label → ${spot.label} 착좌 중, 제안 안 함")
            seatMatters && nowMs - (lastPresenceMs[spot] ?: NEVER) < config.passengerExitSuppressMs ->
                effects += Effect.Log("$label → ${spot.label} 동승자 하차 직후, 제안 안 함")
            (ignoredOfferStreak[key] ?: 0) >= config.ignoredOffersBeforeMute ->
                effects += Effect.Log("$label → ${config.ignoredOffersBeforeMute}회 연속 무응답으로 음소거된 트리거")
            else -> {
                state = CargoState.Offering(trigger, nowMs, nowMs + config.offerTimeoutMs, reason, spot)
                effects += Effect.Chime(ChimeKind.SOFT)
                effects += Effect.Log("$label → ${spot.label} 프리셋 제안 (${config.offerTimeoutMs / 1000}s)")
            }
        }
    }

    private fun onMotion(signal: VehicleSignal, nowMs: Long, effects: MutableList<Effect>) {
        refreshMotionStatus(nowMs)
        val g = state as? CargoState.Guarding ?: return
        if (g.motionStatus == MotionSignalStatus.STALE || g.motionStatus == MotionSignalStatus.DISCONNECTED) return
        val speed = snapshot.speedMps ?: return
        val steering = snapshot.steeringDeg.takeIf { isFresh(steeringReceivedMs, nowMs) }
        val brake = snapshot.brakePressed.takeIf { isFresh(brakeReceivedMs, nowMs) }
        val brakePercent = snapshot.brakePercent.takeIf { isFresh(brakeReceivedMs, nowMs) }
        // Only speed samples advance the longitudinal estimator; steering/brake just refresh lateral.
        val estimate = if (signal is VehicleSignal.Speed) {
            gEstimator.update(speed, steering, brake, brakePercent, nowMs)
        } else {
            val prevLong = g.lastEstimate?.longitudinalG ?: 0.0
            val lat = steering?.let { gEstimator.lateralG(speed, it) } ?: 0.0
            GEstimate(lateralG = lat, longitudinalG = prevLong, speedMps = speed,
                lateralAvailable = steering != null,
                longitudinalAvailable = g.lastEstimate?.longitudinalAvailable == true)
        }
        if (snapshot.isParkedStill) {
            val parked = g.copy(lastEstimate = estimate.takeIf { it.hasData && it.totalG.isFinite() }, badge = BadgeLevel.OK)
            state = parked
            if (parked.arrivalPending != null && canControlCabin(nowMs)) arrive(parked, nowMs, reason = "내비 목적지 도착 후 P", effects = effects)
            return
        }
        if (!estimate.hasData || !estimate.totalG.isFinite()) {
            state = g.copy(lastEstimate = null, badge = BadgeLevel.OK)
            return
        }
        val ratio = estimate.ratioTo(g.preset.limitG)
        val trip = g.trip.copy(
            maxG = maxOf(g.trip.maxG, estimate.totalG),
            gSamples = g.trip.gSamples + 1,
            departedMs = g.trip.departedMs ?: nowMs,
        )
        var next = g.copy(trip = trip, lastEstimate = estimate)
        val level = when {
            ratio >= 1.0 -> BadgeLevel.OVER
            ratio >= config.warnRatio -> BadgeLevel.WARN
            else -> BadgeLevel.OK
        }
        if (level != BadgeLevel.OK) {
            val entering = g.badge == BadgeLevel.OK || (level == BadgeLevel.OVER && g.badge == BadgeLevel.WARN)
            val parkingManoeuvre = speed < config.minCoachSpeedMps && estimate.lateralG >= abs(estimate.longitudinalG)
            next = next.copy(badge = level, lastBadgeChangeMs = nowMs)
            // (a) one soft chime when the guard *enters* WARN/OVER — not on every sample while it lasts
            if (entering && !parkingManoeuvre && nowMs - g.lastWarnMs >= config.chimeCooldownMs) {
                effects += Effect.Chime(ChimeKind.SOFT)
            }
            // (b) voice only once the limit is actually exceeded; capped per trip; never over an emergency
            if (level == BadgeLevel.OVER && !parkingManoeuvre && nowMs - g.lastCoachMs >= config.coachCooldownMs) {
                effects += Effect.Speak(coachingLine(g.preset, estimate))
            }
        } else if (g.badge != BadgeLevel.OK && nowMs - g.lastBadgeChangeMs >= config.badgeHoldMs) {
            next = next.copy(badge = BadgeLevel.OK, lastBadgeChangeMs = nowMs)
        }
        state = next
    }

    private fun coachingLine(preset: CargoPreset, e: GEstimate): String {
        val limit = formatG(preset.limitG)
        return if (e.lateralG >= abs(e.longitudinalG)) {
            "${preset.arrivalNoun} 기준 ${limit}G를 넘었어요. 핸들을 조금만 부드럽게"
        } else if (e.longitudinalG < 0) {
            "${preset.arrivalNoun} 기준 ${limit}G를 넘었어요. 브레이크를 조금만 부드럽게"
        } else {
            "${preset.arrivalNoun} 기준 ${limit}G를 넘었어요. 가속을 조금만 부드럽게"
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun onNavi(event: NaviEvent, nowMs: Long, effects: MutableList<Effect>) {
        val g = state as? CargoState.Guarding ?: return
        when (event) {
            is NaviEvent.SharpCurveAhead -> if (snapshot.isDriving && nowMs - g.lastCurveCoachMs >= config.curveCoachCooldownMs) {
                effects += Effect.Chime(ChimeKind.SOFT)
                effects += Effect.Speak("${event.distanceM}미터 앞 급커브, 조금 줄여 부드럽게")
                state = g.copy(lastCurveCoachMs = nowMs)
            }
            is NaviEvent.SpeedBumpAhead -> if (snapshot.isDriving && nowMs - g.lastBumpCoachMs >= config.bumpCoachCooldownMs) {
                effects += Effect.Speak("${event.distanceM}미터 앞 과속방지턱, 천천히 넘어 주세요")
                state = g.copy(lastBumpCoachMs = nowMs)
            }
            is NaviEvent.DestinationArrived -> when {
                canControlCabin(nowMs) -> arrive(g, nowMs, reason = "내비 목적지 도착", effects = effects)
                g.arrivalPending == null -> {
                    state = g.copy(arrivalPending = event.name ?: "목적지")
                    effects += Effect.Speak("목적지예요. 주차하면 ${g.preset.arrivalNoun} 챙기기를 알려 드릴게요")
                    effects += Effect.Log("내비 도착 수신 · 기어 P 전환까지 원복 대기")
                }
                else -> Unit
            }
            is NaviEvent.WaypointArrived -> effects += Effect.Speak("경유지예요. ${g.preset.arrivalNoun}는 그대로 둘게요")
            is NaviEvent.RoadSpeedLimit, is NaviEvent.Status -> Unit
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startGuard(preset: CargoPreset, requested: CargoSpot?, nowMs: Long, effects: MutableList<Effect>, spoken: Boolean) {
        if (state is CargoState.Arrived) return
        if (!canControlCabin(nowMs)) {
            effects += Effect.Log("기어 P와 정차 신호를 확인한 뒤 VIP 모드를 시작할 수 있어요")
            return
        }
        val spot = requested ?: (state as? CargoState.Offering)?.spot ?: preferredSpot
        // Selecting an occupied seat is allowed: the passenger holds the VIP, so seat heater/vent are left alone.
        val held = spot.hasSeat && snapshot.occupied(spot)
        gEstimator.reset()
        ignoredOfferStreak.clear() // the user engaged: every muted trigger gets another chance
        preferredSpot = spot
        // A replacement may omit features or move to another zone; release the previous plan first.
        if (state is CargoState.Guarding) effects += Effect.RestoreCabin
        state = CargoState.Guarding(preset = preset, trip = TripStats(startedMs = nowMs), spot = spot, heldByPassenger = held)
        effects += Effect.ApplyCabin(preset, spot, held)
        effects += Effect.Chime(ChimeKind.SUCCESS)
        val prefix = if (spoken) "네, " else ""
        val heldNote = if (held) " 동승자가 들고 있으니 열선과 통풍은 그대로 둘게요" else ""
        effects += Effect.Speak("$prefix${preset.displayName} VIP 모드, ${spot.label}. ${formatG(preset.limitG)}G 가드를 시작할게요$heldNote")
        effects += Effect.Log("가드 시작: ${preset.displayName} (${formatG(preset.limitG)}G) · ${spot.label}${if (held) " · 동승자가 들고 있음" else ""}")
    }

    private fun onCabinApplied(result: CabinApplyResult, effects: MutableList<Effect>) {
        val g = state as? CargoState.Guarding ?: return
        state = g.copy(cabin = result)
        val heater = result.status(CabinFeature.SEAT_HEATER)
        when (heater) {
            FeatureStatus.BLOCKED_BY_OCCUPANCY ->
                effects += Effect.Speak("착좌 조건을 확인하지 못해 ${g.spot.label} 열선 설정은 변경하지 않았어요")
            FeatureStatus.SKIPPED_NO_CONSENT ->
                effects += Effect.Speak("열선 설정은 변경하지 않았어요. 적용 결과를 확인해 주세요")
            else -> Unit
        }
        effects += Effect.Log("캐빈 적용: ${result.applied.size}개 성공, ${result.problems.size}개 미적용")
    }

    private fun onTick(nowMs: Long, effects: MutableList<Effect>) {
        refreshMotionStatus(nowMs)
        when (val s = state) {
            is CargoState.Offering -> if (nowMs >= s.deadlineMs) {
                if (s.isAutomatic) {
                    val n = (ignoredOfferStreak[s.triggerKey] ?: 0) + 1
                    ignoredOfferStreak[s.triggerKey] = n
                    if (n >= config.ignoredOffersBeforeMute) {
                        effects += Effect.Log("${s.triggerKey} 자동 제안 ${n}회 연속 무응답 → 음소거 (설정에서 복구)")
                    }
                }
                state = CargoState.Idle
                effects += Effect.Log("프리셋 제안 시간 초과 → 닫힘")
            }
            is CargoState.Guarding -> if (s.badge != BadgeLevel.OK && nowMs - s.lastBadgeChangeMs >= config.badgeHoldMs) {
                state = s.copy(badge = BadgeLevel.OK, lastBadgeChangeMs = nowMs)
            }
            else -> Unit
        }
    }

    private fun arrive(g: CargoState.Guarding, nowMs: Long, reason: String, effects: MutableList<Effect>) {
        if (!canControlCabin(nowMs)) return
        val report = TripReport(
            preset = g.preset,
            maxG = g.trip.maxG,
            avgTempC = g.trip.avgTempC,
            coachingCount = g.trip.coachingCount,
            warnCount = g.trip.warnCount,
            durationMs = nowMs - (g.trip.departedMs ?: g.trip.startedMs),
            spot = g.spot,
            drivingSpeechCount = g.trip.drivingSpeechCount,
            drivingChimeCount = g.trip.drivingChimeCount,
            audioSuppressionCounts = g.trip.audioSuppressionCounts,
            gSamples = g.trip.gSamples,
        )
        state = CargoState.Arrived(g.preset, report, reason, g.spot)
        effects += Effect.Speak("${g.spot.label} ${g.preset.arrivalNoun} 챙기세요", priority = true)
        effects += Effect.RestoreCabin
        effects += Effect.Chime(ChimeKind.SUCCESS)
        effects += Effect.Log("도착 ($reason) · 최대 ${formatG(report.maxG)}G · 코칭 ${report.coachingCount}회")
    }

    private fun isFresh(receivedMs: Long?, nowMs: Long): Boolean =
        receivedMs != null && nowMs >= receivedMs && nowMs - receivedMs <= config.motionFreshnessMs

    private fun motionStatus(nowMs: Long): MotionSignalStatus = when {
        sourceDisconnected -> MotionSignalStatus.DISCONNECTED
        !isFresh(speedReceivedMs, nowMs) || snapshot.speedMps == null -> MotionSignalStatus.STALE
        !isFresh(steeringReceivedMs, nowMs) || snapshot.steeringDeg == null -> MotionSignalStatus.SPEED_ONLY
        else -> MotionSignalStatus.FRESH
    }

    private fun refreshMotionStatus(nowMs: Long) {
        val g = state as? CargoState.Guarding ?: return
        val status = motionStatus(nowMs)
        val unavailable = status == MotionSignalStatus.STALE || status == MotionSignalStatus.DISCONNECTED
        if (unavailable) gEstimator.reset()
        // A missing estimate is distinct from a measured 0 G. Clear obsolete lateral values too.
        val clearEstimate = unavailable || (status == MotionSignalStatus.SPEED_ONLY && g.motionStatus == MotionSignalStatus.FRESH)
        state = g.copy(motionStatus = status,
            lastEstimate = if (clearEstimate) null else g.lastEstimate,
            badge = if (unavailable) BadgeLevel.OK else g.badge)
    }

    /** All app-generated driving audio shares this gate, regardless of its originating feature. */
    private fun limitDrivingAudio(previous: CargoState.Guarding?, nowMs: Long, effects: MutableList<Effect>) {
        if (canControlCabin(nowMs)) return
        val audible = effects.filter { it is Effect.Speak || it is Effect.Chime }
        if (audible.isEmpty()) return
        val g = (state as? CargoState.Guarding) ?: previous
        if (g == null) {
            effects.removeAll(audible.toSet())
            return
        }
        val status = motionStatus(nowMs)
        val reason = when {
            status == MotionSignalStatus.STALE || status == MotionSignalStatus.DISCONNECTED -> AudioSuppressionReason.SIGNAL_UNAVAILABLE
            snapshot.gear == Gear.PARK -> AudioSuppressionReason.SIGNAL_UNAVAILABLE
            (g.lastEstimate?.totalG ?: 0.0) >= config.emergencyG -> AudioSuppressionReason.EMERGENCY
            g.trip.drivingAudibleCount >= config.maxAudiblePerTrip -> AudioSuppressionReason.TRIP_LIMIT
            nowMs - g.lastAudibleMs < config.audibleCooldownMs -> AudioSuppressionReason.COOLDOWN
            else -> null
        }
        val speech = audible.filterIsInstance<Effect.Speak>().firstOrNull()
            ?.takeIf { g.trip.drivingSpeechCount < config.maxVoiceCoachPerTrip }
        val selected = if (reason == null) speech ?: audible.filterIsInstance<Effect.Chime>().firstOrNull() else null
        val suppression = reason ?: if (selected == null) AudioSuppressionReason.VOICE_LIMIT
            else if (audible.size > 1) AudioSuppressionReason.COALESCED else null
        val suppressed = audible.size - if (selected == null) 0 else 1
        effects.removeAll(audible.toSet())
        if (selected != null) effects += selected
        val isSpeech = selected is Effect.Speak
        val isChime = selected is Effect.Chime
        val trip = g.trip.copy(
            drivingSpeechCount = g.trip.drivingSpeechCount + if (isSpeech) 1 else 0,
            drivingChimeCount = g.trip.drivingChimeCount + if (isChime) 1 else 0,
            coachingCount = g.trip.coachingCount + if (isSpeech) 1 else 0,
            warnCount = g.trip.warnCount + if (isChime) 1 else 0,
            audioSuppressionCounts = if (suppression == null) g.trip.audioSuppressionCounts else
                g.trip.audioSuppressionCounts + (suppression to ((g.trip.audioSuppressionCounts[suppression] ?: 0) + suppressed)),
        )
        if (state is CargoState.Guarding) state = (state as CargoState.Guarding).copy(
            trip = trip,
            lastAudibleMs = if (selected != null) nowMs else g.lastAudibleMs,
            lastCoachMs = if (isSpeech) nowMs else g.lastCoachMs,
            lastWarnMs = if (isChime) nowMs else g.lastWarnMs,
            voiceCoachCount = trip.drivingSpeechCount,
            lastAudioSuppression = suppression,
        )
        if (suppression != null && suppression != g.lastAudioSuppression) {
            effects += Effect.Log("주행 안내 억제: $suppression ($suppressed 요청) · 허용 음성 ${trip.drivingSpeechCount}, 효과음 ${trip.drivingChimeCount}")
        }
    }

    companion object {
        /** "Never happened" for cooldown timestamps: far enough in the past that any clock passes every cooldown. */
        const val NEVER: Long = Long.MIN_VALUE / 4
        const val EXTERNAL_TRIGGER = "EXTERNAL"

        fun formatG(g: Double): String = String.format(java.util.Locale.US, "%.2f", g)
    }
}
