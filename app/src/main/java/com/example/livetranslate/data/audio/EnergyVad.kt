package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

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
 * Frame-based energy VAD for utterance segmentation.
 *
 * Processing model (call [accept] once per fixed-size PCM frame):
 * 1. Compute RMS energy of the frame.
 * 2. If RMS >= [energyThreshold]: mark speaking, append frame bytes to buffer.
 * 3. While speaking, if buffered duration >= [maxUtteranceMs]: force-cut ([CutReason.MaxDuration]).
 * 4. While speaking, if consecutive quiet frames cover >= [silenceMs]: cut ([CutReason.Silence]).
 * 5. Drops emissions shorter than [minUtteranceMs] (except empty checks).
 *
 * Frame size must stay constant (e.g. 20ms @ 16kHz = 320 samples).
 */
class EnergyVad(
    private val sampleRate: Int,
    private val frameSamples: Int,
    private val energyThreshold: Double,
    private val silenceMs: Int,
    private val maxUtteranceMs: Int,
    private val minUtteranceMs: Int
) {
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
        val rms = rms(frame)
        val isSpeech = rms >= energyThreshold

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

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size)
    }
}
