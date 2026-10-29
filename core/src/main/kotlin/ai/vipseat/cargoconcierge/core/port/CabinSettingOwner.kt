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
        if (matches(current, target)) return confirmed(FeatureStatus.UNCHANGED, "이미 요청값 · 변경하지 않음", current.toString())
        guard(false)?.let { return it }
        if (!fresh(readAt)) return failed("원래 값 확인이 오래됨 · 변경하지 않음")
        // Save BEFORE sending: an interrupted/timed-out callback can still have changed the car.
        if (original == null) original = current
        expected = target
        val request = write(target)
        if (request.status != FeatureStatus.APPLIED) return request
        val readback = confirmReadback(current, target)
        return when (readback.status) {
            ReadbackStatus.CONFIRMED -> confirmed(FeatureStatus.APPLIED, "변경 요청 후 차량 값 확인", requireNotNull(readback.value))
            ReadbackStatus.UNAVAILABLE -> acceptedButUnconfirmed("변경 요청 응답 수신 · 차량 상태 확인 실패")
            ReadbackStatus.MISMATCH -> acceptedButUnconfirmed("변경 요청 응답 수신 · 차량 값 불일치")
        }
    }

    suspend fun restore(): FeatureOutcome {
        val saved = original ?: return unchanged("복원할 변경 없음")
        val current = read() ?: return failed("현재 값 읽기 실패 · 복원 보류, 재시도 가능")
        val readAt = nowMs()
        if (matches(current, saved)) {
            clear()
            return confirmed(FeatureStatus.UNCHANGED, "원래 값 확인 · 복원 명령 없음", current.toString())
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
        val readback = confirmReadback(current, saved)
        return when (readback.status) {
            ReadbackStatus.CONFIRMED -> {
                clear()
                confirmed(FeatureStatus.APPLIED, "복원 요청 후 원래 차량 값 확인", requireNotNull(readback.value))
            }
            ReadbackStatus.UNAVAILABLE -> acceptedButUnconfirmed("복원 요청 응답 수신 · 차량 상태 확인 실패, 재시도 가능")
            ReadbackStatus.MISMATCH -> acceptedButUnconfirmed("복원 요청 응답 수신 · 차량 값 불일치, 재시도 가능")
        }
    }

    private enum class ReadbackStatus { CONFIRMED, UNAVAILABLE, MISMATCH }
    private data class Readback(val status: ReadbackStatus, val value: String? = null)

    /** An acknowledged write may lag behind its getter. Retry only the unchanged pre-write value.
     * The one-second budget includes reads; null or a third value stops immediately. No writes are
     * repeated, and cancellation from the caller propagates through the timeout and delay.
     */
    private suspend fun confirmReadback(previous: T, target: T): Readback = withTimeoutOrNull(1_000L) {
        var observed = read() ?: return@withTimeoutOrNull Readback(ReadbackStatus.UNAVAILABLE)
        while (!matches(observed, target)) {
            if (!matches(observed, previous)) return@withTimeoutOrNull Readback(ReadbackStatus.MISMATCH)
            delay(100L)
            observed = read() ?: return@withTimeoutOrNull Readback(ReadbackStatus.UNAVAILABLE)
        }
        Readback(ReadbackStatus.CONFIRMED, observed.toString())
    } ?: Readback(ReadbackStatus.UNAVAILABLE)

    private fun fresh(readAt: Long): Boolean = (nowMs() - readAt) in 0..maxReadAgeMs
    private fun clear() { original = null; expected = null }
    private fun relinquish() { clear(); relinquished = true }
    private fun unchanged(detail: String) = FeatureOutcome(feature, FeatureStatus.UNCHANGED, detail)
    private fun failed(detail: String) = FeatureOutcome(feature, FeatureStatus.FAILED, detail)
    private fun acceptedButUnconfirmed(detail: String) =
        FeatureOutcome(feature, FeatureStatus.FAILED, detail, FeatureConfirmation.REQUEST_ACCEPTED)
    private fun confirmed(status: FeatureStatus, detail: String, value: String) =
        FeatureOutcome(feature, status, detail, FeatureConfirmation.VALUE_CONFIRMED, confirmedValue = value)
}
