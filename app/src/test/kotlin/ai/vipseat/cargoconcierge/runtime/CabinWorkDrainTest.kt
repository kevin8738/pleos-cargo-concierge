package ai.vipseat.cargoconcierge.runtime

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CabinWorkDrainTest {
    /** Bound the old non-suspending spin without relying on a wall-clock timeout or hanging a test worker. */
    private class SpinDetectingJobs(private val backing: MutableSet<Job>) : MutableSet<Job> by backing {
        private var completedOnlyReads = 0
        override fun isEmpty(): Boolean {
            if (backing.isNotEmpty() && backing.all { it.isCompleted }) {
                check(++completedOnlyReads < 16) {
                    "Completed cabin jobs are spinning while their removal callback remains queued on the main dispatcher"
                }
            }
            return backing.isEmpty()
        }
    }

    @Test fun `completed job drains even while its removal callback is queued`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(main)
        val completed: CompletableJob = Job()
        val jobs = SpinDetectingJobs(linkedSetOf(completed))
        var cleanupRan = false
        completed.invokeOnCompletion {
            sessionScope.launch {
                cleanupRan = true
                jobs -= completed
            }
        }
        completed.complete()
        assertFalse(cleanupRan)
        // Reproduce the main-immediate boundary: draining starts before the queued callback can run.
        drainCabinJobs(jobs)
        assertTrue(jobs.isEmpty())
        assertFalse(cleanupRan)
        runCurrent()
        assertTrue(cleanupRan)
    }

    @Test fun `job added while the first batch is suspended must also finish before drain returns`() = runTest {
        val first = Job()
        val second = Job()
        val jobs = linkedSetOf<Job>(first)
        var drained = false
        val drain = launch(start = CoroutineStart.UNDISPATCHED) {
            drainCabinJobs(jobs)
            drained = true
        }
        assertFalse(drained)
        jobs += second
        first.complete()
        runCurrent()
        assertFalse(drained)
        assertEquals(setOf(second), jobs)
        second.complete()
        runCurrent()
        assertTrue(drained)
        assertTrue(drain.isCompleted)
        assertTrue(jobs.isEmpty())
    }

    @Test fun `timeout leaves unfinished jobs registered for the caller to cancel and mark pending`() = runTest {
        val unfinished = Job()
        val jobs = linkedSetOf<Job>(unfinished)
        var drained: Boolean? = null
        launch(start = CoroutineStart.UNDISPATCHED) {
            drained = withTimeoutOrNull(15_000) {
                drainCabinJobs(jobs)
                true
            } == true
        }
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(false, drained)
        assertEquals(setOf(unfinished), jobs)
        assertFalse(unfinished.isCompleted)
        unfinished.cancel()
    }
}
