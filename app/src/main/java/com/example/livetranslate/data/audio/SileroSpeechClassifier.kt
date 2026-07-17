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
 * Frame size is fixed at 512 samples @ 16 kHz (~32 ms).
 * Hangover silence is **not** applied here ([silenceDurationMs]=0) so [EnergyVad]
 * owns end-of-utterance timing via [UserSettings.silenceMs].
 * A short [speechDurationMs] filters single-frame false starts.
 */
class SileroSpeechClassifier(
    context: Context,
    mode: SileroVadMode = SileroVadMode.NORMAL,
    speechDurationMs: Int = DEFAULT_SPEECH_DURATION_MS,
    silenceDurationMs: Int = 0
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

        /** Minimum speech run before isSpeech stays true (anti-blip). */
        const val DEFAULT_SPEECH_DURATION_MS = 50
    }
}

private fun SileroVadMode.toLibraryMode(): Mode = when (this) {
    SileroVadMode.NORMAL -> Mode.NORMAL
    SileroVadMode.AGGRESSIVE -> Mode.AGGRESSIVE
    SileroVadMode.VERY_AGGRESSIVE -> Mode.VERY_AGGRESSIVE
}
