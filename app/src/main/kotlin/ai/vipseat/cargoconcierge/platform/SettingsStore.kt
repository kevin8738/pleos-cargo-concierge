package ai.vipseat.cargoconcierge.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    /** Requirement 2: heating an *empty* passenger seat needs explicit opt-in. */
    val emptySeatHeaterConsent: Boolean = false,
    val ttsEnabled: Boolean = true,
    val chimeEnabled: Boolean = true,
    /** When the navigation source has no curve geometry, allow the demo "100 m sharp curve" injection. */
    val demoCurveInjection: Boolean = true,
    val steeringRatio: Float = 15f,
    val wheelbaseM: Float = 2.9f,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cargo_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    /** Crash marker only. Separate display history cannot supply originals for automatic replay. */
    val cabinSessionPending: Boolean get() = prefs.getBoolean("cabin_session_pending", false)
    fun setCabinSessionPending(pending: Boolean) {
        prefs.edit().putBoolean("cabin_session_pending", pending).apply()
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_CONSENT, next.emptySeatHeaterConsent)
            .putBoolean(KEY_TTS, next.ttsEnabled)
            .putBoolean(KEY_CHIME, next.chimeEnabled)
            .putBoolean(KEY_DEMO_CURVE, next.demoCurveInjection)
            .putFloat(KEY_RATIO, next.steeringRatio)
            .putFloat(KEY_WHEELBASE, next.wheelbaseM)
            .apply()
    }

    private fun load() = Settings(
        emptySeatHeaterConsent = prefs.getBoolean(KEY_CONSENT, false),
        ttsEnabled = prefs.getBoolean(KEY_TTS, true),
        chimeEnabled = prefs.getBoolean(KEY_CHIME, true),
        demoCurveInjection = prefs.getBoolean(KEY_DEMO_CURVE, true),
        steeringRatio = prefs.getFloat(KEY_RATIO, 15f),
        wheelbaseM = prefs.getFloat(KEY_WHEELBASE, 2.9f),
    )

    private companion object {
        const val KEY_CONSENT = "empty_seat_heater_consent"
        const val KEY_TTS = "tts_enabled"
        const val KEY_CHIME = "chime_enabled"
        const val KEY_DEMO_CURVE = "demo_curve_injection"
        const val KEY_RATIO = "steering_ratio"
        const val KEY_WHEELBASE = "wheelbase_m"
    }
}
