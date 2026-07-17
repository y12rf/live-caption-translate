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
 * Frame-based utterance segmenter.
 *
 * Processing model (call [accept] once per fixed-size PCM frame):
 * 1. Ask [classifier] whether the frame is speech.
 * 2. If speech: mark speaking, append frame bytes to buffer.
 * 3. While speaking, if buffered duration >= [maxUtteranceMs]: force-cut ([CutReason.MaxDuration]).
 * 4. While speaking, if consecutive quiet frames cover >= [silenceMs]: cut ([CutReason.Silence]).
 * 5. Drops emissions shorter than [minUtteranceMs] (voiced frames only).
 *
 * Frame size must stay constant and match the classifier
 * (Silero: 512 samples @ 16 kHz).
 */
class EnergyVad(
    private val sampleRate: Int,
    private val frameSamples: Int,
    private val classifier: SpeechClassifier,
    private val silenceMs: Int,
    private val maxUtteranceMs: Int,
    private val minUtteranceMs: Int
) : Closeable {
    private val buffer = ByteArrayOutputStream()
    private var speaking = false
    private var silenceFrames = 0
    private var speechFrames = 0
    /** Frames that contained speech (used for min-length; trailing silence not counted). */
    private var voicedFrames = 0

    private val frameMs: Double get() = frameSamples * 1000.0 / sampleRate
    private val silenceFramesNeeded: Int get() = (silenceMs / frameMs).toInt().coerceAtLeast(1)
    private val maxSpeechFrames: Int get() = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)
    private val minSpeechFrames: Int get() = (minUtteranceMs / frameMs).toInt().coerceAtLeast(1)

    fun accept(frame: ShortArray): VadResult {
        require(frame.size == frameSamples) { "Expected $frameSamples samples, got ${frame.size}" }
        val isSpeech = classifier.isSpeech(frame)

        if (isSpeech) {
            speaking = true
            silenceFrames = 0
            speechFrames++
            voicedFrames++
            writeFrame(frame)
            if (speechFrames >= maxSpeechFrames) {
                return emitAndReset(CutReason.MaxDuration)
            }
            return VadResult(null)
        }

        // quiet frame
        if (!speaking) {
            return VadResult(null)
        }
        // Keep trailing quiet frames so the cut includes natural end of speech.
        writeFrame(frame)
        silenceFrames++
        speechFrames++
        if (speechFrames >= maxSpeechFrames) {
            return emitAndReset(CutReason.MaxDuration)
        }
        if (silenceFrames >= silenceFramesNeeded) {
            return emitAndReset(CutReason.Silence)
        }
        return VadResult(null)
    }

    /** Optional flush when user stops recording. */
    fun flushStop(): VadResult {
        if (!speaking || voicedFrames < minSpeechFrames) {
            reset()
            return VadResult(null)
        }
        return emitAndReset(CutReason.StopFlush)
    }

    fun reset() {
        buffer.reset()
        speaking = false
        silenceFrames = 0
        speechFrames = 0
        voicedFrames = 0
    }

    override fun close() {
        reset()
        try {
            classifier.close()
        } catch (_: Exception) {
        }
    }

    private fun emitAndReset(reason: CutReason): VadResult {
        val pcm = buffer.toByteArray()
        val voiced = voicedFrames
        reset()
        if (pcm.isEmpty()) return VadResult(null)
        // Use voiced frame count so trailing silence does not inflate "length".
        if (voiced < minSpeechFrames) return VadResult(null)
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
