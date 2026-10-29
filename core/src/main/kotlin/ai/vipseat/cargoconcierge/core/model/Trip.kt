package ai.vipseat.cargoconcierge.core.model

/** Reducer policy reasons, not measurements of speaker playback or driver attention. */
enum class AudioSuppressionReason { EMERGENCY, SIGNAL_UNAVAILABLE, COOLDOWN, TRIP_LIMIT, VOICE_LIMIT, COALESCED }

/** Observed estimator/input limitations, not a diagnosis that the source or physical motion was wrong. */
enum class GQualityReason(val label: String, val defersComparison: Boolean) {
    INITIAL_BASELINE("최초 차분 기준점", false),
    SOURCE_HANDOFF("속도 출처 전환", false),
    INPUT_GAP("입력 단절", true),
    PARTIAL_AXES("일부 축만 추정", true),
    INVALID_SAMPLE("유효하지 않은 입력", true),
    REORDERED_SAMPLE("입력 순서 역전", true),
    HIGH_G_UNASSESSED("고G 구간 · 원인 미확인", true),
}

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
    /** New accepted speed differences only; [gSamples] retains its motion-event count meaning. */
    val freshLongitudinalSamples: Int = 0,
    val motionProvenance: Set<MotionProvenance> = emptySet(),
    val qualityReasons: Set<GQualityReason> = emptySet(),
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
    val freshLongitudinalSamples: Int = 0,
    val motionProvenance: Set<MotionProvenance> = emptySet(),
    val qualityReasons: Set<GQualityReason> = emptySet(),
) {
    val drivingAudibleCount: Int get() = drivingSpeechCount + drivingChimeCount
    val hasSyntheticInput: Boolean get() = MotionProvenance.SYNTHETIC in motionProvenance
    val requiresAssessment: Boolean get() = qualityReasons.any { it.defersComparison }
    val canCompare: Boolean get() = !hasSyntheticInput && gSamples > 0 && !requiresAssessment
    val withinLimit: Boolean get() = canCompare && maxG <= preset.limitG

    val comparisonLabel: String
        get() = when {
            hasSyntheticInput -> "실주행 평가 불가"
            gSamples == 0 -> "기록 없음"
            requiresAssessment -> "판정 보류"
            withinLimit -> "이내"
            else -> "초과"
        }

    val qualitySummary: String
        get() {
            val origin = if (hasSyntheticInput) "모의 입력 포함" else "입력 출처 검증 안 됨"
            val limits = qualityReasons.filter { it.defersComparison }.joinToString(" · ") { it.label }
            return origin + if (limits.isEmpty()) "" else " · $limits"
        }

    val verdict: String
        get() = when {
            hasSyntheticInput -> "모의 입력이 포함되어 실주행 평가 불가"
            gSamples == 0 -> "유효한 주행 추정 기록이 없어요"
            requiresAssessment -> "입력·추정 한계로 판정 보류. 최대 계산값과 짐 상태를 확인해 주세요"
            withinLimit -> "기록된 추정 G는 설정 기준 이내예요"
            else -> "추정 G가 설정 기준을 넘었어요. ${preset.arrivalNoun} 상태를 확인해 주세요"
        }
}
