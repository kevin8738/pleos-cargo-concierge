package ai.vipseat.cargoconcierge.core.model

enum class TurnDirection { LEFT, RIGHT, UNKNOWN }

/** Normalised navigation events (Pleos NaviHelper, or the demo injector). */
sealed interface NaviEvent {
    data class SharpCurveAhead(val distanceM: Int, val direction: TurnDirection, val source: String) : NaviEvent
    data class SpeedBumpAhead(val distanceM: Int, val source: String) : NaviEvent
    data class DestinationArrived(val name: String?) : NaviEvent
    data class WaypointArrived(val name: String?) : NaviEvent
    data class RoadSpeedLimit(val kph: Int) : NaviEvent
    data class Status(val navigating: Boolean, val detail: String) : NaviEvent
}
