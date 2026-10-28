package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.physics.CargoPhysics
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every per-category limit to the derivation or reference value that justifies it
 * (`submission/근거_EVIDENCE.md`). If someone tunes a limit, this test says which argument broke.
 *
 * Reference values used:
 *  - Standing rail passengers (British Railways, quasi-static lateral): ~0.10 g without discomfort, ~0.12 g uncomfortable.
 *  - Bus passengers (NTU): 1.5 / 1.75 / 2.0 m/s² rated uncomfortable / very / extremely uncomfortable.
 *  - Seated, belted car passengers: 2 m/s² often "too slow", 3 m/s² balanced (test-track study) — the ceiling.
 *  - 도로의 구조·시설 기준에 관한 규칙 제19조: minimum curve radius 30 m at design speed 30 km/h.
 */
class CargoLimitRationaleTest {
    private val standingUncomfortableG = 0.12
    private val busUncomfortableG = CargoPhysics.mps2ToG(1.5)      // 0.153
    private val busExtremelyUncomfortableG = CargoPhysics.mps2ToG(2.0) // 0.204
    private val seatedBalancedG = CargoPhysics.mps2ToG(3.0)        // 0.306
    private val muCardboardOnFabricLowerBound = 0.3                 // to be measured 11/1–11/8 (tilt test)

    @Test
    fun `liquids 0_12 g lies between the transient and static spill thresholds of a 12 oz cup and a 1 cm-headspace soup tub`() {
        val cupTransient = CargoPhysics.spillTransientG(freeboardCm = 1.0, diameterCm = 9.0) // 0.111
        val cupStatic = CargoPhysics.spillStaticG(1.0, 9.0)                                   // 0.222
        val tubStatic = CargoPhysics.spillStaticG(freeboardCm = 1.0, diameterCm = 16.0)       // 0.125
        for (p in listOf(CargoPreset.COFFEE, CargoPreset.HOT_FOOD)) {
            assertTrue("${p.kind}: below worst-case transient spill ${"%.3f".format(cupTransient)}", p.limitG >= cupTransient)
            assertTrue("${p.kind}: a wide 1 cm-headspace tub spills statically at ${"%.3f".format(tubStatic)}", p.limitG <= tubStatic)
            assertTrue(p.limitG <= cupStatic)
            assertTrue(p.limitG <= standingUncomfortableG)
        }
    }

    @Test
    fun `cake and groceries 0_15 g is half the sliding onset at mu 0_3 and the bus 'uncomfortable' level`() {
        for (p in listOf(CargoPreset.CAKE, CargoPreset.GROCERIES)) {
            assertEquals(CargoPhysics.slideG(muCardboardOnFabricLowerBound) / 2.0, p.limitG, 1e-9)
            assertTrue(p.limitG <= busUncomfortableG)
        }
    }

    @Test
    fun `fragile 0_12 g keeps at least a 2x margin under the tipping threshold of an 8 x 30 cm vase`() {
        val tip = CargoPhysics.tipG(baseWidthCm = 8.0, comHeightCm = 15.0) // 0.267
        assertTrue(CargoPreset.FRAGILE.limitG <= tip / 2.0)
        assertTrue(CargoPreset.FRAGILE.limitG <= standingUncomfortableG)
    }

    @Test
    fun `general, pet and flowers 0_20 g is the bus 'extremely uncomfortable' level and under the seated-passenger band`() {
        for (p in listOf(CargoPreset.GENERAL, CargoPreset.PET, CargoPreset.FLOWERS)) {
            assertTrue("${p.kind}", p.limitG <= busExtremelyUncomfortableG)
            assertTrue(p.limitG < seatedBalancedG)
        }
    }

    @Test
    fun `electronics 0_25 g stays under the sliding onset and under what belted passengers call balanced`() {
        assertTrue(CargoPreset.ELECTRONICS.limitG < CargoPhysics.slideG(muCardboardOnFabricLowerBound))
        assertTrue(CargoPreset.ELECTRONICS.limitG < seatedBalancedG)
    }

    @Test
    fun `every limit is meaningful - above a gentle lane change, below an urban intersection turn`() {
        val laneChange = 0.05
        val intersection = CargoPhysics.curveG(speedKph = 30.0, radiusM = 20.0) // 0.354
        for (p in CargoPreset.ALL) {
            assertTrue("${p.kind}", p.limitG > laneChange)
            assertTrue("${p.kind}", p.limitG < intersection)
        }
    }

    @Test
    fun `the legal minimum radius for a 30 km per h road is itself a 0_24 g curve at design speed`() {
        assertEquals(30.0, GEstimator.SHARP_CURVE_RADIUS_M, 0.0)
        assertEquals(0.236, CargoPhysics.curveG(30.0, 30.0), 0.001)
        assertEquals(0.210, CargoPhysics.curveG(20.0, 15.0), 0.001)
    }

    @Test
    fun `recommended speeds follow from v = sqrt(limit g R) on that 30 m curve`() {
        assertEquals(20, GEstimator.recommendedKph(0.12))
        assertEquals(25, GEstimator.recommendedKph(0.15))
        assertEquals(30, GEstimator.recommendedKph(0.20))
        assertEquals(30, GEstimator.recommendedKph(0.25))
    }
}
