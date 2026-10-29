package ai.vipseat.cargoconcierge.core.history

import ai.vipseat.cargoconcierge.core.model.CargoSpot
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One serial off-main consumer. Producers do no disk work and never suspend a command. */
class CabinHistoryRecorder(
    scope: CoroutineScope,
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val wallTimeMs: () -> Long = System::currentTimeMillis,
    capacity: Int = 64,
) {
    data class View(val archive: CabinHistoryArchive = CabinHistoryArchive(), val unavailable: Boolean = false)
    private sealed interface Event {
        data class Begin(val id: String, val at: Long) : Event
        data class Observation(val value: CabinObservation) : Event
        data class End(val id: String, val normal: Boolean) : Event
    }
    private val queue = Channel<Event>(capacity)
    private val dropped = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    init {
        scope.launch {
            val loaded = runCatching { read() }
            val decoded = CabinHistoryCodec.decode(loaded.getOrNull())
            var archive = (decoded ?: CabinHistoryArchive()).afterRestart()
            var unavailable = loaded.isFailure || (!loaded.getOrNull().isNullOrBlank() && decoded == null)
            _view.value = View(archive, unavailable)
            for (event in queue) {
                archive = when (event) {
                    is Event.Begin -> archive.begin(event.id, event.at)
                    is Event.Observation -> archive.observe(event.value)
                    is Event.End -> archive.finish(event.id, event.normal)
                }
                if (dropped.get()) archive = archive.markGap()
                val persisted = runCatching { write(CabinHistoryCodec.encode(archive)) }.isSuccess
                if (!persisted) { archive = archive.markGap(); unavailable = true }
                _view.value = View(archive, unavailable || dropped.get())
            }
        }
    }

    fun begin(): String = UUID.randomUUID().toString().also { offer(Event.Begin(it, wallTimeMs())) }
    fun finish(id: String, normal: Boolean) = offer(Event.End(id, normal))
    fun context(id: String, spot: CargoSpot): CabinHistoryContext = CabinHistoryContext(
        id, sequence.incrementAndGet(), spot, { offer(Event.Observation(it)) }, wallTimeMs, sequence::incrementAndGet)

    private fun offer(event: Event) {
        if (queue.trySend(event).isFailure) {
            dropped.set(true)
            // The display warning is immediate even if a slow/full store never drains again.
            _view.value = _view.value.copy(unavailable = true)
        }
    }
}
