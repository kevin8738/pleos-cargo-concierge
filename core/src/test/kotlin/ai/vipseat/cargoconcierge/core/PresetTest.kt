package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.model.AirflowMode
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {
    @Test
    fun `limits and cabin plans match the product spec`() {
        assertEquals(0.15, CargoPreset.of(CargoKind.CAKE).limitG, 1e-9)
        assertEquals(18.0, CargoPreset.CAKE.cabin.temperatureC!!, 1e-9)
        assertEquals(0.12, CargoPreset.HOT_FOOD.limitG, 1e-9)
        assertEquals(2, CargoPreset.HOT_FOOD.cabin.seatHeaterLevel)
        assertEquals(AirflowMode.OFF, CargoPreset.HOT_FOOD.cabin.airflow)
        assertEquals(0.20, CargoPreset.FLOWERS.limitG, 1e-9)
        assertEquals(22.0, CargoPreset.FLOWERS.cabin.temperatureC!!, 1e-9)
        assertEquals(AirflowMode.INDIRECT_FLOOR, CargoPreset.FLOWERS.cabin.airflow)
        assertEquals(0.15, CargoPreset.GROCERIES.limitG, 1e-9)
        assertEquals(18.0, CargoPreset.GROCERIES.cabin.rearZoneTemperatureC!!, 1e-9)
        assertEquals(0.12, CargoPreset.COFFEE.limitG, 1e-9)
        assertTrue(CargoPreset.COFFEE.cabin.isEmpty)
        assertEquals(9, CargoPreset.ALL.size)
        assertEquals(0.12, CargoPreset.FRAGILE.limitG, 1e-9)
        assertEquals(0.20, CargoPreset.GENERAL.limitG, 1e-9)
        val custom = CargoPreset.named("도자기", CargoKind.FRAGILE)
        assertEquals("도자기", custom.displayName); assertEquals("도자기", custom.arrivalNoun); assertTrue(custom.isCustom)
        assertEquals(CargoPreset.FRAGILE.cabin, custom.cabin)
        assertTrue(!CargoPreset.named("케이크", CargoKind.CAKE).isCustom)
        assertEquals(CargoKind.entries.toSet(), CargoPreset.ALL.map { it.kind }.toSet())
    }
}
