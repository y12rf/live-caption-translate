package com.example.livetranslate.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun open_standardPcm16Mono() {
        val pcm = ByteArray(3200) { (it % 50).toByte() }
        val file = writeWav(pcm, sampleRate = 16_000)
        val open = WavPcmReader.open(file)
        assertEquals(16_000, open.sampleRate)
        assertEquals(1, open.channels)
        assertEquals(16, open.bitsPerSample)
        assertEquals(pcm.size, open.dataSize)
        assertEquals(100L, open.durationMs) // 3200/2 / 16000 * 1000
        open.openPcmStream().use { stream ->
            val buf = ByteArray(pcm.size)
            assertEquals(pcm.size, stream.read(buf))
            assertTrue(buf.contentEquals(pcm))
        }
    }

    @Test
    fun open_rejectsEmpty() {
        val f = tmp.newFile("empty.wav")
        try {
            WavPcmReader.open(f)
            org.junit.Assert.fail("expected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun readPcmRange_middleSlice() {
        // 200ms @ 16k mono 16-bit = 6400 bytes
        val pcm = ByteArray(6400) { i -> (i % 127).toByte() }
        val file = writeWav(pcm, sampleRate = 16_000)
        // 50ms..150ms = 100ms = 3200 bytes
        val slice = WavPcmReader.readPcmRange(file, 50L, 150L)
        assertEquals(3200, slice.size)
        val expected = pcm.copyOfRange(1600, 4800) // 50ms=1600 bytes
        assertTrue(slice.contentEquals(expected))
    }

    private fun writeWav(pcm: ByteArray, sampleRate: Int): File {
        val f = tmp.newFile("t_${pcm.size}.wav")
        val header = WavEncoder.buildHeader(pcm.size, sampleRate)
        f.writeBytes(header + pcm)
        // sanity: LE size
        val chunk = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(36 + pcm.size, chunk)
        return f
    }
}
