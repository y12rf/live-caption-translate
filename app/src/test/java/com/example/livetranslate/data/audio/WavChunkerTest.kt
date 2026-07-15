package com.example.livetranslate.data.audio

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
    fun chunksCoverAllPcmWithoutOverlap() {
        val sampleRate = 16_000
        // 2.5 seconds of silence
        val pcm = ByteArray(sampleRate * 2 * 5 / 2) // 2.5s * 2 bytes
        val file = writeWav(tmp.newFile("t.wav"), pcm, sampleRate)
        val chunks = WavChunker.chunkPcm(file, chunkMs = 1000)
        assertTrue(chunks.size >= 2)
        val totalBytes = chunks.sumOf { it.pcm.size }
        assertEquals(pcm.size, totalBytes)
        assertEquals(0, chunks.first().index)
        assertEquals(chunks.size, chunks.first().total)
        // Continuous indices
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index) }
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int): File {
        val header = WavEncoder.buildHeader(pcm.size, sampleRate)
        file.writeBytes(header + pcm)
        return file
    }
}
