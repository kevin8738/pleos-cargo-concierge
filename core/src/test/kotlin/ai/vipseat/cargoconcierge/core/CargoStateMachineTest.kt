package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.BadgeLevel
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.engine.UiMode
import ai.vipseat.cargoconcierge.core.model.CabinZone
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.TurnDirection
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.ChimeKind
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoStateMachineTest {

    private fun sm(config: CargoStateMachine.Config = CargoStateMachine.Config()) =
        CargoStateMachine(GEstimator(smoothing = 1.0), config)

    private fun CargoStateMachine.v(signal: VehicleSignal, now: Long) = dispatch(Action.Vehicle(signal), now)

    private fun CargoStateMachine.parkWithCargoDoorCycle(door: DoorId = DoorId.PASSENGER, now: Long = 1_000): List<Effect> {
        v(VehicleSignal.GearChanged(Gear.PARK), now)
        v(VehicleSignal.Speed(0.0), now)
        v(VehicleSignal.Door(door, true), now + 100)
        return v(VehicleSignal.Door(door, false), now + 200)
    }

    private fun CargoStateMachine.startDriving(kind: CargoKind = CargoKind.CAKE, now: Long = 2_000): List<Effect> {
        parkWithCargoDoorCycle(now = now)
        val fx = dispatch(Action.SelectPreset(CargoPreset.of(kind)), now + 500)
        // Gear D only: the first speed sample a test sends has no predecessor, so it carries no dv/dt.
        v(VehicleSignal.GearChanged(Gear.DRIVE), now + 1_000)
        return fx
    }

    private fun List<Effect>.spoken() = filterIsInstance<Effect.Speak>().map { it.text }
    private fun List<Effect>.chimes() = filterIsInstance<Effect.Chime>().map { it.kind }

    // ------------------------------------------------------------ trigger

    @Test
    fun `passenger door open then closed while parked offers presets for 10 s`() {
        val m = sm()
        val fx = m.parkWithCargoDoorCycle()
        val s = m.state
        assertTrue(s is CargoState.Offering)
        s as CargoState.Offering
        assertEquals(DoorId.PASSENGER, s.trigger)
        assertEquals(11_200, s.deadlineMs)
        assertEquals(listOf(ChimeKind.SOFT), fx.chimes())
    }

    @Test
    fun `trunk also triggers the offer`() {
        val m = sm()
        m.parkWithCargoDoorCycle(door = DoorId.TRUNK)
        assertTrue(m.state is CargoState.Offering)
    }

    @Test
    fun `driver door does not trigger and door open edge alone does not trigger`() {
        val m = sm()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Door(DoorId.DRIVER, true), 1)
        m.v(VehicleSignal.Door(DoorId.DRIVER, false), 2)
        assertEquals(CargoState.Idle, m.state)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, true), 3)
        assertEquals(CargoState.Idle, m.state)
    }

    @Test
    fun `no offer unless gear is P and speed is zero`() {
        val m = sm()
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 0)
        m.v(VehicleSignal.Speed(0.0), 0)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, true), 1)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, false), 2)
        assertEquals(CargoState.Idle, m.state)

        m.v(VehicleSignal.GearChanged(Gear.PARK), 10)
        m.v(VehicleSignal.Speed(2.0), 10)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, true), 11)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, false), 12)
        assertEquals(CargoState.Idle, m.state)
    }

    @Test
    fun `offer times out after 10 s and dismiss closes it`() {
        val m = sm()
        m.parkWithCargoDoorCycle(now = 1_000)
        m.dispatch(Action.Tick, 11_199)
        assertTrue(m.state is CargoState.Offering)
        m.dispatch(Action.Tick, 11_200)
        assertEquals(CargoState.Idle, m.state)

        m.parkWithCargoDoorCycle(now = 20_000)
        m.dispatch(Action.DismissOffer, 20_500)
        assertEquals(CargoState.Idle, m.state)
    }

    @Test
    fun `offer closes itself when the car starts moving`() {
        val m = sm()
        m.parkWithCargoDoorCycle()
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 2_000)
        assertEquals(CargoState.Idle, m.state)
    }

    // ------------------------------------------------------------ preset / cabin

    @Test
    fun `selecting a preset starts guarding and asks the host to apply the cabin plan`() {
        val m = sm()
        m.parkWithCargoDoorCycle()
        val fx = m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 3_000)
        val s = m.state as CargoState.Guarding
        assertEquals(CargoKind.CAKE, s.preset.kind)
        assertEquals(0.15, s.preset.limitG, 1e-9)
        assertTrue(fx.any { it is Effect.ApplyCabin && it.preset.kind == CargoKind.CAKE })
        assertTrue(fx.spoken().single().contains("0.15G"))
    }

    @Test
    fun `voice preset skips the sheet and speaks a confirmation`() {
        val m = sm()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Speed(0.0), 0)
        val fx = m.dispatch(Action.VoicePreset(CargoPreset.HOT_FOOD), 100)
        assertTrue(m.state is CargoState.Guarding)
        assertTrue(fx.spoken().single().startsWith("네, 뜨거운 음식"))
    }

    @Test
    fun `occupancy-blocked heater is explained by voice`() {
        val m = sm()
        m.startDriving(CargoKind.HOT_FOOD)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        val fx = m.dispatch(Action.CabinApplied(CabinApplyResult(listOf(
            FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.BLOCKED_BY_OCCUPANCY),
            FeatureOutcome(CabinFeature.FAN, FeatureStatus.APPLIED),
        ))), 5_000)
        assertTrue(fx.spoken().single().contains("열선 설정은 변경하지 않았어요"))
        assertFalse(fx.spoken().single().contains("보온할게요"))
        assertEquals(FeatureStatus.BLOCKED_BY_OCCUPANCY, (m.state as CargoState.Guarding).cabin?.status(CabinFeature.SEAT_HEATER))
    }

    // ------------------------------------------------------------ driving monitor

    @Test
    fun `80 percent of the limit raises WARN badge with a soft chime, no speech`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        // 45° on the wheel @ 30 km/h → 3° road wheel → 0.128 g = 85 % of the 0.15 g cake limit.
        val fx = m.v(VehicleSignal.SteeringAngle(45.0), 4_100)
        val s = m.state as CargoState.Guarding
        assertEquals(BadgeLevel.WARN, s.badge)
        assertEquals(listOf(ChimeKind.SOFT), fx.chimes())
        assertTrue(fx.spoken().isEmpty())
        assertEquals(1, s.trip.warnCount)
    }

    @Test
    fun `exceeding the limit raises OVER badge and speaks coaching once per cooldown`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        val fx = m.v(VehicleSignal.SteeringAngle(100.0), 4_100) // ≈0.29 g
        assertEquals(BadgeLevel.OVER, (m.state as CargoState.Guarding).badge)
        assertTrue(fx.spoken().single().contains("0.15G를 넘었어요"))
        // Within cooldown: badge stays OVER but nothing new is spoken.
        val fx2 = m.v(VehicleSignal.SteeringAngle(110.0), 5_000)
        assertTrue(fx2.spoken().isEmpty())
        assertEquals(1, (m.state as CargoState.Guarding).trip.coachingCount)
        // After cooldown it coaches again.
        m.v(VehicleSignal.Speed(30.0 / 3.6), 13_000)
        val fx3 = m.v(VehicleSignal.SteeringAngle(110.0), 13_001)
        assertEquals(1, fx3.spoken().size)
    }

    @Test
    fun `badge returns to OK after calm driving`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        m.v(VehicleSignal.SteeringAngle(100.0), 4_100)
        m.v(VehicleSignal.SteeringAngle(0.0), 4_200)
        assertEquals(BadgeLevel.OVER, (m.state as CargoState.Guarding).badge) // held for 3 s
        m.dispatch(Action.Tick, 7_200)
        assertEquals(BadgeLevel.OK, (m.state as CargoState.Guarding).badge)
    }

    @Test
    fun `hard braking counts as longitudinal g`() {
        val m = sm()
        m.startDriving(CargoKind.COFFEE) // 0.12 g
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        m.v(VehicleSignal.BrakePedal(true, 60.0), 4_900)
        val fx = m.v(VehicleSignal.Speed(12.0 / 3.6), 5_000) // -5 m/s² = 0.51 g
        assertEquals(BadgeLevel.OVER, (m.state as CargoState.Guarding).badge)
        assertTrue(fx.spoken().single().contains("브레이크"))
        assertTrue((m.state as CargoState.Guarding).trip.maxG > 0.4)
    }

    @Test
    fun `ui mode is DRIVING whenever moving or in a driving gear`() {
        val m = sm()
        assertEquals(UiMode.PARKED, m.uiMode)
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 0)
        assertEquals(UiMode.DRIVING, m.uiMode)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 1)
        m.v(VehicleSignal.Speed(0.0), 1)
        assertEquals(UiMode.PARKED, m.uiMode)
        m.v(VehicleSignal.Speed(1.0), 2)
        assertEquals(UiMode.DRIVING, m.uiMode)
    }

    @Test
    fun `sport mode is noticed once with a comfort suggestion`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        val fx = m.v(VehicleSignal.DrivingModeChanged(DrivingMode.SPORT), 5_000)
        assertTrue(fx.spoken().single().contains("에코 모드"))
        assertTrue(m.v(VehicleSignal.DrivingModeChanged(DrivingMode.SPORT), 6_000).spoken().isEmpty())
    }

    // ------------------------------------------------------------ navigation

    @Test
    fun `sharp curve ahead is coached with the preset speed`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        val fx = m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "demo")), 5_000)
        assertEquals(listOf("100미터 앞 급커브, 조금 줄여 부드럽게"), fx.spoken())
        assertEquals(1, (m.state as CargoState.Guarding).trip.coachingCount)
        // Cooldown
        assertTrue(m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(50, TurnDirection.RIGHT, "demo")), 6_000).spoken().isEmpty())
    }

    @Test
    fun `curve coaching is silent while parked and when not guarding`() {
        val m = sm()
        assertTrue(m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.LEFT, "demo")), 0).isEmpty())
        m.parkWithCargoDoorCycle()
        m.dispatch(Action.SelectPreset(CargoPreset.FLOWERS), 2_000)
        assertTrue(m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.LEFT, "demo")), 2_500).spoken().isEmpty())
    }

    // ------------------------------------------------------------ arrival

    @Test
    fun `navi destination arrival speaks reminder, restores cabin and builds the report`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE, now = 1_000)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 3_000)
        m.v(VehicleSignal.SteeringAngle(100.0), 3_100)
        m.v(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, 18.0), 3_200)
        m.v(VehicleSignal.CabinTemperature(CabinZone.FRONT_PASSENGER, 20.0), 3_300)
        m.v(VehicleSignal.Speed(0.0), 62_000)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 62_500) // restore happens only in P (v4)
        val fx = m.dispatch(Action.Navi(NaviEvent.DestinationArrived("베이커리")), 63_000)
        val s = m.state as CargoState.Arrived
        assertEquals("조수석 케이크 챙기세요", fx.spoken().single())
        assertTrue(fx.contains(Effect.RestoreCabin))
        assertEquals(19.0, s.report.avgTempC!!, 1e-9)
        assertEquals(1, s.report.coachingCount)
        assertTrue(s.report.maxG > 0.15)
        assertEquals(61_000, s.report.durationMs) // departed at 2_000 (gear D), arrived 63_000
        assertFalse(s.report.withinLimit)
    }

    @Test
    fun `gear P plus driver door open also counts as arrival`() {
        val m = sm()
        m.startDriving(CargoKind.HOT_FOOD)
        m.v(VehicleSignal.Speed(0.0), 50_000)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 50_500)
        val fx = m.v(VehicleSignal.Door(DoorId.DRIVER, true), 51_000)
        assertTrue(m.state is CargoState.Arrived)
        assertEquals("조수석 음식 챙기세요", fx.spoken().single())
        m.dispatch(Action.AcknowledgeReport, 52_000)
        assertEquals(CargoState.Idle, m.state)
    }

    @Test
    fun `driver door while still driving is not an arrival`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(5.0), 4_000)
        m.v(VehicleSignal.Door(DoorId.DRIVER, true), 4_100)
        assertTrue(m.state is CargoState.Guarding)
    }

    @Test
    fun `ending a trip manually restores the cabin`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        val fx = m.dispatch(Action.EndTrip, 9_000)
        assertTrue(fx.contains(Effect.RestoreCabin))
        assertEquals(CargoState.Idle, m.state)
    }

    // ------------------------------------------------------------ v4: offer suppression (F1)

    @Test
    fun `no automatic offer while the passenger seat is occupied`() {
        val m = sm()
        m.v(VehicleSignal.SeatOccupancy(true), 500)
        val fx = m.parkWithCargoDoorCycle(now = 1_000)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(fx.chimes().isEmpty())
    }

    @Test
    fun `no automatic offer within 30 s after a passenger got out`() {
        val m = sm()
        m.v(VehicleSignal.SeatOccupancy(true), 0)
        m.v(VehicleSignal.SeatOccupancy(false), 10_000)   // passenger leaves
        m.parkWithCargoDoorCycle(now = 20_000)              // door closes 10 s later → suppressed
        assertEquals(CargoState.Idle, m.state)
        m.parkWithCargoDoorCycle(now = 45_000)              // 35 s after leaving → offered again
        assertTrue(m.state is CargoState.Offering)
    }

    @Test
    fun `trunk offers ignore the passenger-seat rules`() {
        val m = sm()
        m.v(VehicleSignal.SeatOccupancy(true), 500)
        m.parkWithCargoDoorCycle(door = DoorId.TRUNK, now = 1_000)
        assertTrue(m.state is CargoState.Offering)
    }

    @Test
    fun `a passenger sitting down closes an open passenger-door offer`() {
        val m = sm()
        m.parkWithCargoDoorCycle()
        m.v(VehicleSignal.SeatOccupancy(true), 1_500)
        assertEquals(CargoState.Idle, m.state)
    }

    @Test
    fun `three unanswered automatic offers mute that trigger until a preset is chosen`() {
        val m = sm()
        for (i in 1..3) {
            m.parkWithCargoDoorCycle(now = 100_000L * i)
            assertTrue(m.state is CargoState.Offering)
            m.dispatch(Action.Tick, 100_000L * i + 20_000)  // time out unanswered
            assertEquals(CargoState.Idle, m.state)
        }
        assertEquals(setOf("PASSENGER"), m.mutedTriggers)
        m.parkWithCargoDoorCycle(now = 500_000)
        assertEquals(CargoState.Idle, m.state)              // muted
        m.parkWithCargoDoorCycle(door = DoorId.TRUNK, now = 600_000)
        assertTrue(m.state is CargoState.Offering)          // other triggers unaffected
        m.dispatch(Action.SelectPreset(CargoPreset.GROCERIES), 600_500)
        assertTrue(m.mutedTriggers.isEmpty())               // engagement resets the streak
    }

    @Test
    fun `manual opens are never counted towards muting`() {
        val m = sm()
        for (i in 1..4) {
            m.v(VehicleSignal.GearChanged(Gear.PARK), 100_000L * i)
            m.v(VehicleSignal.Speed(0.0), 100_000L * i)
            m.dispatch(Action.OpenOffer, 100_000L * i + 1)
            assertTrue(m.state is CargoState.Offering)
            m.dispatch(Action.Tick, 100_000L * i + 20_000)
        }
        assertTrue(m.mutedTriggers.isEmpty())
    }

    @Test
    fun `external offer from a pickup-store geofence opens the sheet while parked only`() {
        val m = sm()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Speed(0.0), 0)
        val fx = m.dispatch(Action.ExternalOffer("지오펜스: 단골 베이커리"), 1_000)
        val s = m.state as CargoState.Offering
        assertEquals("지오펜스: 단골 베이커리", s.reason)
        assertTrue(s.isAutomatic)
        assertEquals(listOf(ChimeKind.SOFT), fx.chimes())
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 2_000)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(m.dispatch(Action.ExternalOffer("지오펜스"), 3_000).chimes().isEmpty())
    }

    // ------------------------------------------------------------ v4: coaching policy (F3)

    @Test
    fun `speech is capped at three and further entries use only the remaining shared chime budget`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        var spoken = 0
        var t = 5_000L
        repeat(5) {
            m.v(VehicleSignal.SteeringAngle(0.0), t)                      // back to OK
            m.dispatch(Action.Tick, t + 3_500)                             // badge hold expires
            m.v(VehicleSignal.Speed(30.0 / 3.6), t + 4_000)
            spoken += m.v(VehicleSignal.SteeringAngle(100.0), t + 4_000).spoken().size // ≈0.29 g → OVER
            t += 12_000
        }
        assertEquals(3, spoken)
        val g = m.state as CargoState.Guarding
        assertEquals(3, g.voiceCoachCount)
        assertEquals(BadgeLevel.OVER, g.badge)
        assertEquals(2, g.trip.warnCount) // first three entries speak instead of stacking a chime
        assertEquals(5, g.trip.drivingAudibleCount)
    }

    @Test
    fun `the chime fires on entering WARN, not on every sample while it lasts`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        assertEquals(1, m.v(VehicleSignal.SteeringAngle(45.0), 4_100).chimes().size)
        assertTrue(m.v(VehicleSignal.SteeringAngle(46.0), 5_000).chimes().isEmpty())
        assertEquals(1, (m.state as CargoState.Guarding).trip.warnCount)
    }

    @Test
    fun `emergency braking is recorded but not coached by voice`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(50.0 / 3.6), 4_000)
        m.v(VehicleSignal.BrakePedal(true, 100.0), 4_900)
        val fx = m.v(VehicleSignal.Speed(20.0 / 3.6), 5_000) // −8.3 m/s² ≈ 0.85 g
        val g = m.state as CargoState.Guarding
        assertTrue(g.trip.maxG > 0.6)
        assertEquals(BadgeLevel.OVER, g.badge)
        assertTrue(fx.spoken().isEmpty())
        assertEquals(0, g.voiceCoachCount)
    }

    @Test
    fun `full-lock parking manoeuvre at walking speed is recorded but not coached`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(10.0 / 3.6), 4_000)
        val fx = m.v(VehicleSignal.SteeringAngle(540.0), 4_100) // ≈0.20 g computed at 10 km/h
        val g = m.state as CargoState.Guarding
        assertTrue(g.lastEstimate!!.lateralG > 0.15)
        assertTrue(fx.spoken().isEmpty())
        assertTrue(fx.chimes().isEmpty())
    }

    // ------------------------------------------------------------ v4: restore only in P, person first (F5)

    @Test
    fun `navi arrival while still driving waits for P before restoring`() {
        val m = sm()
        m.startDriving(CargoKind.CAKE)
        m.v(VehicleSignal.Speed(20.0 / 3.6), 4_000)
        m.v(VehicleSignal.Speed(20.0 / 3.6), 30_000)
        val fx = m.dispatch(Action.Navi(NaviEvent.DestinationArrived("집")), 30_000)
        val g = m.state as CargoState.Guarding
        assertEquals("집", g.arrivalPending)
        assertFalse(fx.contains(Effect.RestoreCabin))
        assertTrue(fx.spoken().single().contains("주차하면"))
        m.v(VehicleSignal.Speed(0.0), 40_000)
        assertTrue(m.state is CargoState.Guarding)          // stopped but still in D
        val fx2 = m.v(VehicleSignal.GearChanged(Gear.PARK), 41_000)
        val a = m.state as CargoState.Arrived
        assertEquals("내비 목적지 도착 후 P", a.reason)
        assertTrue(fx2.contains(Effect.RestoreCabin))
        assertEquals("조수석 케이크 챙기세요", fx2.spoken().single())
    }

    @Test
    fun `a passenger sitting down ends a passenger-seat preset and restores the cabin`() {
        val m = sm()
        m.startDriving(CargoKind.HOT_FOOD)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 4_000)
        val fx = m.v(VehicleSignal.SeatOccupancy(true), 5_000)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(fx.contains(Effect.RestoreCabin))
        assertTrue(fx.spoken().single().contains("사람이 탔어요"))
    }

    @Test
    fun `a passenger sitting down keeps a rear-seat preset guarding`() {
        val m = sm()
        m.startDriving(CargoKind.GROCERIES)
        val fx = m.v(VehicleSignal.SeatOccupancy(true), 5_000)
        assertTrue(m.state is CargoState.Guarding)
        assertFalse(fx.contains(Effect.RestoreCabin))
    }
}
