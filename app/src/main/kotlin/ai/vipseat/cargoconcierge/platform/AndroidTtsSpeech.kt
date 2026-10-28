package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.port.SpeechPort
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Platform TTS (android.speech.tts). Works wherever a Korean TTS engine is installed. */
class AndroidTtsSpeech(context: Context) : SpeechPort {
    override val name = "Android TextToSpeech"
    @Volatile override var available: Boolean = false
        private set
    private val counter = AtomicInteger()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = runCatching { ttsRef?.setLanguage(Locale.KOREAN) }.getOrNull()
            available = r != null && r >= TextToSpeech.LANG_AVAILABLE
            if (!available) Log.w(TAG, "Korean voice unavailable (result=$r)")
        } else {
            Log.w(TAG, "TTS init failed: $status")
        }
    }
    private var ttsRef: TextToSpeech? = null

    init { ttsRef = tts }

    override suspend fun speak(text: String, priority: Boolean) {
        if (!available) { Log.i(TAG, "(tts unavailable) $text"); return }
        val mode = if (priority) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "cargo-${counter.incrementAndGet()}")
    }

    override fun stop() { runCatching { tts.stop() } }

    private companion object { const val TAG = "AndroidTts" }
}
