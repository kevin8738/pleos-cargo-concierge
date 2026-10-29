package ai.vipseat.cargoconcierge.runtime

import kotlinx.coroutines.Job

/** Called on the session's main dispatcher; cabin jobs can be added while an earlier job suspends. */
internal suspend fun drainCabinJobs(jobs: MutableSet<Job>) {
    while (jobs.isNotEmpty()) {
        val batch = jobs.toList()
        batch.forEach { it.join() }
        // A completion callback may still be queued on this same dispatcher. Joining a completed
        // job does not suspend, so relying on that callback would spin and starve even the timeout.
        jobs.removeAll(batch.toSet())
    }
}
