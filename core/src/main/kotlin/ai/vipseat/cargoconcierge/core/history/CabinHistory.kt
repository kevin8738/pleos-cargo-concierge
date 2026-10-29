package ai.vipseat.cargoconcierge.core.history

import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureConfirmation
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Display evidence only. Nothing here can recreate a setting owner or a vehicle command. */
enum class CabinProperty(val label: String, val unit: String) {
    TEMPERATURE("설정 온도", "°C"), FAN_SPEED("풍량", "단계"), FAN_DIRECTION("풍향", ""),
    SEAT_HEATER("좌석 열선", "단계"), SEAT_VENT("좌석 통풍", "단계");

    fun accepts(value: String): Boolean = when (this) {
        TEMPERATURE -> value.toFloatOrNull()?.let { it.isFinite() && it in -100f..150f } == true
        FAN_SPEED, SEAT_HEATER, SEAT_VENT -> value.toIntOrNull()?.let { it in -100..100 } == true
        FAN_DIRECTION -> value.length in 1..48 && value.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }
}

data class CabinAddress(val source: String, val property: CabinProperty, val area: String)
enum class CabinHistoryPhase { BEFORE_WRITE, REQUEST_ACCEPTED, RESULT }

data class CabinObservation(
    val sessionId: String, val operation: Long, val cycleId: String,
    val address: CabinAddress, val spot: CargoSpot, val feature: CabinFeature,
    val atMs: Long, val restoring: Boolean, val phase: CabinHistoryPhase,
    val original: String?, val requested: String?, val confirmed: String? = null,
    val status: FeatureStatus? = null, val confirmation: FeatureConfirmation = FeatureConfirmation.NONE,
)

/** Sink implementations must only enqueue, never perform I/O, wait or call a vehicle. */
class CabinHistoryContext(
    val sessionId: String, val operation: Long, val spot: CargoSpot,
    private val sink: (CabinObservation) -> Unit,
    val wallTimeMs: () -> Long = System::currentTimeMillis,
    private val nextOperation: (() -> Long)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CabinHistoryContext>
    /** One adapter plan may touch the same resolved property twice; each owner call is distinct. */
    fun forProperty(): CabinHistoryContext = nextOperation?.let {
        CabinHistoryContext(sessionId, it(), spot, sink, wallTimeMs)
    } ?: this
    fun offer(observation: CabinObservation) { runCatching { sink(observation) } }
}

data class CabinHistoryRow(
    val cycleId: String, val address: CabinAddress, val spot: CargoSpot,
    val features: Set<CabinFeature>, val operation: Long, val atMs: Long,
    val original: String?, val requested: String?, val confirmed: String?,
    val applyRequested: String?, val confirmedAtMs: Long?,
    val restoring: Boolean, val phase: CabinHistoryPhase,
    val status: FeatureStatus?, val confirmation: FeatureConfirmation,
)

data class CabinHistorySession(
    val id: String, val startedAtMs: Long, val ended: Boolean = false,
    /** A normal end is process bookkeeping, never proof that all physical values were restored. */
    val normalEnd: Boolean = false, val gap: Boolean = false,
    val rows: List<CabinHistoryRow> = emptyList(),
)

data class CabinHistoryArchive(val previous: CabinHistorySession? = null, val current: CabinHistorySession? = null) {
    fun begin(id: String, atMs: Long): CabinHistoryArchive = copy(
        previous = current?.takeIf { it.rows.isNotEmpty() && (!it.normalEnd || it.gap) } ?: previous,
        current = CabinHistorySession(id, atMs),
    )

    fun observe(event: CabinObservation): CabinHistoryArchive {
        val session = current?.takeIf { it.id == event.sessionId && !it.ended } ?: return this
        val index = session.rows.indexOfFirst { it.cycleId == event.cycleId && it.address == event.address }
        val old = session.rows.getOrNull(index)
        if (old != null && (event.operation < old.operation ||
                (event.operation == old.operation && event.phase.ordinal < old.phase.ordinal))) return this
        if (index < 0 && session.rows.size >= MAX_ROWS) return copy(current = session.copy(gap = true))
        val row = CabinHistoryRow(event.cycleId, event.address, old?.spot ?: event.spot,
            (old?.features.orEmpty() + event.feature).toSet(), event.operation, event.atMs,
            old?.original ?: event.original, event.requested, event.confirmed ?: old?.confirmed,
            if (event.restoring) old?.applyRequested else event.requested ?: old?.applyRequested,
            if (event.confirmed != null) event.atMs else old?.confirmedAtMs,
            event.restoring, event.phase, event.status, event.confirmation)
        val rows = session.rows.toMutableList().also { if (index < 0) it.add(row) else it[index] = row }
        return copy(current = session.copy(rows = rows.toList()))
    }

    fun finish(id: String, normal: Boolean): CabinHistoryArchive =
        if (current?.id != id) this else copy(current = current.copy(ended = true, normalEnd = normal))

    /** Failed persistence/queue overflow cannot silently create a complete-looking record. */
    fun markGap(): CabinHistoryArchive = copy(
        previous = previous?.copy(gap = true), current = current?.copy(gap = true))

    /** A new process never resumes its working session. It only displays the retained evidence. */
    fun afterRestart(): CabinHistoryArchive = copy(
        previous = current?.takeIf { it.rows.isNotEmpty() && (!it.normalEnd || it.gap) }?.copy(ended = true) ?: previous,
        current = null,
    )

    companion object { const val MAX_ROWS = 32 }
}

/** UI-only gate: unknown/stale motion is not permission to inspect a detailed history. */
fun cabinHistoryVisible(requested: Boolean, interactive: Boolean, freshParked: Boolean): Boolean =
    requested && interactive && freshParked
