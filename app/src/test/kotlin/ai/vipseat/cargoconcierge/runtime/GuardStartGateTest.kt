package ai.vipseat.cargoconcierge.runtime

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import org.junit.Assert.*
import org.junit.Test

class GuardStartGateTest {
    private class Harness {
        val gate = GuardStartGate<Action>()
        val engine = CargoStateMachine(GEstimator())
        var applyCount = 0
        init {
            engine.dispatch(Action.Vehicle(VehicleSignal.GearChanged(Gear.PARK)), 1_000)
            engine.dispatch(Action.Vehicle(VehicleSignal.Speed(0.0)), 1_001)
        }
        fun request(visible: Boolean = true, location: Boolean = false) = gate.request(visible, location,
            Action.SelectPreset(CargoPreset.of(CargoKind.CAKE)))
        fun ready(id: Long) {
            gate.ready(id)?.let { applyCount += engine.dispatch(it.payload, 1_100).filterIsInstance<Effect.ApplyCabin>().size }
        }
    }

    @Test fun `start failure cannot execute a preset or cabin effect`() {
        val h = Harness()
        val request = h.request()!!
        assertEquals(CargoState.Idle, h.engine.state)
        assertTrue(h.gate.failed(request.id))
        h.ready(request.id)
        assertEquals(0, h.applyCount)
        assertEquals(CargoState.Idle, h.engine.state)
        assertFalse(h.gate.requiresService)
    }

    @Test fun `end invalidates a late foreground promotion`() {
        val h = Harness()
        val request = h.request()!!
        h.gate.end()
        h.engine.dispatch(Action.EndTrip, 1_050)
        h.ready(request.id)
        assertEquals(0, h.applyCount)
        assertEquals(CargoState.Idle, h.engine.state)
        assertFalse(h.gate.requiresService)
    }

    @Test fun `duplicate ready delivery cannot reset trip or apply twice`() {
        val h = Harness()
        val request = h.request()!!
        h.ready(request.id)
        val trip = (h.engine.state as CargoState.Guarding).trip
        h.ready(request.id)
        assertEquals(1, h.applyCount)
        assertSame(trip, (h.engine.state as CargoState.Guarding).trip)
        assertEquals(request.id, h.gate.active!!.id)
    }

    @Test fun `replaced request rejects old success and old failure`() {
        val h = Harness()
        val first = h.request()!!
        val second = h.request(location = true)!!
        h.ready(first.id)
        assertFalse(h.gate.failed(first.id))
        assertEquals(second.id, h.gate.pending!!.id)
        h.ready(second.id)
        assertEquals(1, h.applyCount)
        assertTrue(h.gate.active!!.location)
    }

    @Test fun `hidden UI cannot create a pending start`() {
        val h = Harness()
        assertNull(h.request(visible = false))
        assertFalse(h.gate.requiresService)
        assertEquals(0, h.applyCount)
    }

    @Test fun `normal end retains lease until explicit cabin completion`() {
        val h = Harness()
        val request = h.request()!!
        h.ready(request.id)
        h.gate.end()
        assertTrue(h.gate.requiresService)
        assertTrue(h.gate.ending)
        assertNull(h.request())
        assertFalse(h.gate.finished(request.id + 1))
        assertTrue(h.gate.finished(request.id))
        assertFalse(h.gate.requiresService)
        assertNotNull(h.request())
    }

    @Test fun `unexpected loss invalidates requests without producing a restore effect`() {
        val h = Harness()
        val request = h.request()!!
        h.ready(request.id)
        h.gate.abandon()
        h.ready(request.id)
        assertEquals(1, h.applyCount)
        assertFalse(h.gate.requiresService)
        assertFalse(h.gate.ending)
        // The pure gate produces no engine action; host replaces the engine rather than EndTrip.
        assertNull(h.gate.active)
    }

    @Test fun `new process has neither a resumable preset nor service lease`() {
        val old = Harness()
        val request = old.request()!!
        old.ready(request.id)
        val fresh = Harness()
        fresh.ready(request.id)
        assertEquals(CargoState.Idle, fresh.engine.state)
        assertEquals(0, fresh.applyCount)
        assertFalse(fresh.gate.requiresService)
    }

    @Test fun `active lease rejects a new start token without changing location choice`() {
        val h = Harness()
        val request = h.request(location = false)!!
        h.ready(request.id)
        assertNull(h.request(location = true))
        assertFalse(h.gate.active!!.location)
        assertEquals(request.id, h.gate.active!!.id)
    }
}
