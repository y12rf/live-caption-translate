package com.example.livetranslate.domain

/**
 * Helpers for aligning VAD cuts to continuous session recording time.
 */
object UtteranceOffsets {
    /**
     * Convert cut-time (end of utterance) elapsed ms into **sentence-start** offset.
     *
     * @param endElapsedMs continuous recording elapsed when the VAD emits the cut
     * @param pcmBytes 16-bit mono PCM length in bytes
     * @param sampleRate Hz
     */
    fun startOffsetMs(endElapsedMs: Long, pcmBytes: Int, sampleRate: Int): Long {
        if (sampleRate <= 0 || pcmBytes <= 0) return endElapsedMs.coerceAtLeast(0L)
        val samples = pcmBytes / 2
        val durMs = samples * 1000L / sampleRate
        return (endElapsedMs - durMs).coerceAtLeast(0L)
    }
}
