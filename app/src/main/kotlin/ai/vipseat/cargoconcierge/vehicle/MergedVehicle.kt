package ai.vipseat.cargoconcierge.vehicle

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CabinPlan
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinApplyResult
import ai.vipseat.cargoconcierge.core.port.CabinCapabilities
import ai.vipseat.cargoconcierge.core.port.NaviPort
import ai.vipseat.cargoconcierge.core.port.RealMotionObserver
import ai.vipseat.cargoconcierge.core.port.SpeedArbiter
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Real sources (Pleos SDK, AOSP CarPropertyManager) merged with the scriptable mock. Signals from
 * all of them feed the same state machine, so a demo can inject whatever the emulator cannot
 * produce. Speed is arbitrated (demo > first real source > next …) to avoid fake dv/dt spikes.
 * Cabin commands go to [controlTarget] (the first real source by default) — results are never faked.
 */
class MergedVehicle(
    private val sources: List<VehiclePort>,
    private val mock: MockVehicle,
    private val controlTarget: VehiclePort? = sources.firstOrNull(),
) : VehiclePort {
    constructor(primary: VehiclePort?, mock: MockVehicle) : this(listOfNotNull(primary), mock)

    private val arbiter = SpeedArbiter.forSources(sources.size)
    private val steeringArbiter = SpeedArbiter.forSources(sources.size)
    private val realMotionArbiter = SpeedArbiter(LongArray(sources.size) { 2_000L })
    private val sourceStatuses = mutableMapOf<Int, VehicleSignal.SourceStatus>()

    override val name: String =
        if (sources.isEmpty()) mock.name else sources.joinToString(" + ") { it.name } + " + demo bridge"

    override val signals: Flow<VehicleSignal> = merge(
        *(listOf(mock) + sources).mapIndexed { priority, src ->
            src.signals.onEach { sig ->
                // Observe actual AOSP/Pleos speed BEFORE the demo-priority filter. Synthetic samples
                // can drive the coaching demonstration, but cannot authorize real cabin commands.
                if (priority > 0 && sig is VehicleSignal.Speed && sig.mps.isFinite() && sig.mps >= 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (synchronized(realMotionArbiter) { realMotionArbiter.accept(priority - 1, now) }) {
                        (controlTarget as? RealMotionObserver)?.observeRealMotion(sig, now)
                    }
                }
            }.filter { sig ->
                val now = SystemClock.elapsedRealtime()
                when (sig) {
                    is VehicleSignal.Speed -> synchronized(arbiter) { arbiter.accept(priority, now) }
                    is VehicleSignal.SteeringAngle -> synchronized(steeringArbiter) { steeringArbiter.accept(priority, now) }
                    is VehicleSignal.SourceStatus -> priority > 0 || sources.isEmpty()
                    else -> true
                }
            }.map { sig ->
                if (sig is VehicleSignal.SourceStatus && priority > 0) synchronized(sourceStatuses) {
                    sourceStatuses[priority] = sig
                    VehicleSignal.SourceStatus(
                        connected = sourceStatuses.values.any { it.connected },
                        detail = sourceStatuses.entries.sortedBy { it.key }.joinToString(" | ") {
                            "${sources[it.key - 1].name}: ${if (it.value.connected) "연결" else "연결 끊김"} ${it.value.detail}"
                        },
                    ).also { combined ->
                        if (!combined.connected) (controlTarget as? RealMotionObserver)?.observeRealMotion(combined, SystemClock.elapsedRealtime())
                    }
                } else sig
            }
        }.toTypedArray(),
    )

    override suspend fun connect() {
        sources.forEach { runCatching { it.connect() } }
        mock.connect(emitInitialState = sources.isEmpty())
    }

    override suspend fun disconnect() {
        (controlTarget as? RealMotionObserver)?.observeRealMotion(VehicleSignal.SourceStatus(false, "disconnect"), SystemClock.elapsedRealtime())
        synchronized(realMotionArbiter) { realMotionArbiter.reset() }
        synchronized(arbiter) { arbiter.reset() }
        synchronized(steeringArbiter) { steeringArbiter.reset() }
        synchronized(sourceStatuses) { sourceStatuses.clear() }
        sources.forEach { runCatching { it.disconnect() } }
        mock.disconnect()
    }

    override suspend fun capabilities(): CabinCapabilities = (controlTarget ?: mock).capabilities()

    override suspend fun applyCabin(plan: CabinPlan, consent: ApplyConsent, spot: CargoSpot): CabinApplyResult =
        (controlTarget ?: mock).applyCabin(plan, consent, spot)

    override suspend fun restoreCabin(): CabinApplyResult = (controlTarget ?: mock).restoreCabin()
}

class MergedNavi(private val primary: NaviPort?, private val mock: MockNavi) : NaviPort {
    override val name: String = primary?.let { "${it.name} + demo injector" } ?: mock.name
    override val events: Flow<NaviEvent> = primary?.let { merge(it.events.map { e -> e }, mock.events) } ?: mock.events
    override suspend fun connect() { runCatching { primary?.connect() }; mock.connect() }
    override suspend fun disconnect() { runCatching { primary?.disconnect() }; mock.disconnect() }
}
