package ai.vipseat.cargoconcierge.platform

import ai.vipseat.cargoconcierge.core.port.ChimeKind
import ai.vipseat.cargoconcierge.core.port.ChimePort
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/** Short, non-startling tones via ToneGenerator — no audio assets needed. */
class ChimePlayer(private val enabled: () -> Boolean) : ChimePort {
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
        .onFailure { Log.w(TAG, "ToneGenerator unavailable", it) }
        .getOrNull()

    override fun play(kind: ChimeKind) {
        if (!enabled()) return
        val t = tone ?: return
        runCatching {
            when (kind) {
                ChimeKind.SOFT -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                ChimeKind.ALERT -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                ChimeKind.SUCCESS -> t.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            }
        }.onFailure { Log.w(TAG, "chime failed", it) }
    }

    private companion object { const val TAG = "ChimePlayer" }
}
