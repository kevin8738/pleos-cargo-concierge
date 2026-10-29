package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.model.MotionProvenance
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.model.withMotionProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionProvenanceTest {
    @Test fun `unknown environment cannot erase known synthetic origin on any motion type`() {
        val signals = listOf(
            VehicleSignal.Speed(12.0, 2) to VehicleSignal.Speed(12.0, 2, MotionProvenance.SYNTHETIC),
            VehicleSignal.SteeringAngle(15.0) to VehicleSignal.SteeringAngle(15.0, MotionProvenance.SYNTHETIC),
            VehicleSignal.BrakePedal(true, 25.0) to VehicleSignal.BrakePedal(true, 25.0, MotionProvenance.SYNTHETIC),
        )
        for ((unknown, synthetic) in signals) {
            assertEquals(synthetic, synthetic.withMotionProvenance(MotionProvenance.UNKNOWN))
            assertEquals(synthetic, unknown.withMotionProvenance(MotionProvenance.SYNTHETIC))
            assertEquals(unknown, unknown.withMotionProvenance(MotionProvenance.UNKNOWN))
        }
    }
}
