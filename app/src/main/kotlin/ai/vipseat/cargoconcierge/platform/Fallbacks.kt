package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.port.SpeechPort
import ai.vipseat.cargoconcierge.core.port.VoiceInputPort

/** Speaks through [primary] when it is available, otherwise through [fallback]. */
class FallbackSpeech(private val primary: SpeechPort, private val fallback: SpeechPort) : SpeechPort {
    override val name: String get() = if (primary.available) primary.name else "${fallback.name} (fallback)"
    override val available: Boolean get() = primary.available || fallback.available
    override suspend fun speak(text: String, priority: Boolean) =
        if (primary.available) primary.speak(text, priority) else fallback.speak(text, priority)
    override fun stop() { primary.stop(); fallback.stop() }
}

class FallbackVoiceInput(private val primary: VoiceInputPort, private val fallback: VoiceInputPort) : VoiceInputPort {
    override val name: String get() = if (primary.available) primary.name else "${fallback.name} (fallback)"
    override val available: Boolean get() = primary.available || fallback.available
    override suspend fun listenOnce(timeoutMs: Long): String? =
        if (primary.available) primary.listenOnce(timeoutMs) else fallback.listenOnce(timeoutMs)
}
