package ai.vipseat.cargoconcierge.core.mock

import ai.vipseat.cargoconcierge.core.model.NaviEvent
import ai.vipseat.cargoconcierge.core.model.TurnDirection
import ai.vipseat.cargoconcierge.core.port.NaviPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Injectable navigation events — the fallback when NaviHelper gives no curve geometry. */
class MockNavi : NaviPort {
    override val name: String = "Demo navi"
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviEvent> = _events

    override suspend fun connect() { _events.tryEmit(NaviEvent.Status(false, "demo navi ready")) }
    override suspend fun disconnect() {}

    fun emit(event: NaviEvent) { _events.tryEmit(event) }

    fun sharpCurve(distanceM: Int = 100, direction: TurnDirection = TurnDirection.RIGHT) =
        emit(NaviEvent.SharpCurveAhead(distanceM, direction, source = "demo"))

    fun speedBump(distanceM: Int = 80) = emit(NaviEvent.SpeedBumpAhead(distanceM, source = "demo"))

    fun arrived(name: String? = "목적지") = emit(NaviEvent.DestinationArrived(name))
}
