package ai.vipseat.cargoconcierge.core.model

/** Reducer policy reasons, not measurements of speaker playback or driver attention. */
enum class AudioSuppressionReason { EMERGENCY, SIGNAL_UNAVAILABLE, COOLDOWN, TRIP_LIMIT, VOICE_LIMIT, COALESCED }

/** Running statistics for the current guarded trip. */
data class TripStats(
    val startedMs: Long,
    val departedMs: Long? = null,
    val maxG: Double = 0.0,
    val tempSumC: Double = 0.0,
    val tempSamples: Int = 0,
    val coachingCount: Int = 0,
    val warnCount: Int = 0,
    val gSamples: Int = 0,
    /** Driving effect requests admitted by the reducer; TTS/audio output may still be unavailable or muted. */
    val drivingSpeechCount: Int = 0,
    val drivingChimeCount: Int = 0,
    val audioSuppressionCounts: Map<AudioSuppressionReason, Int> = emptyMap(),
) {
    val avgTempC: Double? get() = if (tempSamples == 0) null else tempSumC / tempSamples
    val drivingAudibleCount: Int get() = drivingSpeechCount + drivingChimeCount
}

data class TripReport(
    val preset: CargoPreset,
    val maxG: Double,
    val avgTempC: Double?,
    val coachingCount: Int,
    val warnCount: Int,
    val durationMs: Long,
    val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
    val drivingSpeechCount: Int = 0,
    val drivingChimeCount: Int = 0,
    val audioSuppressionCounts: Map<AudioSuppressionReason, Int> = emptyMap(),
    val gSamples: Int = 0,
) {
    val drivingAudibleCount: Int get() = drivingSpeechCount + drivingChimeCount
    val withinLimit: Boolean get() = gSamples > 0 && maxG <= preset.limitG

    val verdict: String
        get() = when {
            gSamples == 0 -> "유효한 주행 추정 기록이 없어요"
            withinLimit -> "기록된 추정 G는 설정 기준 이내예요"
            else -> "추정 G가 설정 기준을 넘었어요. ${preset.arrivalNoun} 상태를 확인해 주세요"
        }
}
