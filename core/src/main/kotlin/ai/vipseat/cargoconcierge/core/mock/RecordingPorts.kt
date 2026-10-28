package ai.vipseat.cargoconcierge.core.mock

import ai.vipseat.cargoconcierge.core.port.ChimeKind
import ai.vipseat.cargoconcierge.core.port.ChimePort
import ai.vipseat.cargoconcierge.core.port.SpeechPort
import ai.vipseat.cargoconcierge.core.port.VoiceInputPort

/** Records utterances instead of speaking — for tests and for the log pane. */
class RecordingSpeech : SpeechPort {
    override val name = "Recording TTS"
    override val available = true
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String, priority: Boolean) { spoken += text }
    override fun stop() {}
}

class RecordingChime : ChimePort {
    val played = mutableListOf<ChimeKind>()
    override fun play(kind: ChimeKind) { played += kind }
}

/** Returns a scripted phrase; stands in for STT/Gleo. */
class ScriptedVoiceInput(private val phrases: ArrayDeque<String> = ArrayDeque()) : VoiceInputPort {
    override val name = "Scripted STT"
    override val available = true
    fun enqueue(phrase: String) = phrases.addLast(phrase)
    override suspend fun listenOnce(timeoutMs: Long): String? = phrases.removeFirstOrNull()
}
