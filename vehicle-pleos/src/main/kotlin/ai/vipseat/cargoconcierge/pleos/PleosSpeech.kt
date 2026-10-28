package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.vipseat.cargoconcierge.core.port.SpeechPort
import ai.vipseat.cargoconcierge.core.port.VoiceInputPort
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType

/**
 * Pleos TextToSpeech 3.3.1. Verified: TextToSpeech(context, Mode), initialize(OnInitializeListener),
 * setLocale(Locale), speak(String, boolean), stop(), release().
 * ASSUMPTIONS (not verifiable from bytecode): the boolean on speak() flushes the queue when true;
 * HYBRID mode works without registerApp(). If initialisation fails, [available] stays false and
 * FallbackSpeech routes to the platform engine.
 */
class PleosSpeech(context: Context) : SpeechPort {
    override val name = "Pleos TTS 3.3.1"
    @Volatile override var available: Boolean = false
        private set
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context.applicationContext, Mode.HYBRID) }
        .onFailure { Log.w(TAG, "TTS construct failed: ${it.message}") }
        .getOrNull()

    init {
        tts?.let { t ->
            runCatching {
                t.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { t.setLocale(Locale.KOREAN) }
                        available = true
                        Log.i(TAG, "Pleos TTS ready")
                    }
                    override fun onFailure(error: TtsErrorType) {
                        available = false
                        Log.w(TAG, "Pleos TTS init failed: $error")
                    }
                })
            }.onFailure { Log.w(TAG, "TTS initialize threw: ${it.message}") }
        }
    }

    override suspend fun speak(text: String, priority: Boolean) {
        val t = tts ?: return
        runCatching { t.speak(text, priority) }.onFailure { Log.w(TAG, "speak failed: ${it.message}"); available = false }
    }

    override fun stop() { runCatching { tts?.stop() } }

    private companion object { const val TAG = "PleosSpeech" }
}

/**
 * Pleos SpeechToText 3.3.1. Verified: SpeechToText(context, Mode), initialize(OnInitializeListener),
 * addListener/removeListener(ResultListener), request(), stop(), setLocale(Locale), release().
 * ResultListener.onUpdated(text, isFinal) delivers transcripts (ASSUMPTION: the boolean marks the final result).
 */
class PleosVoiceInput(context: Context) : VoiceInputPort {
    override val name = "Pleos STT 3.3.1"
    @Volatile override var available: Boolean = false
        private set
    private val stt: SpeechToText? = runCatching { SpeechToText(context.applicationContext, SttMode.HYBRID) }
        .onFailure { Log.w(TAG, "STT construct failed: ${it.message}") }
        .getOrNull()

    init {
        stt?.let { s ->
            runCatching {
                s.initialize(object : SttInitListener {
                    override fun onSuccess() { runCatching { s.setLocale(Locale.KOREAN) }; available = true; Log.i(TAG, "Pleos STT ready") }
                    override fun onFailure(error: SttErrorType) { available = false; Log.w(TAG, "Pleos STT init failed: $error") }
                })
            }.onFailure { Log.w(TAG, "STT initialize threw: ${it.message}") }
        }
    }

    override suspend fun listenOnce(timeoutMs: Long): String? {
        val s = stt ?: return null
        if (!available) return null
        var listener: ResultListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var partial: String? = null
                    val l = object : ResultListener {
                        override fun onUpdated(text: String, isFinal: Boolean) {
                            partial = text
                            if (isFinal && cont.isActive) cont.resume(text)
                        }
                        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}
                        override fun onStartedRecognition() {}
                        override fun onEndedRecognition() { if (cont.isActive) cont.resume(partial) }
                        override fun onError(error: SttErrorType) { Log.w(TAG, "STT error $error"); if (cont.isActive) cont.resume(partial) }
                        override fun onReady() {}
                        override fun onUpdatedRms(rms: Double) {}
                    }
                    listener = l
                    s.addListener(l)
                    s.request()
                    cont.invokeOnCancellation { runCatching { s.stop() } }
                }
            }
        } finally {
            runCatching { s.stop() }
            listener?.let { runCatching { s.removeListener(it) } }
        }
    }

    private companion object { const val TAG = "PleosVoiceInput" }
}
