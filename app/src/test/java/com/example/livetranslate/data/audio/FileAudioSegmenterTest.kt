package com.example.livetranslate.data.audio

import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.CutReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class FileAudioSegmenterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun energySegmenter(threshold: Double = 200.0) = FileAudioSegmenter(
        frameSamples = 320, // energy classifier is frame-size agnostic
        classifierFactory = { EnergySpeechClassifier(threshold) }
    )

    @Test
    fun segment_speechThenSilence_emitsUtterance() = runBlocking {
        // 2s loud + 1s silence @ 16k mono → should cut on silence with low min length
        val sr = 16_000
        val pcm = buildPcm(sr, speechMs = 2000, silenceMs = 1000, amplitude = 8000)
        val wav = writeWav(pcm, sr)
        val settings = UserSettings(
            silenceMs = 300,
            maxUtteranceMs = 15_000,
            minUtteranceMs = 400
        )
        val segs = energySegmenter().segment(wav, settings).toList()
        assertTrue("expected >=1 utterance, got ${segs.size}", segs.isNotEmpty())
        assertTrue(segs.first().pcm.isNotEmpty())
        assertEquals(sr, segs.first().sampleRate)
        assertTrue(
            segs.first().reason == CutReason.Silence ||
                segs.first().reason == CutReason.StopFlush ||
                segs.first().reason == CutReason.MaxDuration
        )
    }

    @Test
    fun segment_maxDuration_forceCuts() = runBlocking {
        val sr = 16_000
        // Continuous loud speech longer than maxUtterance
        val pcm = buildPcm(sr, speechMs = 3000, silenceMs = 0, amplitude = 9000)
        val wav = writeWav(pcm, sr)
        val settings = UserSettings(
            silenceMs = 800,
            maxUtteranceMs = 1000,
            minUtteranceMs = 200
        )
        val segs = energySegmenter().segment(wav, settings).toList()
        assertTrue("expected multiple max-duration cuts, got ${segs.size}", segs.size >= 2)
        assertTrue(segs.any { it.reason == CutReason.MaxDuration })
    }

    private fun buildPcm(sr: Int, speechMs: Int, silenceMs: Int, amplitude: Int): ByteArray {
        val speechN = sr * speechMs / 1000
        val silenceN = sr * silenceMs / 1000
        val samples = ShortArray(speechN + silenceN)
        for (i in 0 until speechN) {
            val t = i.toDouble() / sr
            samples[i] = (sin(2 * Math.PI * 440 * t) * amplitude).toInt().toShort()
        }
        val bytes = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        return bytes
    }

    private fun writeWav(pcm: ByteArray, sampleRate: Int): File {
        val f = tmp.newFile("seg_${pcm.size}.wav")
        f.writeBytes(WavEncoder.buildHeader(pcm.size, sampleRate) + pcm)
        return f
    }
}
