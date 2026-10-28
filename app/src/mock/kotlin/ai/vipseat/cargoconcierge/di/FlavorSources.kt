package ai.vipseat.cargoconcierge.di

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.platform.AndroidSttVoiceInput
import ai.vipseat.cargoconcierge.platform.AndroidTtsSpeech
import ai.vipseat.cargoconcierge.vehicle.MergedVehicle
import ai.vipseat.cargoconcierge.vehicle.aosp.AospCarVehicle
import android.Manifest
import android.content.Context

/**
 * mock flavor: standard AVD / stock AAOS emulator.
 *  - Vehicle: AOSP CarPropertyManager when the device is automotive (VHAL injection works),
 *    always merged with the MockVehicle so the demo bridge and in-app scenario work everywhere.
 *  - Navi: demo injector only.
 *  - Speech: platform TextToSpeech / SpeechRecognizer.
 */
object FlavorSources : SourceFactory {
    override fun create(context: Context, mockVehicle: MockVehicle, mockNavi: MockNavi): Sources {
        val aosp = AospCarVehicle.createIfAvailable(context)
        val vehicle = MergedVehicle(sources = listOfNotNull(aosp), mock = mockVehicle)
        return Sources(
            vehicle = vehicle,
            navi = mockNavi,
            speech = AndroidTtsSpeech(context),
            voice = AndroidSttVoiceInput(context),
            runtimePermissions = listOf(
                Manifest.permission.RECORD_AUDIO,
                // dangerous-level car permissions on stock AAOS → runtime dialog / `pm grant`
                "android.car.permission.CAR_SPEED",
                "android.car.permission.CAR_POWERTRAIN",
            ),
            description = if (aosp != null) "AOSP CarPropertyManager + demo bridge" else "Mock vehicle (no car service)",
        )
    }
}
