package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MockVehicleTest {

    @Test
    fun `cake preset cools the passenger zone and restore brings it back`() = runTest {
        val v = MockVehicle()
        v.connect()
        val r = v.applyCabin(CargoPreset.CAKE.cabin, ApplyConsent(emptySeatHeaterAllowed = false))
        assertEquals(FeatureStatus.APPLIED, r.status(CabinFeature.TEMPERATURE))
        assertEquals(18.0, v.cabin.tempC, 1e-9)
        assertEquals(3, v.cabin.fan)
        // heater level 0 never needs consent
        assertEquals(FeatureStatus.APPLIED, r.status(CabinFeature.SEAT_HEATER))
        v.restoreCabin()
        assertEquals(22.0, v.cabin.tempC, 1e-9)
        assertEquals(1, v.restoreCount)
    }

    @Test
    fun `hot food heater is gated by consent then by occupant classification`() = runTest {
        val v = MockVehicle()
        v.setPassengerOccupied(false)
        val noConsent = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(emptySeatHeaterAllowed = false))
        assertEquals(FeatureStatus.SKIPPED_NO_CONSENT, noConsent.status(CabinFeature.SEAT_HEATER))
        assertEquals(FeatureStatus.APPLIED, noConsent.status(CabinFeature.AIRFLOW))
        assertEquals(0, v.cabin.fan)

        val consent = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(emptySeatHeaterAllowed = true))
        assertEquals(FeatureStatus.BLOCKED_BY_OCCUPANCY, consent.status(CabinFeature.SEAT_HEATER))

        v.occupancyBlocksHeater = false
        val ok = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(emptySeatHeaterAllowed = true))
        assertEquals(FeatureStatus.APPLIED, ok.status(CabinFeature.SEAT_HEATER))
        assertEquals(2, v.cabin.heater)
    }

    @Test
    fun `unsupported and denied features are reported not thrown`() = runTest {
        val v = MockVehicle()
        v.supportsRearZone = false
        v.deniedFeatures = setOf(CabinFeature.TEMPERATURE)
        val r1 = v.applyCabin(CargoPreset.GROCERIES.cabin, ApplyConsent(false))
        assertEquals(FeatureStatus.UNSUPPORTED, r1.status(CabinFeature.REAR_TEMPERATURE))
        val r2 = v.applyCabin(CargoPreset.CAKE.cabin, ApplyConsent(false))
        assertEquals(FeatureStatus.PERMISSION_DENIED, r2.status(CabinFeature.TEMPERATURE))
        assertEquals(22.0, v.cabin.tempC, 1e-9)
    }

    @Test
    fun `seat controls are refused while driving`() = runTest {
        val v = MockVehicle()
        v.setPassengerOccupied(true)
        v.setSpeedKph(40.0)
        val r = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(true))
        assertEquals(FeatureStatus.DRIVING_RESTRICTED, r.status(CabinFeature.SEAT_VENT))
    }
}
