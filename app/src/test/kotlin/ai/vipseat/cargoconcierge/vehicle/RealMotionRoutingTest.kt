package ai.vipseat.cargoconcierge.vehicle

import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.MotionProvenance
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.port.RealMotionObserver
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Routing boundary: the command target owns its own location cache; this channel is external. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealMotionRoutingTest {
    private open class Port(override val name: String) : VehiclePort {
        override val signals = MutableSharedFlow<VehicleSignal>(extraBufferCapacity = 16)
        var initialSpeed: Double? = null
        var disconnectSignal: VehicleSignal? = null
        override suspend fun connect() { initialSpeed?.let { signals.tryEmit(VehicleSignal.Speed(it)) } }
        override suspend fun disconnect() { disconnectSignal?.let { signals.tryEmit(it) } }
        override suspend fun capabilities() = CabinCapabilities(emptyMap(), "fake")
        override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot) = CabinApplyResult.EMPTY
        override suspend fun restoreCabin() = CabinApplyResult.EMPTY
    }

    private class Target : Port("control target"), RealMotionObserver {
        val observed = mutableListOf<Pair<VehicleSignal, Long>>()
        override fun observeRealMotion(signal: VehicleSignal, receivedAtMs: Long) {
            observed += signal to receivedAtMs
        }
    }

    private class Fixture(scope: TestScope, targetFirst: Boolean = false) {
        val aosp = Port("AOSP")
        val other = Port("other external")
        val target = Target()
        val demo = MockVehicle()
        var nowMs = 1_000L
        val vehicle = MergedVehicle(
            sources = if (targetFirst) listOf(target, aosp, other) else listOf(aosp, target, other),
            mock = demo,
            controlTarget = target,
            monotonicNowMs = { nowMs },
            platformProvenance = MotionProvenance.UNKNOWN,
        )
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vehicle.signals.collect {} }
        }
        fun send(port: Port, signal: VehicleSignal) { assertTrue(port.signals.tryEmit(signal)) }
    }

    @Test fun `own location speed never loops back into external command cache`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.target, VehicleSignal.Speed(0.0)); runCurrent()
        assertTrue("own GPS must stay in the target's revocable cache", f.target.observed.isEmpty())
    }

    @Test fun `own location does not suppress a fresh external observation even when target is first`() = runTest {
        val f = Fixture(this, targetFirst = true); f.vehicle.connect(); runCurrent()
        f.send(f.target, VehicleSignal.Speed(0.0)); runCurrent()
        f.nowMs += 10
        f.send(f.aosp, VehicleSignal.Speed(4.0)); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(4.0) to 1_010L), f.target.observed)
    }

    @Test fun `expired external reading is never refreshed by an own location fix`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        f.nowMs += 2_001
        f.send(f.target, VehicleSignal.Speed(0.0)); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(0.0) to 1_000L), f.target.observed)
    }

    @Test fun `selected external disconnect clears its cache while control target stays connected`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.target, VehicleSignal.SourceStatus(true, "target connected"))
        f.send(f.aosp, VehicleSignal.SourceStatus(true, "AOSP connected")); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        f.nowMs += 20
        f.send(f.aosp, VehicleSignal.SourceStatus(false, "AOSP lost")); runCurrent()
        assertEquals(VehicleSignal.SourceStatus(false, "AOSP lost") to 1_020L, f.target.observed.last())
        f.send(f.other, VehicleSignal.Speed(5.0)); runCurrent()
        assertEquals(VehicleSignal.Speed(5.0) to 1_020L, f.target.observed.last())
    }

    @Test fun `unrelated disconnect does not drop the selected healthy external reading`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        f.send(f.other, VehicleSignal.SourceStatus(false, "unrelated lost")); runCurrent()
        f.send(f.target, VehicleSignal.SourceStatus(false, "target lost")); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(0.0) to 1_000L), f.target.observed)
    }

    @Test fun `demo stationary input never replaces actual moving observation`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.demo.setSpeedMps(0.0); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(3.0)); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(3.0) to 1_000L), f.target.observed)
        f.nowMs += 50
        f.demo.setSpeedMps(0.0)
        f.send(f.aosp, VehicleSignal.Speed(Double.NaN)); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(3.0) to 1_000L), f.target.observed)
    }

    @Test fun `whole adapter disconnect rejects teardown and late speeds until explicit reconnect`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        f.aosp.disconnectSignal = VehicleSignal.Speed(0.0)
        f.vehicle.disconnect(); runCurrent()
        assertEquals(VehicleSignal.SourceStatus(false, "disconnect") to 1_000L, f.target.observed.last())
        val count = f.target.observed.size
        f.nowMs += 10
        f.send(f.other, VehicleSignal.Speed(6.0)); runCurrent()
        assertEquals(count, f.target.observed.size)
        f.vehicle.connect(); runCurrent()
        f.send(f.other, VehicleSignal.Speed(6.0)); runCurrent()
        assertEquals(VehicleSignal.Speed(6.0) to 1_010L, f.target.observed.last())
    }

    @Test fun `a disconnected source cannot reinsert late speed before connected status`() = runTest {
        val f = Fixture(this); f.vehicle.connect(); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        f.send(f.aosp, VehicleSignal.SourceStatus(false, "lost")); runCurrent()
        val count = f.target.observed.size
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        assertEquals(count, f.target.observed.size)
        f.send(f.aosp, VehicleSignal.SourceStatus(true, "back"))
        f.send(f.aosp, VehicleSignal.Speed(2.0)); runCurrent()
        assertEquals(VehicleSignal.Speed(2.0) to 1_000L, f.target.observed.last())
    }

    @Test fun `connect accepts actual initial speed before the first connected status`() = runTest {
        val f = Fixture(this); runCurrent()
        f.send(f.aosp, VehicleSignal.Speed(0.0)); runCurrent()
        assertTrue(f.target.observed.isEmpty())
        f.aosp.initialSpeed = 1.0
        f.vehicle.connect(); runCurrent()
        assertEquals(listOf(VehicleSignal.Speed(1.0) to 1_000L), f.target.observed)
    }
}
