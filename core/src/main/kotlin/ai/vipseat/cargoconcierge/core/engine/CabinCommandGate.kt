package ai.vipseat.cargoconcierge.core.engine

/** Main-thread generation token for queued cabin work and its asynchronous result. */
class CabinCommandGate {
    private var generation = 0L
    fun advance(): Long = ++generation
    fun isCurrent(ticket: Long): Boolean = ticket == generation
}
