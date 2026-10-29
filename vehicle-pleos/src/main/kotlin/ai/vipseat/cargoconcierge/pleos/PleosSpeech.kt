package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
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
 * Pleos TextToSpeech 3.3.1: Korean synthesis requires ko-KR, and speak's boolean is isFinal.
 * HYBRID can attempt on-device synthesis when server credentials are unavailable; initialization
 * alone does not prove synthesis or playback. Event callbacks expose the later outcome.
 */
class PleosSpeech(context: Context) : SpeechPort {
    override val name = "Pleos TTS 3.3.1"
    @Volatile override var available: Boolean = false
        private set
    private val eventListener = object : EventListener {
        override fun onStart() { Log.i(TAG, "TTS_START") }
        override fun onReady() { Log.i(TAG, "TTS_READY") }
        override fun onDone() { Log.i(TAG, "TTS_DONE") }
        override fun onError(error: TtsErrorType) { Log.w(TAG, "TTS_ERROR:$error") }
        override fun onStop() { Log.i(TAG, "TTS_STOP") }
        override fun onUpdatedRms(rms: Double) {}
    }
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context.applicationContext, Mode.HYBRID) }
        .onFailure { Log.w(TAG, "TTS_CONSTRUCT_ERROR") }
        .getOrNull()

    init {
        tts?.let { t ->
            runCatching {
                t.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching {
                            t.setLocale(Locale.KOREA)
                            t.addEventListener(eventListener)
                        }.onSuccess {
                            available = true
                            Log.i(TAG, "TTS_INITIALIZED")
                        }.onFailure {
                            available = false
                            Log.w(TAG, "TTS_CONFIGURATION_ERROR")
                        }
                    }
                    override fun onFailure(error: TtsErrorType) {
                        available = false
                        Log.w(TAG, "TTS_INIT_ERROR:$error")
                    }
                })
            }.onFailure { Log.w(TAG, "TTS_INIT_ERROR") }
        }
    }

    override suspend fun speak(text: String, priority: Boolean) {
        val t = tts ?: return
        // Every request is a complete sentence. CargoViewModel stops obsolete speech before this
        // call; its priority flag must not be passed as the SDK's streaming completion flag.
        runCatching { t.speak(text, isFinal = true) }
            .onFailure { Log.w(TAG, "TTS_REQUEST_ERROR"); available = false }
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
