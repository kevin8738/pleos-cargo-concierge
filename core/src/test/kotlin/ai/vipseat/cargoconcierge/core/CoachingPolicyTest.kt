package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.AudioSuppressionReason
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.TurnDirection
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import org.junit.Assert.*
import org.junit.Test

class CoachingPolicyTest {
    private fun CargoStateMachine.v(signal: VehicleSignal, now: Long) = dispatch(Action.Vehicle(signal), now)
    private fun List<Effect>.audible() = filter { it is Effect.Speak || it is Effect.Chime }
    private fun driving(): CargoStateMachine = CargoStateMachine(GEstimator(smoothing = 1.0)).also {
        it.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        it.v(VehicleSignal.Speed(0.0), 0)
        it.dispatch(Action.SelectPreset(CargoPreset.CAKE), 100)
        it.v(VehicleSignal.GearChanged(Gear.DRIVE), 10_000)
        it.v(VehicleSignal.Speed(30.0 / 3.6), 10_000)
    }

    @Test fun `start requires known P and a known stationary speed for tap and voice`() {
        for (action in listOf(Action.SelectPreset(CargoPreset.CAKE), Action.VoicePreset(CargoPreset.CAKE))) {
            val m = CargoStateMachine()
            assertTrue(m.dispatch(action, 0).none { it is Effect.ApplyCabin })
            m.v(VehicleSignal.GearChanged(Gear.PARK), 1)
            assertTrue(m.dispatch(action, 2).none { it is Effect.ApplyCabin })
            m.v(VehicleSignal.Speed(0.0), 3)
            assertTrue(m.dispatch(action, 4).any { it is Effect.ApplyCabin })
            m.v(VehicleSignal.GearChanged(Gear.DRIVE), 5)
            val before = m.state
            assertTrue(m.dispatch(action, 6).none { it is Effect.ApplyCabin })
            assertEquals(before, m.state)
        }
    }

    @Test fun `selection rejected while driving does not change preferred spot`() {
        val m = driving()
        m.dispatch(Action.SelectSpot(CargoSpot.TRUNK), 10_001)
        assertEquals(CargoSpot.FRONT_PASSENGER, m.preferredSpot)
        assertEquals(CargoSpot.FRONT_PASSENGER, (m.state as CargoState.Guarding).spot)
    }

    @Test fun `cached parked values cannot open an offer start a preset or change spot`() {
        val requests = listOf(Action.OpenOffer, Action.ExternalOffer("pickup"),
            Action.SelectPreset(CargoPreset.CAKE), Action.VoicePreset(CargoPreset.CAKE),
            Action.SelectSpot(CargoSpot.TRUNK))
        for (request in requests) {
            val m = CargoStateMachine()
            m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
            m.v(VehicleSignal.Speed(0.0), 0)
            val fx = m.dispatch(request, 3_000)
            assertEquals(CargoState.Idle, m.state)
            assertEquals(CargoSpot.FRONT_PASSENGER, m.preferredSpot)
            assertTrue(fx.none { it is Effect.ApplyCabin })
            assertTrue(fx.audible().isEmpty())
        }
    }

    @Test fun `speed alone cannot refresh a cached P permission`() {
        val m = CargoStateMachine()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Speed(0.0), 3_000)
        assertTrue(m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 3_001).none { it is Effect.ApplyCabin })
        m.v(VehicleSignal.GearChanged(Gear.PARK), 3_002)
        assertTrue(m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 3_003).any { it is Effect.ApplyCabin })
    }

    @Test fun `late navigation arrival waits for renewed park and speed without speaking over stale data`() {
        val m = CargoStateMachine()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Speed(0.0), 0)
        m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 100)
        val late = m.dispatch(Action.Navi(NaviEvent.DestinationArrived("집")), 10_000)
        assertTrue(m.state is CargoState.Guarding)
        assertFalse(late.contains(Effect.RestoreCabin))
        assertTrue(late.audible().isEmpty())
        assertTrue(m.dispatch(Action.Navi(NaviEvent.WaypointArrived("가게")), 10_001).audible().isEmpty())
        assertTrue(m.v(VehicleSignal.DrivingModeChanged(DrivingMode.SPORT), 10_002).audible().isEmpty())
        val gearOnly = m.v(VehicleSignal.GearChanged(Gear.PARK), 10_100)
        assertFalse(gearOnly.contains(Effect.RestoreCabin))
        assertTrue(m.state is CargoState.Guarding)
        val fresh = m.v(VehicleSignal.Speed(0.0), 10_101)
        assertTrue(m.state is CargoState.Arrived)
        assertTrue(fresh.contains(Effect.RestoreCabin))
    }

    @Test fun `replacing a parked preset restores before applying the new plan including voice spot changes`() {
        for (replacement in listOf(Action.SelectPreset(CargoPreset.COFFEE),
            Action.VoicePreset(CargoPreset.HOT_FOOD, CargoSpot.REAR_RIGHT))) {
            val m = CargoStateMachine()
            m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
            m.v(VehicleSignal.Speed(0.0), 0)
            m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 100)
            val fx = m.dispatch(replacement, 200)
            val commands = fx.filter { it == Effect.RestoreCabin || it is Effect.ApplyCabin }
            assertEquals(2, commands.size)
            assertEquals(Effect.RestoreCabin, commands[0])
            assertTrue(commands[1] is Effect.ApplyCabin)
            val expected = if (replacement is Action.VoicePreset) CargoSpot.REAR_RIGHT else CargoSpot.FRONT_PASSENGER
            assertEquals(expected, (commands[1] as Effect.ApplyCabin).spot)
        }
    }

    @Test fun `inertial navi sport and waypoint candidates share one cooldown`() {
        val m = driving()
        val all = mutableListOf<Effect>()
        all += m.v(VehicleSignal.SteeringAngle(100.0), 10_100)
        all += m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "test")), 10_200)
        all += m.dispatch(Action.Navi(NaviEvent.SpeedBumpAhead(50, "test")), 10_300)
        all += m.v(VehicleSignal.DrivingModeChanged(DrivingMode.SPORT), 10_400)
        all += m.dispatch(Action.Navi(NaviEvent.WaypointArrived("가게")), 10_500)
        assertEquals(1, all.audible().size)
    }

    @Test fun `emergency suppresses chime and navigation as well as inertial speech`() {
        val m = driving()
        m.v(VehicleSignal.BrakePedal(true, 100.0), 10_900)
        val emergency = m.v(VehicleSignal.Speed(0.1), 11_000)
        assertTrue(emergency.audible().isEmpty())
        assertTrue(m.dispatch(Action.Navi(NaviEvent.SpeedBumpAhead(50, "test")), 11_100).audible().isEmpty())
        assertTrue((m.state as CargoState.Guarding).trip.maxG > 0.6)
    }

    @Test fun `stale speed cannot be combined with new steering to coach`() {
        val m = driving()
        val fx = m.v(VehicleSignal.SteeringAngle(100.0), 20_000)
        assertTrue(fx.audible().isEmpty())
        assertNull((m.state as CargoState.Guarding).lastEstimate)
    }

    @Test fun `stale steering and brake do not contaminate a fresh speed sample`() {
        val m = driving()
        m.v(VehicleSignal.SteeringAngle(100.0), 10_100)
        m.v(VehicleSignal.BrakePedal(true, 100.0), 10_200)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 20_000)
        assertNull((m.state as CargoState.Guarding).lastEstimate)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 20_100)
        val e = (m.state as CargoState.Guarding).lastEstimate!!
        assertEquals(0.0, e.lateralG, 0.0001)
        assertEquals(0.0, e.longitudinalG, 0.0001)
    }

    @Test fun `disconnect invalidates stored motion until new samples arrive`() {
        val m = driving()
        m.v(VehicleSignal.SteeringAngle(100.0), 10_100)
        m.v(VehicleSignal.SourceStatus(false, "lost"), 10_200)
        assertNull((m.state as CargoState.Guarding).lastEstimate)
        assertTrue(m.dispatch(Action.Navi(NaviEvent.SpeedBumpAhead(50, "test")), 10_300).audible().isEmpty())
        m.v(VehicleSignal.SourceStatus(true, "back"), 10_400)
        assertNull((m.state as CargoState.Guarding).lastEstimate)
    }

    @Test fun `tick expires a displayed estimate without inventing a zero measurement`() {
        val m = driving()
        m.v(VehicleSignal.SteeringAngle(100.0), 10_100)
        m.dispatch(Action.Tick, 13_000)
        assertNull((m.state as CargoState.Guarding).lastEstimate)
        assertTrue((m.state as CargoState.Guarding).trip.maxG > 0.15)
    }

    @Test fun `navigation speech cannot exceed the per trip speech budget`() {
        val m = driving()
        val all = mutableListOf<Effect>()
        repeat(8) { i ->
            val now = 20_000L + i * 20_000L
            m.v(VehicleSignal.Speed(30.0 / 3.6), now)
            all += m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "test")), now + 1)
        }
        assertEquals(3, all.filterIsInstance<Effect.Speak>().size)
        assertTrue(all.audible().size <= 6)
        val g = m.state as CargoState.Guarding
        assertEquals(3, g.trip.drivingSpeechCount)
        assertEquals(3, g.trip.drivingChimeCount)
        assertTrue(g.trip.audioSuppressionCounts.getValue(AudioSuppressionReason.TRIP_LIMIT) > 0)
    }

    @Test fun `thirty minute mixed event run retains combined counts and suppression reasons in report`() {
        val m = driving()
        val all = mutableListOf<Effect>()
        repeat(1_800) { i ->
            val now = 20_000L + i * 1_000L
            all += m.v(VehicleSignal.Speed(30.0 / 3.6), now)
            all += m.v(VehicleSignal.SteeringAngle(0.0), now)
            all += m.dispatch(Action.Navi(NaviEvent.SharpCurveAhead(100, TurnDirection.RIGHT, "test")), now)
            all += m.dispatch(Action.Navi(NaviEvent.SpeedBumpAhead(50, "test")), now)
            all += m.dispatch(Action.Navi(NaviEvent.WaypointArrived("중간 정차")), now)
            all += m.v(VehicleSignal.DrivingModeChanged(DrivingMode.SPORT), now)
        }
        val g = m.state as CargoState.Guarding
        assertEquals(6, all.audible().size)
        assertEquals(3, all.filterIsInstance<Effect.Speak>().size)
        assertEquals(6, g.trip.drivingAudibleCount)
        assertEquals(3, g.trip.drivingSpeechCount)
        assertEquals(3, g.trip.drivingChimeCount)
        assertTrue(g.trip.audioSuppressionCounts.getValue(AudioSuppressionReason.COOLDOWN) > 0)
        assertTrue(g.trip.audioSuppressionCounts.getValue(AudioSuppressionReason.TRIP_LIMIT) > 0)
        // Repeated over-budget inputs update counters without flooding the event log every second.
        assertTrue(all.filterIsInstance<Effect.Log>().count { it.message.contains("TRIP_LIMIT") } <= 1)
        m.v(VehicleSignal.Speed(0.0), 2_000_000)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 2_000_100)
        m.dispatch(Action.Navi(NaviEvent.DestinationArrived("집")), 2_000_200)
        val report = (m.state as CargoState.Arrived).report
        assertEquals(g.trip.drivingAudibleCount, report.drivingAudibleCount)
        assertEquals(g.trip.audioSuppressionCounts, report.audioSuppressionCounts)
    }

    @Test fun `no measurement report makes no within limit or cargo integrity claim`() {
        val m = CargoStateMachine()
        m.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        m.v(VehicleSignal.Speed(0.0), 0)
        m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 1)
        m.dispatch(Action.Navi(NaviEvent.DestinationArrived("집")), 2)
        val report = (m.state as CargoState.Arrived).report
        assertEquals(0, report.gSamples)
        assertFalse(report.withinLimit)
        assertEquals("유효한 주행 추정 기록이 없어요", report.verdict)
    }

    @Test fun `a speed baseline without steering is not a measured zero G`() {
        val m = driving()
        val g = m.state as CargoState.Guarding
        assertNull(g.lastEstimate)
        assertEquals(0, g.trip.gSamples)
        m.v(VehicleSignal.Speed(30.0 / 3.6), 11_000)
        val measured = m.state as CargoState.Guarding
        assertNotNull(measured.lastEstimate)
        assertEquals(1, measured.trip.gSamples)
        assertEquals(0.0, measured.lastEstimate!!.longitudinalG, 0.0001)
    }

    @Test fun `unknown or disconnected vehicle cannot finish a navigation arrival`() {
        val m = driving()
        m.v(VehicleSignal.SourceStatus(false, "lost"), 10_100)
        val fx = m.dispatch(Action.Navi(NaviEvent.DestinationArrived("집")), 10_200)
        assertTrue(m.state is CargoState.Guarding)
        assertFalse(fx.contains(Effect.RestoreCabin))
        assertTrue(fx.audible().isEmpty())
    }

    @Test fun `settings action re enables ignored automatic offers`() {
        val m = CargoStateMachine()
        repeat(3) { i ->
            val now = i * 20_000L
            m.v(VehicleSignal.GearChanged(Gear.PARK), now)
            m.v(VehicleSignal.Speed(0.0), now)
            m.v(VehicleSignal.Door(DoorId.PASSENGER, true), now + 1)
            m.v(VehicleSignal.Door(DoorId.PASSENGER, false), now + 2)
            m.dispatch(Action.Tick, now + 12_000)
        }
        assertEquals(1, m.mutedTriggerCount)
        m.dispatch(Action.ResetOfferSuppression, 60_000)
        assertEquals(0, m.mutedTriggerCount)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 60_000)
        m.v(VehicleSignal.Speed(0.0), 60_000)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, true), 60_001)
        m.v(VehicleSignal.Door(DoorId.PASSENGER, false), 60_002)
        assertTrue(m.state is CargoState.Offering)
    }
}
