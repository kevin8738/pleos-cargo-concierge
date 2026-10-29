package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.*
import ai.vipseat.cargoconcierge.core.model.*
import org.junit.Assert.*
import org.junit.Test

class VacancyLossTest {
    private fun start(preset: CargoPreset = CargoPreset.CAKE): CargoStateMachine = CargoStateMachine().also {
        it.dispatch(Action.Vehicle(VehicleSignal.GearChanged(Gear.PARK)), 0)
        it.dispatch(Action.Vehicle(VehicleSignal.Speed(0.0)), 0)
        it.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, false)), 0)
        it.dispatch(Action.SelectPreset(preset), 100)
        assertTrue(it.state is CargoState.Guarding)
    }

    @Test fun `loss of selected seat vacancy ends session and requests guarded restoration`() {
        val m = start()
        val effects = m.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, null)), 200)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(effects.contains(Effect.RestoreCabin))
        assertTrue(effects.filterIsInstance<Effect.Speak>().single().text.contains("확인"))
        assertFalse(effects.filterIsInstance<Effect.Speak>().any { it.text.contains("사람이 탔어요") })
    }

    @Test fun `other seat unknown does not end selected seat session`() {
        val m = start()
        val effects = m.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.REAR_LEFT, null)), 200)
        assertTrue(m.state is CargoState.Guarding)
        assertFalse(effects.contains(Effect.RestoreCabin))
    }

    @Test fun `vacancy loss preserves G coaching when no cabin settings were requested`() {
        val m = start(CargoPreset.GENERAL)
        val effects = m.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, null)), 200)
        assertTrue(m.state is CargoState.Guarding)
        assertFalse(effects.contains(Effect.RestoreCabin))
    }

    @Test fun `vacancy loss while moving still uses guarded restore effect without a new apply`() {
        val m = start()
        m.dispatch(Action.Vehicle(VehicleSignal.GearChanged(Gear.DRIVE)), 150)
        val effects = m.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, null)), 200)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(effects.contains(Effect.RestoreCabin))
        assertTrue(effects.none { it is Effect.ApplyCabin })
        assertFalse(m.dispatch(Action.Vehicle(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, null)), 300).contains(Effect.RestoreCabin))
    }
}
