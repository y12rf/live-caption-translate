package com.example.livetranslate.data.audio

import android.content.Context
import com.example.livetranslate.data.settings.SileroVadMode
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Silero DNN speech classifier (gkonovalov/android-vad).
 *
 * Library owns hangover timing:
 * - [speechDurationMs] — min speech run before isSpeech stays true (anti-blip)
 * - [silenceDurationMs] — min silence after speech before isSpeech goes false
 *   (end-of-utterance hangover; settings [UserSettings.silenceMs], default 260)
 *
 * Frame size fixed at 512 samples @ 16 kHz (~32 ms).
 * [EnergyVad] only edge-detects speech→silence and enforces max/min utterance length.
 */
class SileroSpeechClassifier(
    context: Context,
    mode: SileroVadMode = SileroVadMode.NORMAL,
    speechDurationMs: Int = DEFAULT_SPEECH_DURATION_MS,
    silenceDurationMs: Int = DEFAULT_SILENCE_DURATION_MS
) : SpeechClassifier {

    private val vad = VadSilero(
        context = context.applicationContext,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = mode.toLibraryMode(),
        speechDurationMs = speechDurationMs.coerceIn(0, 300_000),
        silenceDurationMs = silenceDurationMs.coerceIn(0, 300_000)
    )

    override fun isSpeech(frame: ShortArray): Boolean {
        require(frame.size == FRAME_SAMPLES) {
            "Silero expects $FRAME_SAMPLES samples @ 16 kHz, got ${frame.size}"
        }
        return vad.isSpeech(frame)
    }

    override fun close() {
        try {
            vad.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Silero 16 kHz frame size (samples). */
        const val FRAME_SAMPLES = 512

        /** ~32 ms @ 16 kHz. */
        const val FRAME_MS: Int = FRAME_SAMPLES * 1000 / 16_000

        /** Library recommended Speech Duration (anti single-frame false start). */
        const val DEFAULT_SPEECH_DURATION_MS = 50

        /** Default Silence Duration hangover (settings default 260ms). */
        const val DEFAULT_SILENCE_DURATION_MS = 260
    }
}

private fun SileroVadMode.toLibraryMode(): Mode = when (this) {
    SileroVadMode.NORMAL -> Mode.NORMAL
    SileroVadMode.AGGRESSIVE -> Mode.AGGRESSIVE
    SileroVadMode.VERY_AGGRESSIVE -> Mode.VERY_AGGRESSIVE
}
