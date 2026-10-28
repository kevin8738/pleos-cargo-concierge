package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.mock.DemoActors
import ai.vipseat.cargoconcierge.core.mock.DemoScenario
import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.physics.GEstimator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the PT timeline through the real reducer: the demo must produce the story we tell. */
class DemoScenarioReplayTest {

    @Test
    fun `cake run offers, guards, warns, coaches the curve and ends with the arrival reminder`() = runTest {
        val vehicle = MockVehicle()
        val navi = MockNavi()
        val sm = CargoStateMachine(GEstimator(smoothing = 1.0))
        val effects = mutableListOf<Effect>()
        val vehicleSignals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 256)
        val naviEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
        var now = 0L

        val c1 = launch { vehicle.signals.collect { vehicleSignals.tryEmit(it) } }
        val c2 = launch { navi.events.collect { naviEvents.tryEmit(it) } }
        val c3 = launch { vehicleSignals.collect { effects += sm.dispatch(Action.Vehicle(it), now) } }
        val c4 = launch { naviEvents.collect { effects += sm.dispatch(Action.Navi(it), now) } }
        val actors = DemoActors(vehicle, navi) { kind -> effects += sm.dispatch(Action.SelectPreset(CargoPreset.of(kind)), now) }
        testScheduler.advanceUntilIdle() // collectors must be subscribed before the first emission

        val seen = mutableListOf<String>()
        for (step in DemoScenario.cakeRun) {
            now = step.atMs
            step.run(actors)
            testScheduler.advanceUntilIdle()
            effects += sm.dispatch(Action.Tick, now)
            seen += "${step.atMs}: ${sm.state::class.simpleName}"
        }
        listOf(c1, c2, c3, c4).forEach { it.cancel() }

        val spoken = effects.filterIsInstance<Effect.Speak>().map { it.text }
        assertTrue(seen.joinToString("\n"), seen.any { it.endsWith("Offering") })
        assertTrue(spoken.joinToString("\n"), spoken.any { it.contains("케이크 VIP 모드") })
        assertTrue(spoken.joinToString("\n"), spoken.any { it.contains("0.15G를 넘었어요") })
        assertTrue(spoken.joinToString("\n"), spoken.contains("100미터 앞 급커브, 조금 줄여 부드럽게"))
        assertEquals("조수석 케이크 챙기세요", spoken.last())
        val arrived = sm.state as CargoState.Arrived
        assertEquals(CargoKind.CAKE, arrived.preset.kind)
        assertTrue(arrived.report.maxG > 0.15)
        assertTrue(arrived.report.coachingCount >= 2)
        assertTrue(vehicle.appliedPlans.isEmpty()) // host (ViewModel) applies; reducer only emits effects
    }
}
