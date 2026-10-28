package ai.vipseat.cargoconcierge.core.engine

import ai.vipseat.cargoconcierge.core.model.CargoKind
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
        /** Spoken F3 coaching lines this trip (capped by [CargoStateMachine.Config.maxVoiceCoachPerTrip]). */
        val voiceCoachCount: Int = 0,
        /** The navigation app said "arrived" while we were still in D: restoring waits for P. */
        val arrivalPending: String? = null,
        /** Where the VIP rides. */
        val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
        /** The seat was already occupied when the VIP was placed there: the passenger holds it, seat heater/vent stay untouched. */
        val heldByPassenger: Boolean = false,
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
 *  - F3 coaching: one soft chime when the guard *enters* WARN/OVER, voice only when the limit is
 *    exceeded, at most [Config.maxVoiceCoachPerTrip] spoken lines per trip, never during an emergency
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
        /** Spoken F3 coaching per trip; chime + badge keep working afterwards. */
        val maxVoiceCoachPerTrip: Int = 3,
        /** Below this speed a steering-dominated exceedance is a parking manoeuvre: record, don't coach. */
        val minCoachSpeedMps: Double = 15.0 / 3.6,
        /**
         * At or above this total g the driver is handling an emergency: never speak over it.
         * 0.6 g ≈ 6 m/s², the deceleration at which UN R13/R13-H lets the emergency stop signal activate.
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

    val uiMode: UiMode get() = if (snapshot.isDriving) UiMode.DRIVING else UiMode.PARKED

    /** Triggers (DoorId name or [EXTERNAL_TRIGGER]) whose automatic offers are currently muted. */
    val mutedTriggers: Set<String>
        get() = ignoredOfferStreak.filterValues { it >= config.ignoredOffersBeforeMute }.keys

    /** Settings → "자동 제안 다시 켜기". */
    fun unmuteTriggers() {
        ignoredOfferStreak.clear()
    }

    fun dispatch(action: Action, nowMs: Long): List<Effect> {
        val effects = ArrayList<Effect>(4)
        when (action) {
            is Action.Vehicle -> onVehicle(action.signal, nowMs, effects)
            is Action.Navi -> onNavi(action.event, nowMs, effects)
            Action.Tick -> onTick(nowMs, effects)
            Action.OpenOffer -> if (state is CargoState.Idle && !snapshot.isDriving) {
                state = CargoState.Offering(null, nowMs, nowMs + config.offerTimeoutMs, spot = preferredSpot)
                effects += Effect.Log("프리셋 시트 열림 (수동 · ${preferredSpot.label})")
            }
            is Action.ExternalOffer -> if (state is CargoState.Idle && !snapshot.isDriving) {
                offerAutomatically(trigger = null, spot = preferredSpot, reason = action.reason, nowMs = nowMs, effects = effects)
            }
            Action.DismissOffer -> if (state is CargoState.Offering) {
                state = CargoState.Idle
                effects += Effect.Log("프리셋 시트 닫힘")
            }
            is Action.SelectSpot -> onSelectSpot(action.spot, effects)
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
        }
        return effects
    }

    // ---------------------------------------------------------------- vehicle signals

    private fun onVehicle(signal: VehicleSignal, nowMs: Long, effects: MutableList<Effect>) {
        val prev = snapshot
        snapshot = snapshot.apply(signal)

        when (signal) {
            is VehicleSignal.Door -> onDoor(prev, signal, nowMs, effects)
            is VehicleSignal.Speed, is VehicleSignal.SteeringAngle, is VehicleSignal.BrakePedal ->
                onMotion(signal, nowMs, effects)
            is VehicleSignal.SeatOccupancy -> onOccupancy(prev, signal, nowMs, effects)
            is VehicleSignal.CabinTemperature -> (state as? CargoState.Guarding)?.let { g ->
                state = g.copy(trip = g.trip.copy(tempSumC = g.trip.tempSumC + signal.celsius, tempSamples = g.trip.tempSamples + 1))
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
        if (state is CargoState.Offering && snapshot.isDriving) {
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
        } else if (g.arrivalPending != null && snapshot.isStationary) {
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
    private fun onSelectSpot(spot: CargoSpot, effects: MutableList<Effect>) {
        preferredSpot = spot
        when (val s = state) {
            is CargoState.Offering -> {
                state = s.copy(spot = spot)
                effects += Effect.Log("VIP 자리: ${spot.label}")
            }
            is CargoState.Guarding -> if (s.spot != spot) {
                if (snapshot.isDriving) {
                    effects += Effect.Log("주행 중에는 VIP 자리를 바꾸지 않아요 (${spot.label} 요청 무시)")
                    return
                }
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
        if (closedEdge && spot != null && snapshot.isParkedStill && state is CargoState.Idle) {
            offerAutomatically(trigger = signal.id, spot = spot, reason = "", nowMs = nowMs, effects = effects)
            return
        }
        // F5: P + driver door opens while guarding.
        val g = state as? CargoState.Guarding ?: return
        if (signal.id == DoorId.DRIVER && signal.open && snapshot.gear == Gear.PARK && snapshot.isStationary) {
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
        val g = state as? CargoState.Guarding ?: return
        val speed = snapshot.speedMps ?: return
        // Only speed samples advance the longitudinal estimator; steering/brake just refresh lateral.
        val estimate = if (signal is VehicleSignal.Speed) {
            gEstimator.update(speed, snapshot.steeringDeg, snapshot.brakePressed, snapshot.brakePercent, nowMs)
        } else {
            val prevLong = g.lastEstimate?.longitudinalG ?: 0.0
            val lat = snapshot.steeringDeg?.let { gEstimator.lateralG(speed, it) } ?: 0.0
            GEstimate(lateralG = lat, longitudinalG = prevLong, speedMps = speed)
        }
        if (!snapshot.isDriving) {
            val parked = g.copy(lastEstimate = estimate, badge = BadgeLevel.OK)
            state = parked
            if (parked.arrivalPending != null) arrive(parked, nowMs, reason = "내비 목적지 도착 후 P", effects = effects)
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
            val emergency = estimate.totalG >= config.emergencyG
            next = next.copy(badge = level, lastBadgeChangeMs = nowMs)
            // (a) one soft chime when the guard *enters* WARN/OVER — not on every sample while it lasts
            if (entering && !parkingManoeuvre && nowMs - g.lastWarnMs >= config.chimeCooldownMs) {
                effects += Effect.Chime(ChimeKind.SOFT)
                next = next.copy(lastWarnMs = nowMs, trip = next.trip.copy(warnCount = next.trip.warnCount + 1))
            }
            // (b) voice only once the limit is actually exceeded; capped per trip; never over an emergency
            if (level == BadgeLevel.OVER && nowMs - g.lastCoachMs >= config.coachCooldownMs) {
                when {
                    parkingManoeuvre -> Unit
                    emergency -> effects += Effect.Log("긴급 조작(${formatG(estimate.totalG)}g) → 음성 코칭 억제, 리포트에만 기록")
                    g.voiceCoachCount >= config.maxVoiceCoachPerTrip ->
                        effects += Effect.Log("주행당 음성 코칭 ${config.maxVoiceCoachPerTrip}회 상한 → 효과음·배지만")
                    else -> {
                        effects += Effect.Speak(coachingLine(g.preset, estimate))
                        next = next.copy(
                            lastCoachMs = nowMs,
                            voiceCoachCount = g.voiceCoachCount + 1,
                            trip = next.trip.copy(coachingCount = next.trip.coachingCount + 1),
                        )
                    }
                }
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
                state = g.copy(lastCurveCoachMs = nowMs, trip = g.trip.copy(coachingCount = g.trip.coachingCount + 1))
            }
            is NaviEvent.SpeedBumpAhead -> if (snapshot.isDriving && nowMs - g.lastBumpCoachMs >= config.bumpCoachCooldownMs) {
                effects += Effect.Speak("${event.distanceM}미터 앞 과속방지턱, 천천히 넘어 주세요")
                state = g.copy(lastBumpCoachMs = nowMs, trip = g.trip.copy(coachingCount = g.trip.coachingCount + 1))
            }
            is NaviEvent.DestinationArrived -> when {
                !snapshot.isDriving -> arrive(g, nowMs, reason = "내비 목적지 도착", effects = effects)
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
        val spot = requested ?: (state as? CargoState.Offering)?.spot ?: preferredSpot
        // Selecting an occupied seat is allowed: the passenger holds the VIP, so seat heater/vent are left alone.
        val held = spot.hasSeat && snapshot.occupied(spot)
        gEstimator.reset()
        ignoredOfferStreak.clear() // the user engaged: every muted trigger gets another chance
        preferredSpot = spot
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
                effects += Effect.Speak("${g.spot.label} 자리가 비어 있어 열선은 켜지지 않았어요. 송풍을 끄고 보온할게요")
            FeatureStatus.SKIPPED_NO_CONSENT ->
                effects += Effect.Speak("빈 좌석 열선은 설정에서 허용하면 켤 수 있어요")
            else -> Unit
        }
        effects += Effect.Log("캐빈 적용: ${result.applied.size}개 성공, ${result.problems.size}개 미적용")
    }

    private fun onTick(nowMs: Long, effects: MutableList<Effect>) {
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
        val report = TripReport(
            preset = g.preset,
            maxG = g.trip.maxG,
            avgTempC = g.trip.avgTempC,
            coachingCount = g.trip.coachingCount,
            warnCount = g.trip.warnCount,
            durationMs = nowMs - (g.trip.departedMs ?: g.trip.startedMs),
            spot = g.spot,
        )
        state = CargoState.Arrived(g.preset, report, reason, g.spot)
        effects += Effect.Speak("${g.spot.label} ${g.preset.arrivalNoun} 챙기세요", priority = true)
        effects += Effect.RestoreCabin
        effects += Effect.Chime(ChimeKind.SUCCESS)
        effects += Effect.Log("도착 ($reason) · 최대 ${formatG(report.maxG)}G · 코칭 ${report.coachingCount}회")
    }

    companion object {
        /** "Never happened" for cooldown timestamps: far enough in the past that any clock passes every cooldown. */
        const val NEVER: Long = Long.MIN_VALUE / 4
        const val EXTERNAL_TRIGGER = "EXTERNAL"

        fun formatG(g: Double): String = String.format(java.util.Locale.US, "%.2f", g)
    }
}
