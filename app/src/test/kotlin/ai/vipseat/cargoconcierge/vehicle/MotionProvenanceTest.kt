package ai.vipseat.cargoconcierge.vehicle

import ai.vipseat.cargoconcierge.core.model.MotionProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionProvenanceTest {
    @Test fun `only verified AVD product and API pair is labelled synthetic`() {
        assertEquals(MotionProvenance.SYNTHETIC, vehicleMotionProvenance("crp_avd_arm64", 34))
        assertEquals(MotionProvenance.UNKNOWN, vehicleMotionProvenance("crp_avd_arm64", 35))
        assertEquals(MotionProvenance.UNKNOWN, vehicleMotionProvenance("real_vehicle", 34))
        assertEquals(MotionProvenance.UNKNOWN, vehicleMotionProvenance("", 0))
    }
}
