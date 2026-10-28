package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.physics.GEstimator
import ai.vipseat.cargoconcierge.core.physics.LocationSpeedEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GEstimatorTest {

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
