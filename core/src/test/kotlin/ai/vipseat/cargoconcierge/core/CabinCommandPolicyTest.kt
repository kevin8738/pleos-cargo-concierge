package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.CabinCommandGate
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.port.CabinCommandPolicy
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import org.junit.Assert.*
import org.junit.Test

class CabinCommandPolicyTest {
    @Test fun hotFoodCannotEnableUnvalidatedHeaterEvenWithConsent() {
        val request = CabinCommandPolicy.prepare(CargoPreset.HOT_FOOD.cabin, heldByPassenger = false)
        assertNull(request.plan.seatHeaterLevel)
        assertEquals(FeatureStatus.PENDING_VALIDATION, request.withheld.single().status)
        assertEquals(CabinFeature.SEAT_HEATER, request.withheld.single().feature)
        assertEquals(0, request.plan.fanLevel)
    }
    @Test fun heaterOffIsPreservedButPassengerSeatCommandsAreRemoved() {
        val cake = CargoPreset.CAKE.cabin
        assertEquals(0, CabinCommandPolicy.prepare(cake, false).plan.seatHeaterLevel)
        val held = CabinCommandPolicy.prepare(cake, true).plan
        assertNull(held.seatHeaterLevel)
        assertNull(held.seatVentLevel)
        assertEquals(cake.temperatureC, held.temperatureC)
    }
    @Test fun everyPositiveHeaterLevelIsWithheldWithoutChangingUnrelatedPlan() {
        for (level in 1..3) {
            val original = CabinPlan(temperatureC = 22.0, seatHeaterLevel = level)
            assertEquals(original.copy(seatHeaterLevel = null), CabinCommandPolicy.prepare(original, false).plan)
        }
    }
    @Test fun endedOrReplacedSessionCannotApplyQueuedCabinOrAcceptItsResult() {
        val gate = CabinCommandGate()
        val first = gate.advance()
        assertTrue(gate.isCurrent(first))
        gate.advance() // end or restore invalidates a queued apply
        assertFalse(gate.isCurrent(first))
        val samePresetNewSession = gate.advance()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(samePresetNewSession))
    }
}
