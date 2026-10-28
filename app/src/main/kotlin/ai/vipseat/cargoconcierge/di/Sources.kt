package ai.vipseat.cargoconcierge.di

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.port.NaviPort
import ai.vipseat.cargoconcierge.core.port.SpeechPort
import ai.vipseat.cargoconcierge.core.port.VehiclePort
import ai.vipseat.cargoconcierge.core.port.VoiceInputPort
import android.content.Context

/** Everything a product flavor contributes. `FlavorSources` is defined once per flavor source set. */
data class Sources(
    val vehicle: VehiclePort,
    val navi: NaviPort,
    val speech: SpeechPort,
    val voice: VoiceInputPort,
    /** Runtime permissions the Activity should request on start. */
    val runtimePermissions: List<String>,
    val description: String,
)

interface SourceFactory {
    fun create(context: Context, mockVehicle: MockVehicle, mockNavi: MockNavi): Sources
}
