package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.port.VoiceInputPort
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Platform STT (android.speech.SpeechRecognizer). Needs a recogniser service + RECORD_AUDIO. */
class AndroidSttVoiceInput(private val context: Context) : VoiceInputPort {
    override val name = "Android SpeechRecognizer"
    override val available: Boolean get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override suspend fun listenOnce(timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        if (!available) return@withContext null
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return@withContext null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (cont.isActive) cont.resume(text)
                        }
                        override fun onError(error: Int) { Log.w(TAG, "STT error $error"); if (cont.isActive) cont.resume(null) }
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    recognizer.startListening(intent)
                    cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
                }
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    private companion object { const val TAG = "AndroidStt" }
}
