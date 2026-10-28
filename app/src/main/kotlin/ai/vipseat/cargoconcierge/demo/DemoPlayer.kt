package ai.vipseat.cargoconcierge.demo

import ai.vipseat.cargoconcierge.core.mock.DemoActors
import ai.vipseat.cargoconcierge.core.mock.DemoScenario
import ai.vipseat.cargoconcierge.core.mock.DemoStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Plays [DemoScenario.cakeRun] in real time against the mock car — the one-button PT demo. */
class DemoPlayer(private val scope: CoroutineScope, private val actors: DemoActors) {
    data class Progress(val running: Boolean, val stepIndex: Int, val caption: String?, val elapsedMs: Long, val totalMs: Long)

    private val _progress = MutableStateFlow(Progress(false, -1, null, 0, DemoScenario.durationMs))
    val progress: StateFlow<Progress> = _progress
    private var job: Job? = null

    fun start(steps: List<DemoStep> = DemoScenario.cakeRun) {
        stop()
        job = scope.launch {
            val total = steps.last().atMs + 1_000
            var elapsed = 0L
            steps.forEachIndexed { i, step ->
                val wait = step.atMs - elapsed
                if (wait > 0) delay(wait)
                elapsed = step.atMs
                _progress.value = Progress(true, i, step.caption, elapsed, total)
                step.run(actors)
            }
            delay(1_000)
            _progress.value = Progress(false, steps.size, "데모 종료", total, total)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_progress.value.running) _progress.value = _progress.value.copy(running = false, caption = "데모 중지")
    }
}
