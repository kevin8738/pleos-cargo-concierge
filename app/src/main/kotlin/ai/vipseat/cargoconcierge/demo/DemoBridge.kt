package ai.vipseat.cargoconcierge.demo

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.DrivingMode
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.TurnDirection
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Commands that need the ViewModel (state machine / demo player) rather than the mock car. */
sealed interface UiCommand {
    data class VoiceText(val text: String) : UiCommand
    data class Preset(val kind: CargoKind) : UiCommand
    data class Scenario(val start: Boolean) : UiCommand
    data object OpenSheet : UiCommand
    /** F1 ②/③ stand-in for the demo: pre-boot door history or a pickup-store geofence. */
    data class ExternalOffer(val reason: String) : UiCommand
    data object EndTrip : UiCommand
    data object AcknowledgeReport : UiCommand
    data class ShowSettings(val show: Boolean) : UiCommand
    /** "도자기:FRAGILE,강아지:PET" replaces the recent-VIP list; empty or "clear" empties it. */
    data class SeedRecents(val spec: String) : UiCommand
    /** Cabin map: where the VIP rides (FRONT_PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT, TRUNK). */
    data class SelectSpot(val spot: CargoSpot) : UiCommand
}

/**
 * Translates `adb shell am broadcast -a ai.vipseat.cargoconcierge.DEMO --es cmd <cmd> --es value <v>`
 * into mock-vehicle / mock-navi calls. Documented in demo/demo_broadcast.sh.
 */
class DemoBridge(private val vehicle: MockVehicle, private val navi: MockNavi) {
    private val _uiCommands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 16)
    val uiCommands: SharedFlow<UiCommand> = _uiCommands

    fun handle(intent: Intent): String {
        if (intent.action == ACTION_VOICE_TEXT) {
            val text = intent.getStringExtra("text").orEmpty()
            _uiCommands.tryEmit(UiCommand.VoiceText(text))
            return "voice: $text"
        }
        val cmd = intent.getStringExtra("cmd")?.lowercase().orEmpty()
        val value = intent.getStringExtra("value").orEmpty()
        return try {
            when (cmd) {
                "gear" -> { vehicle.setGear(parseGear(value)); "gear=$value" }
                "speed" -> { vehicle.setSpeedKph(value.toDouble()); "speed=${value}kph" }
                "steer" -> { vehicle.setSteering(value.toDouble()); "steer=${value}deg" }
                "brake" -> { val pct = value.toDoubleOrNull() ?: 0.0; vehicle.setBrake(pct > 0, pct); "brake=$pct%" }
                "door" -> {
                    val id = DoorId.valueOf(intent.getStringExtra("id")?.uppercase() ?: "PASSENGER")
                    val open = value.equals("open", true) || value == "1" || value.equals("true", true)
                    vehicle.setDoor(id, open); "door $id open=$open"
                }
                "occupied" -> {
                    val seat = intent.getStringExtra("seat")?.uppercase()?.let { s -> runCatching { CargoSpot.valueOf(s) }.getOrNull() } ?: CargoSpot.FRONT_PASSENGER
                    vehicle.setOccupied(seat, value.toBooleanStrictOrNull()); "occupied $seat=$value"
                }
                "temp" -> { vehicle.setCabinTemp(value.toDouble()); "temp=$value" }
                "mode" -> { vehicle.setDrivingMode(DrivingMode.valueOf(value.uppercase())); "mode=$value" }
                "navi" -> {
                    val distance = intent.getStringExtra("distance")?.toIntOrNull() ?: 100
                    when (value.lowercase()) {
                        "curve" -> navi.sharpCurve(distance, TurnDirection.RIGHT)
                        "bump" -> navi.speedBump(distance)
                        "arrived" -> navi.arrived(intent.getStringExtra("name") ?: "목적지")
                        else -> return "unknown navi value $value"
                    }
                    "navi $value"
                }
                "voice" -> { _uiCommands.tryEmit(UiCommand.VoiceText(intent.getStringExtra("text") ?: value)); "voice" }
                "preset" -> { _uiCommands.tryEmit(UiCommand.Preset(CargoKind.valueOf(value.uppercase()))); "preset=$value" }
                "scenario" -> { _uiCommands.tryEmit(UiCommand.Scenario(start = value.equals("start", true))); "scenario $value" }
                "sheet" -> { _uiCommands.tryEmit(UiCommand.OpenSheet); "sheet" }
                "offer" -> { _uiCommands.tryEmit(UiCommand.ExternalOffer(value.ifEmpty { "외부 트리거" })); "offer=$value" }
                "end" -> { _uiCommands.tryEmit(UiCommand.EndTrip); "end" }
                "ack" -> { _uiCommands.tryEmit(UiCommand.AcknowledgeReport); "ack" }
                "recent" -> { _uiCommands.tryEmit(UiCommand.SeedRecents(if (value.equals("clear", true)) "" else value)); "recent=$value" }
                "spot" -> { _uiCommands.tryEmit(UiCommand.SelectSpot(CargoSpot.valueOf(value.uppercase()))); "spot=$value" }
                "settings" -> { _uiCommands.tryEmit(UiCommand.ShowSettings(value.equals("on", true) || value.equals("true", true))); "settings=$value" }
                else -> "unknown cmd '$cmd'"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    private fun parseGear(v: String): Gear = when (v.uppercase()) {
        "P", "PARK" -> Gear.PARK
        "D", "DRIVE" -> Gear.DRIVE
        "R", "REVERSE" -> Gear.REVERSE
        "N", "NEUTRAL" -> Gear.NEUTRAL
        else -> Gear.UNKNOWN
    }

    companion object {
        const val TAG = "DemoBridge"
        const val ACTION_DEMO = "ai.vipseat.cargoconcierge.DEMO"
        const val ACTION_VOICE_TEXT = "ai.vipseat.cargoconcierge.VOICE_TEXT"
    }
}
