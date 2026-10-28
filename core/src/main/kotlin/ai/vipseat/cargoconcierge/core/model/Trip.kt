package ai.vipseat.cargoconcierge.core.model

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
) {
    val avgTempC: Double? get() = if (tempSamples == 0) null else tempSumC / tempSamples
}

data class TripReport(
    val preset: CargoPreset,
    val maxG: Double,
    val avgTempC: Double?,
    val coachingCount: Int,
    val warnCount: Int,
    val durationMs: Long,
    val spot: CargoSpot = CargoSpot.FRONT_PASSENGER,
) {
    val withinLimit: Boolean get() = maxG <= preset.limitG

    val verdict: String
        get() = when {
            maxG <= preset.limitG * 0.8 -> "완벽한 배송이었어요"
            withinLimit -> "한계 안에서 도착했어요"
            maxG <= preset.limitG * 1.3 -> "조금 흔들렸지만 괜찮아요"
            else -> "${preset.arrivalNoun} 상태를 확인해 보세요"
        }
}
