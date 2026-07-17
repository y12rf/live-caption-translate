package com.example.livetranslate.data.audio

import java.io.Closeable
import kotlin.math.sqrt

/**
 * Per-frame speech / non-speech decision used by [EnergyVad] utterance segmenter.
 */
interface SpeechClassifier : Closeable {
    fun isSpeech(frame: ShortArray): Boolean

    override fun close() {}
}

/**
 * Legacy RMS energy gate — kept for unit tests (no ONNX / Android assets).
 */
class EnergySpeechClassifier(
    private val energyThreshold: Double
) : SpeechClassifier {
    override fun isSpeech(frame: ShortArray): Boolean {
        if (frame.isEmpty()) return false
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size) >= energyThreshold
    }
}
