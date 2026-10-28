package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.port.SpeedArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedArbiterTest {
    @Test
    fun `demo speed suppresses real sources for its hold window`() {
        val a = SpeedArbiter.forSources(realSources = 2) // 0 demo, 1 CarPropertyManager, 2 FusedLocation
        assertTrue(a.accept(1, 0))          // VHAL idles at 0 km/h
        assertTrue(a.accept(0, 1_000))      // demo injects 30 km/h
        assertFalse(a.accept(1, 1_500))     // VHAL 0 km/h must not leak in …
        assertFalse(a.accept(2, 2_000))     // … nor the location estimate
        assertTrue(a.accept(0, 4_000))      // demo keeps going
        assertFalse(a.accept(1, 63_000))    // still inside the 60 s hold from the 4 s sample
        assertTrue(a.accept(1, 64_000))     // demo silent for 60 s → real source is back
    }

    @Test
    fun `car property speed outranks location-derived speed`() {
        val a = SpeedArbiter.forSources(realSources = 2)
        assertTrue(a.accept(2, 0))          // only FusedLocation so far
        assertTrue(a.accept(1, 100))        // CarPropertyManager starts delivering
        assertFalse(a.accept(2, 1_000))     // location estimate is dropped while the car feed is fresh
        assertTrue(a.accept(2, 5_200))      // car feed stale for > 5 s → location takes over again
    }
}
