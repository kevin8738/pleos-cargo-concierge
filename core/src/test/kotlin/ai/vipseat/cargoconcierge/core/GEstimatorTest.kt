package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.physics.LocationSpeedEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GEstimatorTest {

    @Test
    fun `both source directions discard offset and start the next derivative from the new source`() {
        for ((firstSource, nextSource) in listOf(1 to 2, 2 to 1)) {
            for (offset in listOf(-1.0, 1.0)) {
                val e = GEstimator() // Production EMA alpha 0.5, even with a fresh 100 ms handoff.
                e.update(9.0, null, false, null, 1_000, firstSource)
                val first = e.update(9.0 + offset, null, false, null, 1_100, nextSource)
                assertFalse(first.longitudinalAvailable)
                assertEquals(0.0, first.longitudinalG, 0.0)
                val next = e.update(10.0 + offset, null, false, null, 1_200, nextSource)
                assertTrue(next.longitudinalAvailable)
                assertEquals(0.5 * 1.0 / 0.1 / GEstimator.G, next.longitudinalG, 1e-9)
            }
        }
    }

    @Test
    fun `reordered or invalid other source cannot reset the valid source baseline`() {
        val e = GEstimator()
        e.update(9.0, null, false, null, 1_000, 1)
        e.update(100.0, null, false, null, 999, 2)
        e.update(100.0, null, false, null, 1_000, 2)
        e.update(Double.NaN, null, false, null, 1_050, 2)
        val next = e.update(10.0, null, false, null, 1_100, 1)
        assertEquals(0.5 * 1.0 / 0.1 / GEstimator.G, next.longitudinalG, 1e-9)
    }

    @Test
    fun `a source change inside the minimum derivative interval still establishes its own baseline`() {
        val e = GEstimator()
        e.update(9.0, null, false, null, 1_000, 1)
        val first = e.update(20.0, null, false, null, 1_010, 2)
        assertEquals(20.0, first.speedMps, 0.0)
        assertFalse(first.longitudinalAvailable)
        val next = e.update(20.0, null, false, null, 1_110, 2)
        assertEquals(0.0, next.longitudinalG, 0.0)
    }

    @Test
    fun `independent brake percentage estimate remains available on a speed source handoff`() {
        val e = GEstimator()
        e.update(9.0, null, false, null, 1_000, 1)
        val next = e.update(10.0, null, true, 50.0, 1_100, 2)
        assertTrue(next.longitudinalAvailable)
        assertEquals(-4.0 / GEstimator.G, next.longitudinalG, 1e-9)
    }

    @Test
    fun `out of order speed cannot replace the derivative baseline`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(10.0, null, false, null, 1_000)
        e.update(100.0, null, false, null, 500)
        val next = e.update(11.0, null, false, null, 2_000)
        assertEquals(1.0 / GEstimator.G, next.longitudinalG, 1e-9)
    }

    @Test
    fun `duplicate and very fast samples do not replace the derivative baseline`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(10.0, null, false, null, 1_000)
        e.update(100.0, null, false, null, 1_000)
        e.update(100.0, null, false, null, 1_010)
        val next = e.update(11.0, null, false, null, 2_000)
        assertEquals(1.0 / GEstimator.G, next.longitudinalG, 1e-9)
    }

    @Test
    fun `invalid numerical input cannot poison later finite estimates`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(10.0, null, false, null, 1_000)
        e.update(Double.NaN, Double.NaN, true, Double.NaN, 1_500)
        val next = e.update(11.0, Double.POSITIVE_INFINITY, false, null, 2_000)
        assertTrue(next.totalG.isFinite())
        assertEquals(1.0 / GEstimator.G, next.longitudinalG, 1e-9)
    }

    @Test
    fun `no speed means no lateral g`() {
        val e = GEstimator()
        assertEquals(0.0, e.lateralG(0.0, 180.0), 1e-9)
    }

    @Test
    fun `lateral g follows v2 times curvature with wheelbase 2_9 m and ratio 15`() {
        // 30 km/h, 90° on the wheel → road wheel 6° → κ = tan(6°)/2.9 = 0.03624 1/m → a = 8.333² · κ = 2.517 m/s² = 0.257 g
        val e = GEstimator(wheelbaseM = 2.9, steeringRatio = 15.0)
        val g = e.lateralG(30.0 / 3.6, 90.0)
        assertEquals(0.257, g, 0.005)
        // Symmetric in sign, quadratic in speed.
        assertEquals(g, e.lateralG(30.0 / 3.6, -90.0), 1e-9)
        assertEquals(g * 4, e.lateralG(60.0 / 3.6, 90.0), 0.01)
    }

    @Test
    fun `longitudinal g comes from speed deltas and is smoothed`() {
        val e = GEstimator(smoothing = 1.0) // alpha 1 → raw derivative
        e.update(50.0 / 3.6, 0.0, false, null, 0)
        val est = e.update(30.0 / 3.6, 0.0, true, null, 2_000)
        // (30-50) km/h over 2 s = -2.78 m/s² = -0.283 g
        assertEquals(-0.283, est.longitudinalG, 0.005)
        assertEquals(0.283, est.totalG, 0.005)
    }

    @Test
    fun `brake pedal percentage refines braking when larger than dv dt`() {
        val e = GEstimator(smoothing = 1.0, maxBrakeDecelMps2 = 8.0)
        e.update(10.0, null, false, null, 0)
        val est = e.update(9.9, null, true, 50.0, 1_000) // dv/dt tiny, pedal 50 % → -4 m/s² = -0.41 g
        assertEquals(-0.408, est.longitudinalG, 0.005)
    }

    @Test
    fun `stale samples do not create spikes`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(20.0, null, false, null, 0)
        val est = e.update(0.0, null, false, null, 60_000) // 60 s gap → treated as restart
        assertEquals(0.0, est.longitudinalG, 1e-9)
    }

    @Test
    fun `total g combines lateral and longitudinal`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(8.333, 0.0, false, null, 0)
        val est = e.update(8.333, 90.0, false, null, 1_000)
        assertEquals(est.lateralG, est.totalG, 1e-9)
        assertTrue(est.ratioTo(0.15) > 1.0)
    }

    @Test
    fun `recommended curve speed is rounded to 5 kph`() {
        // sqrt(0.15 g · 30 m) = 6.64 m/s = 23.9 km/h → 25
        assertEquals(25, GEstimator.recommendedKph(0.15))
        assertEquals(20, GEstimator.recommendedKph(0.12))
        assertEquals(30, GEstimator.recommendedKph(0.20))
        assertEquals(10, GEstimator.recommendedKph(0.01))
    }

    @Test
    fun `location speed estimator derives speed from consecutive fixes`() {
        val l = LocationSpeedEstimator(smoothing = 1.0)
        assertNull(l.update(37.5000, 127.0000, 0))
        // ~11.1 m north per 0.0001° latitude
        val v = l.update(37.5001, 127.0000, 1_000)
        assertNotNull(v)
        assertEquals(11.1, v!!, 0.2)
    }
}
