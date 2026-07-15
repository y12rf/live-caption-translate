package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavChunkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun packVad_batchesEvery70() {
        val utts = (0 until 150).map { i ->
            UtteranceAudio(
                pcm = byteArrayOf(i.toByte(), 0),
                sampleRate = 16_000,
                reason = CutReason.Silence,
                offsetMs = i * 1000L
            )
        }
        val batches = WavChunker.packVadUtterances(utts, utterancesPerBatch = 70)
        assertEquals(3, batches.size)
        assertEquals(70, batches[0].utteranceCount)
        assertEquals(70, batches[1].utteranceCount)
        assertEquals(10, batches[2].utteranceCount)
        assertEquals(0L, batches[0].startMs)
        assertEquals(70_000L, batches[1].startMs)
        // PCM concatenated without loss
        assertEquals(70 * 2, batches[0].pcm.size)
        assertEquals(10 * 2, batches[2].pcm.size)
    }

    @Test
    fun packVad_empty() {
        assertTrue(WavChunker.packVadUtterances(emptyList()).isEmpty())
    }

    @Test
    fun chunksCoverAllPcmWithoutOverlap() {
        val sampleRate = 16_000
        val pcm = ByteArray(sampleRate * 2 * 5 / 2)
        val file = writeWav(tmp.newFile("t.wav"), pcm, sampleRate)
        val chunks = WavChunker.chunkPcm(file, chunkMs = 1000)
        assertTrue(chunks.size >= 2)
        val totalBytes = chunks.sumOf { it.pcm.size }
        assertEquals(pcm.size, totalBytes)
        assertEquals(0, chunks.first().index)
        assertEquals(chunks.size, chunks.first().total)
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index) }
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int): File {
        val header = WavEncoder.buildHeader(pcm.size, sampleRate)
        file.writeBytes(header + pcm)
        return file
    }
}
