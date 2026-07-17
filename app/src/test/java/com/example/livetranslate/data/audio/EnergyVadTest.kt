package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {

    private fun loudFrame(samples: Int = 320): ShortArray =
        ShortArray(samples) { 8000 }

    private fun quietFrame(samples: Int = 320): ShortArray =
        ShortArray(samples) { 10 }

    private fun vad(
        silenceMs: Int = 100,
        maxMs: Int = 500,
        minMs: Int = 50,
        threshold: Double = 500.0,
        sampleRate: Int = 16000,
        frameSamples: Int = 320
    ) = EnergyVad(
        sampleRate = sampleRate,
        frameSamples = frameSamples,
        classifier = EnergySpeechClassifier(threshold),
        postSpeechQuietMs = silenceMs,
        maxUtteranceMs = maxMs,
        minUtteranceMs = minMs
    )

    @Test
    fun silenceAfterSpeech_emitsSilenceCut() {
        val v = vad(silenceMs = 40, maxMs = 10_000, minMs = 20)
        assertNull(v.accept(loudFrame()).emit)
        assertNull(v.accept(quietFrame()).emit)
        val secondQuiet = v.accept(quietFrame())
        assertNotNull(secondQuiet.emit)
        assertEquals(CutReason.Silence, secondQuiet.emit!!.reason)
        assertTrue(secondQuiet.emit!!.pcm.isNotEmpty())
        v.close()
    }

    @Test
    fun longSpeech_forceMaxDurationCut() {
        val v = vad(silenceMs = 10_000, maxMs = 60, minMs = 20)
        var emitted = 0
        repeat(4) {
            if (v.accept(loudFrame()).emit != null) emitted++
        }
        assertEquals(1, emitted)
        v.close()
    }

    @Test
    fun tooShort_heldNotEmitted_thenMergedIntoNext() {
        // frame = 20ms @ 16k/320; min=100ms → need ≥5 voiced frames
        // postSpeechQuietMs=20 → 1 quiet frame triggers a silence-cut attempt
        val v = vad(silenceMs = 20, maxMs = 10_000, minMs = 100, frameSamples = 320)
        // 1 loud frame (~20ms) then silence → under min, hold (no emit)
        assertNull(v.accept(loudFrame()).emit)
        assertNull(v.accept(quietFrame()).emit)

        // more speech + silence → total voiced enough → one merged emit
        repeat(4) { assertNull(v.accept(loudFrame()).emit) }
        val merged = v.accept(quietFrame()).emit
        assertNotNull(merged)
        assertEquals(CutReason.Silence, merged!!.reason)
        // held first loud + 4 more loud frames of PCM at least
        assertTrue(merged.pcm.size >= 5 * 320 * 2)
        v.close()
    }

    @Test
    fun tooShort_flushStop_emitsHeld() {
        val v = vad(silenceMs = 20, maxMs = 10_000, minMs = 100)
        v.accept(loudFrame())
        assertNull(v.accept(quietFrame()).emit) // under min → hold
        val flush = v.flushStop().emit
        assertNotNull(flush)
        assertEquals(CutReason.StopFlush, flush!!.reason)
        v.close()
    }
}
