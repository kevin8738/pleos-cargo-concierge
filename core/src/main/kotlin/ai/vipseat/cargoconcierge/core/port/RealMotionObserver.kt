package ai.vipseat.cargoconcierge.core.port

import ai.vipseat.cargoconcierge.core.model.VehicleSignal

/** Actual-source observations for command guards; the demo bridge must never feed this channel. */
interface RealMotionObserver {
    /** [receivedAtMs] uses the host monotonic clock. */
    fun observeRealMotion(signal: VehicleSignal, receivedAtMs: Long)
}
