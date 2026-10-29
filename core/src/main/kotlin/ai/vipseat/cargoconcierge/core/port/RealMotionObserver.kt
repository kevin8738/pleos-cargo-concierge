package ai.vipseat.cargoconcierge.core.port

import ai.vipseat.cargoconcierge.core.model.VehicleSignal

/**
 * Other actual-source observations for command guards; never the demo bridge or the observer's
 * own VehiclePort. A target keeps its own revocable feed (such as location) separately so ending
 * that feed cannot erase a healthy external observation or leave an unrevoked copy of its own.
 */
interface RealMotionObserver {
    /**
     * [receivedAtMs] uses the host monotonic clock. SourceStatus(false) invalidates the selected
     * external observation (or the entire adapter connection), not an unrelated source's cache.
     */
    fun observeRealMotion(signal: VehicleSignal, receivedAtMs: Long)
}
