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
    fun packVad_batchesByCountWhenShort() {
        // Tiny PCM so duration never hits 30s; only count matters.
        val utts = (0 until 35).map { i ->
            UtteranceAudio(
                pcm = byteArrayOf(i.toByte(), 0),
                sampleRate = 16_000,
                reason = CutReason.Silence,
                offsetMs = i * 1000L
            )
        }
        val batches = WavChunker.packVadUtterances(
            utts,
            utterancesPerBatch = 15,
            maxBatchDurationMs = 30_000L
        )
        assertEquals(3, batches.size)
        assertEquals(15, batches[0].utteranceCount)
        assertEquals(15, batches[1].utteranceCount)
        assertEquals(5, batches[2].utteranceCount)
        assertEquals(0L, batches[0].startMs)
        assertEquals(15_000L, batches[1].startMs)
        assertEquals(15 * 2, batches[0].pcm.size)
        assertEquals(5 * 2, batches[2].pcm.size)
    }

    @Test
    fun packVad_flushesByDurationBeforeCount() {
        // 1 second of silence per utt @ 16 kHz mono 16-bit = 32000 bytes
        val oneSec = ByteArray(16_000 * 2)
        val utts = (0 until 10).map { i ->
            UtteranceAudio(
                pcm = oneSec,
                sampleRate = 16_000,
                reason = CutReason.Silence,
                offsetMs = i * 1000L
            )
        }
        // count cap 70 would allow all 10; duration 3s should flush every 3 utts
        val batches = WavChunker.packVadUtterances(
            utts,
            utterancesPerBatch = 70,
            maxBatchDurationMs = 3_000L
        )
        assertEquals(4, batches.size) // 3+3+3+1
        assertEquals(3, batches[0].utteranceCount)
        assertEquals(3, batches[1].utteranceCount)
        assertEquals(3, batches[2].utteranceCount)
        assertEquals(1, batches[3].utteranceCount)
        assertTrue(batches[0].durationMs <= 3_000L)
        assertEquals(3_000L, batches[1].startMs)
    }

    @Test
    fun packVad_singleLongUtteranceAlone() {
        val fiveSec = ByteArray(16_000 * 2 * 5)
        val short = ByteArray(16_000 * 2) // 1s
        val utts = listOf(
            UtteranceAudio(fiveSec, 16_000, CutReason.MaxDuration, 0L),
            UtteranceAudio(short, 16_000, CutReason.Silence, 5_000L)
        )
        val batches = WavChunker.packVadUtterances(
            utts,
            utterancesPerBatch = 70,
            maxBatchDurationMs = 3_000L
        )
        // First alone (already > cap), second alone (would exceed if merged)
        assertEquals(2, batches.size)
        assertEquals(1, batches[0].utteranceCount)
        assertEquals(1, batches[1].utteranceCount)
        assertEquals(5_000L, batches[0].durationMs)
        assertEquals(1_000L, batches[1].durationMs)
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
