package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.BadgeLevel
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.port.SpeedArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual arbiter → signal → reducer path, with production defaults including EMA alpha 0.5. */
class SpeedHandoffTest {
    private fun driving(): CargoStateMachine = CargoStateMachine().also {
        it.dispatch(Action.Vehicle(VehicleSignal.GearChanged(Gear.PARK)), 0)
        it.dispatch(Action.Vehicle(VehicleSignal.Speed(0.0)), 1)
        it.dispatch(Action.SelectPreset(CargoPreset.COFFEE), 100)
        it.dispatch(Action.Vehicle(VehicleSignal.GearChanged(Gear.DRIVE)), 10_000)
    }

    private fun CargoStateMachine.speed(arbiter: SpeedArbiter, priority: Int, mps: Double, nowMs: Long): List<Effect> {
        val accepted = arbiter.acceptSpeed(priority, VehicleSignal.Speed(mps), nowMs)
        assertNotNull("Expected source $priority to be accepted", accepted)
        assertEquals(priority, accepted!!.sourceId)
        return dispatch(Action.Vehicle(accepted), nowMs)
    }

    private fun CargoStateMachine.guard() = state as CargoState.Guarding

    private fun assertNoDrivingOutput(effects: List<Effect>) {
        assertTrue(effects.none { it is Effect.Speak || it is Effect.Chime })
        assertTrue(effects.none { it is Effect.ApplyCabin || it is Effect.RestoreCabin })
    }

    @Test
    fun `returning higher priority source cannot turn either offset sign into acceleration`() {
        for (offset in listOf(-1.0, 1.0)) {
            val m = driving()
            val arbiter = SpeedArbiter.forSources(2)
            m.speed(arbiter, 2, 9.0, 10_000)
            val effects = m.speed(arbiter, 1, 9.0 + offset, 10_100)

            assertNoDrivingOutput(effects)
            assertEquals(0.0, m.guard().trip.maxG, 0.0)
            assertEquals(BadgeLevel.OK, m.guard().badge)
            assertNull(m.guard().lastEstimate) // A source's first value is not acceleration data.
            val next = m.speed(arbiter, 1, 9.0 + offset, 10_200)
            assertNoDrivingOutput(next)
            assertEquals(0.0, m.guard().lastEstimate!!.longitudinalG, 0.0)
        }
    }

    @Test
    fun `fallback after the production hold window preserves identity and ignores either offset sign`() {
        for (offset in listOf(-1.0, 1.0)) {
            val m = driving()
            val arbiter = SpeedArbiter.forSources(2)
            m.speed(arbiter, 1, 9.0, 10_000)
            assertNull(arbiter.acceptSpeed(2, VehicleSignal.Speed(9.0 + offset), 14_999))
            val effects = m.speed(arbiter, 2, 9.0 + offset, 15_000)

            assertNoDrivingOutput(effects)
            assertEquals(0.0, m.guard().trip.maxG, 0.0)
            assertEquals(BadgeLevel.OK, m.guard().badge)
            assertNull(m.guard().lastEstimate)
            m.speed(arbiter, 2, 9.0 + offset, 15_100)
            assertEquals(0.0, m.guard().lastEstimate!!.longitudinalG, 0.0)
        }
    }

    @Test
    fun `first speed has no longitudinal estimate and does not invent a successful driving sample`() {
        val m = driving()
        val effects = m.speed(SpeedArbiter.forSources(2), 2, 9.0, 10_000)

        assertNoDrivingOutput(effects)
        assertNull(m.guard().lastEstimate)
        assertEquals(0, m.guard().trip.gSamples)
        assertEquals(0.0, m.guard().trip.maxG, 0.0)
    }

    @Test
    fun `same source acceleration still warns with production smoothing and decays normally`() {
        val m = driving()
        val arbiter = SpeedArbiter.forSources(2)
        m.speed(arbiter, 2, 9.0, 10_000)
        val effects = m.speed(arbiter, 2, 10.0, 10_100)

        assertEquals(0.5 * 1.0 / 0.1 / GEstimator.G, m.guard().lastEstimate!!.longitudinalG, 1e-9)
        assertEquals(BadgeLevel.OVER, m.guard().badge)
        assertEquals(1, effects.filterIsInstance<Effect.Speak>().size)
        m.speed(arbiter, 2, 10.0, 10_200)
        assertEquals(0.5 * 0.5 * 1.0 / 0.1 / GEstimator.G, m.guard().lastEstimate!!.longitudinalG, 1e-9)
    }

    @Test
    fun `handoff clears prior longitudinal smoothing without erasing trip or cabin state`() {
        val m = driving()
        val arbiter = SpeedArbiter.forSources(2)
        val cabin = CabinApplyResult(listOf(FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED)))
        m.dispatch(Action.CabinApplied(cabin), 9_999)
        m.speed(arbiter, 2, 9.0, 10_000)
        m.speed(arbiter, 2, 10.0, 10_100)
        val before = m.guard()

        val effects = m.speed(arbiter, 1, 8.0, 10_200)
        assertNoDrivingOutput(effects)
        assertEquals(before.trip, m.guard().trip)
        assertEquals(before.cabin, m.guard().cabin)
        assertEquals(before.spot, m.guard().spot)
        assertEquals(before.voiceCoachCount, m.guard().voiceCoachCount)
        assertNull(m.guard().lastEstimate)
        m.speed(arbiter, 1, 8.0, 10_300)
        assertEquals(0.0, m.guard().lastEstimate!!.longitudinalG, 0.0)
        assertEquals(before.trip.maxG, m.guard().trip.maxG, 0.0)
    }

    @Test
    fun `handoff retains available steering based lateral G`() {
        val m = driving()
        val arbiter = SpeedArbiter.forSources(2)
        m.speed(arbiter, 2, 9.0, 10_000)
        m.dispatch(Action.Vehicle(VehicleSignal.SteeringAngle(30.0)), 10_050)
        m.speed(arbiter, 1, 10.0, 10_100)

        val estimate = m.guard().lastEstimate!!
        assertTrue(estimate.lateralAvailable)
        assertFalse(estimate.longitudinalAvailable)
        assertEquals(GEstimator().lateralG(10.0, 30.0), estimate.lateralG, 1e-9)
        assertEquals(0.0, estimate.longitudinalG, 0.0)
    }

    @Test
    fun `rejected lower priority sample does not break the accepted source baseline`() {
        val m = driving()
        val arbiter = SpeedArbiter.forSources(2)
        m.speed(arbiter, 1, 9.0, 10_000)
        assertNull(arbiter.acceptSpeed(2, VehicleSignal.Speed(30.0), 10_050))
        m.speed(arbiter, 1, 10.0, 10_100)
        assertEquals(0.5 * 1.0 / 0.1 / GEstimator.G, m.guard().lastEstimate!!.longitudinalG, 1e-9)
    }
}
