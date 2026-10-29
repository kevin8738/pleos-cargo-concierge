package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.*
import ai.vipseat.cargoconcierge.core.model.*
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import org.junit.Assert.*
import org.junit.Test

class MotionQualityTest {
    private fun start() = CargoStateMachine(GEstimator(smoothing = 1.0)).also {
        it.v(VehicleSignal.GearChanged(Gear.PARK), 0)
        it.v(VehicleSignal.Speed(0.0), 1)
        it.dispatch(Action.SelectPreset(CargoPreset.CAKE), 2)
        it.v(VehicleSignal.GearChanged(Gear.DRIVE), 100)
    }
    private fun CargoStateMachine.v(s: VehicleSignal, t: Long) = dispatch(Action.Vehicle(s), t)
    private fun CargoStateMachine.guard() = state as CargoState.Guarding
    private fun CargoStateMachine.baseline(provenance: MotionProvenance = MotionProvenance.UNKNOWN) {
        v(VehicleSignal.Speed(10.0, 1, provenance), 1_000)
        v(VehicleSignal.SteeringAngle(0.0), 1_001)
    }
    private fun CargoStateMachine.arrive(t: Long = 2_100, sourceId: Int = 2): TripReport {
        v(VehicleSignal.Speed(0.0, sourceId), t) // Handoff avoids adding a synthetic braking difference.
        v(VehicleSignal.GearChanged(Gear.PARK), t + 1)
        v(VehicleSignal.Door(DoorId.DRIVER, true), t + 2)
        return (state as CargoState.Arrived).report
    }

    @Test fun `cached events while a new source establishes baseline remain informational`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        m.v(VehicleSignal.Speed(11.0, 2), 2_100)
        m.v(VehicleSignal.SteeringAngle(0.0), 2_101)
        m.v(VehicleSignal.BrakePedal(false), 2_102)
        m.v(VehicleSignal.Speed(11.0, 2), 2_200)
        val trip = m.guard().trip
        assertTrue(GQualityReason.SOURCE_HANDOFF in trip.qualityReasons)
        assertFalse(GQualityReason.PARTIAL_AXES in trip.qualityReasons)
        assertEquals(2, trip.freshLongitudinalSamples)
    }

    @Test fun `too early new source speed retains handoff information until valid difference`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        m.v(VehicleSignal.Speed(11.0, 2), 2_100)
        m.v(VehicleSignal.Speed(11.0, 2), 2_110)
        assertFalse(m.guard().lastEstimate!!.longitudinalFresh)
        assertTrue(GQualityReason.SOURCE_HANDOFF in m.guard().lastEstimate!!.qualityReasons)
        assertFalse(GQualityReason.PARTIAL_AXES in m.guard().trip.qualityReasons)
        m.v(VehicleSignal.Speed(11.0, 2), 2_200)
        assertTrue(m.guard().lastEstimate!!.longitudinalFresh)
        assertEquals(2, m.guard().trip.freshLongitudinalSamples)
        val report = m.arrive(2_300, sourceId = 3)
        assertFalse(report.requiresAssessment)
        assertTrue(report.withinLimit)
    }

    @Test fun `high G reason persists after later small values`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0 + 0.6 * GEstimator.G, 1), 2_000)
        m.v(VehicleSignal.Speed(10.0 + 0.6 * GEstimator.G, 1), 2_100)
        assertEquals(0.0, m.guard().lastEstimate!!.totalG, 0.0)
        assertTrue(GQualityReason.HIGH_G_UNASSESSED in m.guard().trip.qualityReasons)
        assertEquals("판정 보류", m.arrive(2_200).comparisonLabel)
    }

    @Test fun `overflow keeps report indeterminate without inventing a valid difference`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(Double.MAX_VALUE, 1), 1_100)
        assertTrue(GQualityReason.INVALID_SAMPLE in m.guard().trip.qualityReasons)
        assertEquals(0, m.guard().trip.freshLongitudinalSamples)
    }

    @Test fun `high G flag starts at policy point six and never changes raw maximum or audio gate`() {
        for (g in listOf(0.599, 0.600, 4.53)) {
            val m = start()
            m.baseline()
            val fx = m.v(VehicleSignal.Speed(10.0 + g * GEstimator.G, 1), 2_000)
            val trip = m.guard().trip
            assertEquals(g, trip.maxG, 1e-12)
            assertEquals(g >= 0.6, GQualityReason.HIGH_G_UNASSESSED in trip.qualityReasons)
            assertEquals(BadgeLevel.OVER, m.guard().badge)
            assertEquals(g < 0.6, fx.any { it is Effect.Speak })
            val report = m.arrive()
            assertEquals(g, report.maxG, 1e-12)
            if (g >= 0.6) assertEquals("판정 보류", report.comparisonLabel)
        }
    }

    @Test fun `first baseline is information only once both axes have a new difference`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        assertTrue(GQualityReason.INITIAL_BASELINE in m.guard().trip.qualityReasons)
        val report = m.arrive()
        assertFalse(report.requiresAssessment)
        assertTrue(report.withinLimit)
        assertEquals(1, report.freshLongitudinalSamples)
    }

    @Test fun `synthetic speed baseline persists through unknown inputs and overrides numeric comparison`() {
        val m = start()
        m.baseline(MotionProvenance.SYNTHETIC)
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        val report = m.arrive()
        assertEquals(setOf(MotionProvenance.UNKNOWN, MotionProvenance.SYNTHETIC), report.motionProvenance)
        assertEquals("실주행 평가 불가", report.comparisonLabel)
        assertFalse(report.withinLimit)
        assertTrue(report.verdict.contains("모의 입력"))
    }

    @Test fun `used synthetic steering and brake events also mark a mixed trip`() {
        for (signal in listOf(VehicleSignal.SteeringAngle(0.0, MotionProvenance.SYNTHETIC),
            VehicleSignal.BrakePedal(false, null, MotionProvenance.SYNTHETIC))) {
            val m = start()
            m.baseline()
            m.v(VehicleSignal.Speed(10.0, 1), 2_000)
            m.v(signal, 2_001)
            assertTrue(m.arrive().hasSyntheticInput)
        }
    }

    @Test fun `synthetic input rejected as reordered does not become used provenance`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        m.v(VehicleSignal.Speed(50.0, 2, MotionProvenance.SYNTHETIC), 1_500)
        val report = m.arrive()
        assertFalse(report.hasSyntheticInput)
        assertTrue(GQualityReason.REORDERED_SAMPLE in report.qualityReasons)
        assertEquals(1, report.freshLongitudinalSamples)
    }

    @Test fun `synthetic verdict still exposes high G assessment limit`() {
        val m = start()
        m.baseline(MotionProvenance.SYNTHETIC)
        m.v(VehicleSignal.Speed(10.0 + 4.53 * GEstimator.G, 1), 2_000)
        val report = m.arrive()
        assertEquals("실주행 평가 불가", report.comparisonLabel)
        assertTrue(report.qualitySummary.contains("고G 구간 · 원인 미확인"))
        assertEquals(4.53, report.maxG, 1e-12)
    }

    @Test fun `speed only estimates retain raw values but defer report comparison`() {
        val m = start()
        m.v(VehicleSignal.Speed(10.0, 1), 1_000)
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        val report = m.arrive()
        assertTrue(GQualityReason.PARTIAL_AXES in report.qualityReasons)
        assertEquals("판정 보류", report.comparisonLabel)
        assertFalse(report.withinLimit)
    }

    @Test fun `received gap survives estimator reset and recovery`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        m.dispatch(Action.Tick, 4_001)
        m.v(VehicleSignal.Speed(10.0, 1), 5_000)
        assertTrue(GQualityReason.INPUT_GAP in m.guard().trip.qualityReasons)
        assertFalse(m.guard().lastEstimate?.longitudinalFresh == true)
        m.v(VehicleSignal.SteeringAngle(0.0), 5_001)
        m.v(VehicleSignal.Speed(10.0, 1), 6_000)
        assertEquals("판정 보류", m.arrive(6_100).comparisonLabel)
    }

    @Test fun `invalid value and early reordered return remain visible in trip`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        m.v(VehicleSignal.Speed(Double.NaN, 1), 2_001)
        m.v(VehicleSignal.Speed(10.0, 1), 2_100)
        m.v(VehicleSignal.Speed(50.0, 1), 2_050)
        val report = m.arrive(2_200)
        assertTrue(GQualityReason.INVALID_SAMPLE in report.qualityReasons)
        assertTrue(GQualityReason.REORDERED_SAMPLE in report.qualityReasons)
        assertEquals("판정 보류", report.comparisonLabel)
    }

    @Test fun `cached steering brake and too early speed do not inflate fresh differences`() {
        val m = start()
        m.baseline()
        m.v(VehicleSignal.Speed(10.0, 1), 2_000)
        val before = m.guard().trip.gSamples
        repeat(3) { m.v(VehicleSignal.SteeringAngle(0.0), 2_001L + it) }
        m.v(VehicleSignal.BrakePedal(false), 2_005)
        m.v(VehicleSignal.Speed(10.0, 1), 2_010)
        m.v(VehicleSignal.Speed(10.0, 1), 2_010) // Existing duplicate rejection.
        assertEquals(before + 5, m.guard().trip.gSamples)
        assertEquals(1, m.guard().trip.freshLongitudinalSamples)
        assertFalse(m.guard().lastEstimate!!.longitudinalFresh)
        assertFalse(GQualityReason.REORDERED_SAMPLE in m.guard().trip.qualityReasons)
    }

    @Test fun `new trip resets quality and used provenance`() {
        val m = start()
        m.baseline(MotionProvenance.SYNTHETIC)
        m.v(VehicleSignal.Speed(60.0, 1), 2_000)
        m.arrive()
        m.dispatch(Action.AcknowledgeReport, 2_200)
        m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 2_201)
        assertTrue(m.guard().trip.qualityReasons.isEmpty())
        assertTrue(m.guard().trip.motionProvenance.isEmpty())
        assertEquals(0, m.guard().trip.freshLongitudinalSamples)
    }

    @Test fun `no data and synthetic no data do not claim comparison`() {
        val empty = TripReport(CargoPreset.CAKE, 0.0, null, 0, 0, 0)
        assertEquals("기록 없음", empty.comparisonLabel)
        assertEquals("실주행 평가 불가", empty.copy(motionProvenance = setOf(MotionProvenance.SYNTHETIC)).comparisonLabel)
    }
}
