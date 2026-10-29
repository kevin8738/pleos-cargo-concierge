package ai.vipseat.cargoconcierge.core.port

import ai.vipseat.cargoconcierge.core.model.VehicleSignal

/**
 * Several sources can report speed at once (demo bridge, CarPropertyManager, FusedLocation-derived).
 * Interleaving them would feed the G estimator fake dv/dt spikes (e.g. VHAL says 0 km/h between two
 * injected 30 km/h samples). The arbiter lets the highest-priority source that spoke recently win:
 * a Speed sample from priority p is accepted only if no source with priority < p was heard within
 * that source's hold window.
 *
 * Priority 0 is the demo bridge (explicit human injection, long hold), then real sources.
 */
class SpeedArbiter(private val holdMs: LongArray) {
    private val lastSeen = LongArray(holdMs.size) { NEVER }

    val sourceCount: Int get() = holdMs.size

    /** Records a Speed sample from [priority] at [nowMs] and says whether it should be forwarded. */
    fun accept(priority: Int, nowMs: Long): Boolean {
        require(priority in holdMs.indices) { "priority $priority out of range" }
        lastSeen[priority] = nowMs
        for (p in 0 until priority) {
            if (nowMs - lastSeen[p] < holdMs[p]) return false
        }
        return true
    }

    /**
     * Invalid speeds never refresh a source's hold window. Retains each accepted stream's
     * identity so a handoff cannot become a fake speed delta.
     */
    fun acceptSpeed(priority: Int, sample: VehicleSignal.Speed, nowMs: Long): VehicleSignal.Speed? =
        if (sample.mps.isFinite() && sample.mps >= 0.0 && accept(priority, nowMs)) sample.copy(sourceId = priority) else null

    fun reset() { lastSeen.fill(NEVER) }

    companion object {
        const val NEVER: Long = Long.MIN_VALUE / 4
        const val DEMO_HOLD_MS = 60_000L
        const val REAL_HOLD_MS = 5_000L

        /** Demo bridge first, then [realSources] real sources in priority order. */
        fun forSources(realSources: Int): SpeedArbiter =
            SpeedArbiter(LongArray(realSources + 1) { if (it == 0) DEMO_HOLD_MS else REAL_HOLD_MS })
    }
}
