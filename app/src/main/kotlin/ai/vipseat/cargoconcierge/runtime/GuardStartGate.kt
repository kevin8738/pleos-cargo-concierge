package ai.vipseat.cargoconcierge.runtime

/** Main-thread, in-memory permission to start effects after foreground promotion.
 * No preset or token is persisted: a recreated process cannot resume a guard.
 */
internal class GuardStartGate<T> {
    data class Request<T>(val id: Long, val payload: T, val location: Boolean)

    private var generation = 0L
    var pending: Request<T>? = null
        private set
    var active: Request<T>? = null
        private set
    var ending: Boolean = false
        private set
    val requiresService: Boolean get() = pending != null || active != null

    fun request(visible: Boolean, location: Boolean, payload: T): Request<T>? {
        if (!visible || active != null || ending) return null
        return Request(++generation, payload, location).also { pending = it }
    }

    fun ready(id: Long): Request<T>? {
        val request = pending?.takeIf { it.id == id } ?: return null
        pending = null
        active = request
        return request
    }

    fun failed(id: Long): Boolean {
        if (pending?.id != id) return false
        pending = null
        generation++
        return true
    }

    /** Invalidate pending starts immediately; keep an active lease until cabin work is drained. */
    fun end() {
        generation++
        pending = null
        ending = active != null
    }

    fun finished(id: Long): Boolean {
        if (!ending || active?.id != id) return false
        active = null
        ending = false
        return true
    }

    /** Unexpected loss has no recovery/restore action. */
    fun abandon() {
        generation++
        pending = null
        active = null
        ending = false
    }
}
