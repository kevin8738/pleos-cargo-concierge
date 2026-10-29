package ai.vipseat.cargoconcierge.core.port

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-memory ownership of one physical cabin property, not a whole preset.
 *
 * A successful callback only acknowledges a request. APPLIED requires a separate matching read.
 * Different current values relinquish ownership conservatively; they do not prove who changed it.
 * Calls must be serialized by the adapter. This does not provide process-death recovery or an atomic
 * compare-and-set with the vehicle: a change between the last read and the setter remains possible.
 */
class CabinSettingOwner<T : Any>(
    val feature: CabinFeature,
    private val read: suspend () -> T?,
    private val write: suspend (T) -> FeatureOutcome,
    private val guard: suspend (restoring: Boolean) -> FeatureOutcome?,
    private val matches: (T, T) -> Boolean = { a, b -> a == b },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxReadAgeMs: Long = 5_000L,
) {
    private var original: T? = null
    private var expected: T? = null
    private var relinquished = false

    val needsRestore: Boolean get() = original != null

    suspend fun apply(target: T): FeatureOutcome {
        if (relinquished) return unchanged("현재 값 변경 감지 · 이번 세션은 유지")
        val current = read() ?: return failed("원래 값 읽기 실패 · 변경하지 않음")
        val readAt = nowMs()
        val previousTarget = expected
        if (previousTarget != null && !matches(current, previousTarget)) {
            relinquish()
            return unchanged("현재 값 변경 감지 · 이번 세션은 유지")
        }
        if (matches(current, target)) return unchanged("이미 요청값 · 변경하지 않음")
        guard(false)?.let { return it }
        if (!fresh(readAt)) return failed("원래 값 확인이 오래됨 · 변경하지 않음")
        // Save BEFORE sending: an interrupted/timed-out callback can still have changed the car.
        if (original == null) original = current
        expected = target
        val request = write(target)
        if (request.status != FeatureStatus.APPLIED) return request
        return when (confirmReadback(current, target)) {
            Readback.CONFIRMED -> applied("변경 요청 후 차량 값 확인")
            Readback.UNAVAILABLE -> failed("변경 요청 응답 수신 · 차량 상태 확인 실패")
            Readback.MISMATCH -> failed("변경 요청 응답 수신 · 차량 값 불일치")
        }
    }

    suspend fun restore(): FeatureOutcome {
        val saved = original ?: return unchanged("복원할 변경 없음")
        val current = read() ?: return failed("현재 값 읽기 실패 · 복원 보류, 재시도 가능")
        val readAt = nowMs()
        if (matches(current, saved)) {
            clear()
            return unchanged("원래 값 확인 · 복원 명령 없음")
        }
        val lastTarget = expected
        if (lastTarget == null || !matches(current, lastTarget)) {
            relinquish()
            return unchanged("현재 값 변경 감지 · 덮어쓰지 않고 유지")
        }
        guard(true)?.let { return it }
        if (!fresh(readAt)) return failed("현재 값 확인이 오래됨 · 복원 보류, 재시도 가능")
        val request = write(saved)
        if (request.status != FeatureStatus.APPLIED) return request
        return when (confirmReadback(current, saved)) {
            Readback.CONFIRMED -> {
                clear()
                applied("복원 요청 후 원래 차량 값 확인")
            }
            Readback.UNAVAILABLE -> failed("복원 요청 응답 수신 · 차량 상태 확인 실패, 재시도 가능")
            Readback.MISMATCH -> failed("복원 요청 응답 수신 · 차량 값 불일치, 재시도 가능")
        }
    }

    private enum class Readback { CONFIRMED, UNAVAILABLE, MISMATCH }

    /** An acknowledged write may lag behind its getter. Retry only the unchanged pre-write value.
     * The one-second budget includes reads; null or a third value stops immediately. No writes are
     * repeated, and cancellation from the caller propagates through the timeout and delay.
     */
    private suspend fun confirmReadback(previous: T, target: T): Readback = withTimeoutOrNull(1_000L) {
        var observed = read() ?: return@withTimeoutOrNull Readback.UNAVAILABLE
        while (!matches(observed, target)) {
            if (!matches(observed, previous)) return@withTimeoutOrNull Readback.MISMATCH
            delay(100L)
            observed = read() ?: return@withTimeoutOrNull Readback.UNAVAILABLE
        }
        Readback.CONFIRMED
    } ?: Readback.UNAVAILABLE

    private fun fresh(readAt: Long): Boolean = (nowMs() - readAt) in 0..maxReadAgeMs
    private fun clear() { original = null; expected = null }
    private fun relinquish() { clear(); relinquished = true }
    private fun unchanged(detail: String) = FeatureOutcome(feature, FeatureStatus.UNCHANGED, detail)
    private fun failed(detail: String) = FeatureOutcome(feature, FeatureStatus.FAILED, detail)
    private fun applied(detail: String) = FeatureOutcome(feature, FeatureStatus.APPLIED, detail)
}
