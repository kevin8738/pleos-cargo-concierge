package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.model.GQualityReason
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import org.junit.Assert.*
import org.junit.Test

class GEstimatorQualityTest {
    @Test fun `overflow is not counted as a valid fresh difference`() {
        val e = GEstimator(smoothing = 1.0)
        e.update(0.0, 0.0, false, null, 1_000)
        val overflow = e.update(Double.MAX_VALUE, 0.0, false, null, 1_100)
        assertFalse(overflow.longitudinalFresh)
        assertTrue(GQualityReason.INVALID_SAMPLE in overflow.qualityReasons)
    }

    @Test fun `only an accepted new time difference is fresh`() {
        val e = GEstimator()
        val first = e.update(10.0, 0.0, false, null, 1_000, 1)
        assertFalse(first.longitudinalFresh)
        assertTrue(GQualityReason.INITIAL_BASELINE in first.qualityReasons)
        assertTrue(e.update(11.0, 0.0, false, null, 2_000, 1).longitudinalFresh)
        assertFalse(e.update(50.0, 0.0, false, null, 2_000, 1).longitudinalFresh)
        assertFalse(e.update(50.0, 0.0, false, null, 2_010, 1).longitudinalFresh)
        val handoff = e.update(50.0, 0.0, false, null, 2_020, 2)
        assertFalse(handoff.longitudinalFresh)
        assertTrue(GQualityReason.SOURCE_HANDOFF in handoff.qualityReasons)
        val gap = e.update(50.0, 0.0, false, null, 8_000, 2)
        assertFalse(gap.longitudinalFresh)
        assertTrue(GQualityReason.INPUT_GAP in gap.qualityReasons)
    }

    @Test fun `invalid and genuinely reordered observations are distinct from duplicates`() {
        val e = GEstimator()
        e.update(10.0, null, false, null, 1_000)
        assertTrue(GQualityReason.INVALID_SAMPLE in e.update(Double.NaN, null, false, null, 1_100).qualityReasons)
        assertTrue(GQualityReason.REORDERED_SAMPLE in e.update(12.0, null, false, null, 999).qualityReasons)
        assertFalse(GQualityReason.REORDERED_SAMPLE in e.update(12.0, null, false, null, 1_000).qualityReasons)
    }

    @Test fun `brake estimate on handoff is available but is not a new speed difference`() {
        val e = GEstimator()
        e.update(10.0, 0.0, false, null, 1_000, 1)
        val next = e.update(12.0, 0.0, true, 50.0, 2_000, 2)
        assertTrue(next.longitudinalAvailable)
        assertFalse(next.longitudinalFresh)
        assertEquals(-4.0 / GEstimator.G, next.longitudinalG, 1e-12)
    }
}
