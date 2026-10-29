package ai.vipseat.cargoconcierge.di

import ai.vipseat.cargoconcierge.core.mock.MockNavi
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.platform.AndroidSttVoiceInput
import ai.vipseat.cargoconcierge.platform.AndroidTtsSpeech
import ai.vipseat.cargoconcierge.platform.FallbackSpeech
import ai.vipseat.cargoconcierge.platform.FallbackVoiceInput
import ai.vipseat.cargoconcierge.pleos.PleosNavi
import ai.vipseat.cargoconcierge.pleos.PleosRuntime
import ai.vipseat.cargoconcierge.pleos.PleosSpeech
import ai.vipseat.cargoconcierge.pleos.PleosVehicle
import ai.vipseat.cargoconcierge.pleos.PleosVoiceInput
import ai.vipseat.cargoconcierge.vehicle.MergedNavi
import ai.vipseat.cargoconcierge.vehicle.MergedVehicle
import ai.vipseat.cargoconcierge.vehicle.aosp.AospCarVehicle
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * pleos flavor: Pleos_Connect_v2.1 AVD / Pleos device.
 *  - Vehicle: Pleos Vehicle SDK 2.3.2 is the control target (HVAC/seat) and the typed signal source
 *    (doors, brake, steering, occupancy, driving mode). The AOSP CarPropertyManager adapter is merged
 *    as a second source because Pleos itself deprecates the typed gear API in favour of the standard
 *    GEAR_SELECTION property and exposes no typed speed API (PERF_VEHICLE_SPEED). Speed priority:
 *    demo bridge > CarPropertyManager > FusedLocation-derived (inside PleosVehicle).
 *  - Navi: NaviHelper 3.0.5 events, merged with the demo curve injector.
 *  - Speech: Pleos TTS/STT 3.3.1 with platform fallback.
 */
object FlavorSources : SourceFactory {
    override fun create(context: Context, mockVehicle: MockVehicle, mockNavi: MockNavi): Sources {
        val app = context.applicationContext
        val locationAccessAvailable: () -> Boolean = {
            val standardPermission = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
            standardPermission &&
                ContextCompat.checkSelfPermission(app, "pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED &&
                runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true }.getOrDefault(false)
        }
        val pleosVehicle = PleosVehicle(context, locationAccessAvailable)
        val aosp = AospCarVehicle.createIfAvailable(context)
        val pleosNavi = PleosNavi(context)
        return Sources(
            vehicle = MergedVehicle(sources = listOfNotNull(aosp, pleosVehicle), mock = mockVehicle, controlTarget = pleosVehicle),
            navi = MergedNavi(primary = pleosNavi, mock = mockNavi),
            speech = FallbackSpeech(primary = PleosSpeech(context), fallback = AndroidTtsSpeech(context)),
            voice = FallbackVoiceInput(primary = PleosVoiceInput(context), fallback = AndroidSttVoiceInput(context)),
            runtimePermissions = listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                "android.car.permission.CAR_SPEED",
                "android.car.permission.CAR_POWERTRAIN",
            ) + PleosRuntime.runtimePermissions,
            description = "Pleos Vehicle 2.3.2 (control) · CarPropertyManager (speed/gear) · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS/STT 3.3.1 (+ demo bridge)",
            locationAccessAvailable = locationAccessAvailable,
            setLocationAccessAllowed = pleosVehicle::setLocationAccessAllowed,
        )
    }
}
