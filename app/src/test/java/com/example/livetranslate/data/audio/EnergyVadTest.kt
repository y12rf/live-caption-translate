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
        silenceMs = silenceMs,
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
    fun tooShort_dropped() {
        val v = vad(silenceMs = 20, maxMs = 10_000, minMs = 100)
        v.accept(loudFrame())
        val e1 = v.accept(quietFrame()).emit
        val e2 = v.accept(quietFrame()).emit
        assertTrue(e1 == null && e2 == null)
        v.close()
    }
}
