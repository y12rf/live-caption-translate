package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import java.io.ByteArrayOutputStream
import java.io.Closeable

data class VadEmit(val pcm: ByteArray, val reason: CutReason) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VadEmit) return false
        return reason == other.reason && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * pcm.contentHashCode() + reason.hashCode()
}

data class VadResult(val emit: VadEmit?)

/**
 * Frame-based utterance packer on top of a [SpeechClassifier].
 *
 * With [SileroSpeechClassifier], silence hangover is applied **inside Silero**
 * (`silenceDurationMs`). This class only:
 * 1. Buffers while [SpeechClassifier.isSpeech] is true (incl. hangover frames).
 * 2. On first non-speech after speech → try silence cut
 *    (optional [postSpeechQuietMs] for non-Silero classifiers).
 * 3. Force-cut at [maxUtteranceMs] ([CutReason.MaxDuration]) — always emit.
 * 4. If a silence cut is shorter than [minUtteranceMs] (voiced), **hold PCM** and
 *    merge into the next speech run instead of discarding or emitting early.
 * 5. [flushStop] emits held buffer even if still under min (no next sentence).
 *
 * Frame size must match the classifier (Silero: 512 @ 16 kHz).
 */
class EnergyVad(
    private val sampleRate: Int,
    private val frameSamples: Int,
    private val classifier: SpeechClassifier,
    /**
     * Extra quiet time after isSpeech becomes false before a silence cut attempt.
     * Use **0** when Silero already applies silenceDuration hangover.
     */
    private val postSpeechQuietMs: Int = 0,
    private val maxUtteranceMs: Int,
    private val minUtteranceMs: Int
) : Closeable {
    private val buffer = ByteArrayOutputStream()
    private var speaking = false
    private var quietAfterSpeechFrames = 0
    private var totalFramesInUtterance = 0
    /** Classifier-true speech frames (min length; hangover-only not counted if never voiced). */
    private var voicedFrames = 0

    private val frameMs: Double get() = frameSamples * 1000.0 / sampleRate
    private val quietFramesNeeded: Int
        get() = if (postSpeechQuietMs <= 0) 1
        else (postSpeechQuietMs / frameMs).toInt().coerceAtLeast(1)
    private val maxSpeechFrames: Int get() = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)
    private val minSpeechFrames: Int get() = (minUtteranceMs / frameMs).toInt().coerceAtLeast(0)

    fun accept(frame: ShortArray): VadResult {
        require(frame.size == frameSamples) { "Expected $frameSamples samples, got ${frame.size}" }
        val isSpeech = classifier.isSpeech(frame)

        if (isSpeech) {
            speaking = true
            quietAfterSpeechFrames = 0
            totalFramesInUtterance++
            voicedFrames++
            writeFrame(frame)
            if (totalFramesInUtterance >= maxSpeechFrames) {
                // Max always flushes (including short hold + more speech).
                return emitAndReset(CutReason.MaxDuration, force = true)
            }
            return VadResult(null)
        }

        // quiet frame
        if (!speaking) {
            // Between held short clip and next speech: do not grow buffer with pure silence.
            return VadResult(null)
        }
        writeFrame(frame)
        quietAfterSpeechFrames++
        totalFramesInUtterance++
        if (totalFramesInUtterance >= maxSpeechFrames) {
            return emitAndReset(CutReason.MaxDuration, force = true)
        }
        if (quietAfterSpeechFrames >= quietFramesNeeded) {
            return emitAndReset(CutReason.Silence, force = false)
        }
        return VadResult(null)
    }

    /**
     * Flush when user stops. Emits any held/short buffer (no next sentence to merge into).
     */
    fun flushStop(): VadResult {
        if (buffer.size() == 0) {
            reset()
            return VadResult(null)
        }
        return emitAndReset(CutReason.StopFlush, force = true)
    }

    fun reset() {
        buffer.reset()
        speaking = false
        quietAfterSpeechFrames = 0
        totalFramesInUtterance = 0
        voicedFrames = 0
    }

    override fun close() {
        reset()
        try {
            classifier.close()
        } catch (_: Exception) {
        }
    }

    /**
     * @param force if true (max / stop), emit even when under [minUtteranceMs].
     *              if false (silence) and under min, hold buffer for merge into next speech.
     */
    private fun emitAndReset(reason: CutReason, force: Boolean): VadResult {
        val pcm = buffer.toByteArray()
        if (pcm.isEmpty()) {
            reset()
            return VadResult(null)
        }
        val underMin = minSpeechFrames > 0 && voicedFrames < minSpeechFrames
        if (!force && underMin) {
            // Merge into next utterance: keep PCM + voiced count, wait for more speech.
            speaking = false
            quietAfterSpeechFrames = 0
            // totalFramesInUtterance kept so max still applies across merges.
            return VadResult(null)
        }
        reset()
        return VadResult(VadEmit(pcm, reason))
    }

    private fun writeFrame(frame: ShortArray) {
        val bytes = ByteArray(frame.size * 2)
        var i = 0
        for (s in frame) {
            bytes[i++] = (s.toInt() and 0xFF).toByte()
            bytes[i++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        buffer.write(bytes)
    }
}
