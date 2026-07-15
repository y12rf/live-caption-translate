package com.example.livetranslate.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SessionAudioRecorderPureTest {
    @Test
    fun wavHeader_matchesEncoder() {
        val pcmSize = 3200 // 100ms @ 16k mono 16-bit
        val header = WavEncoder.buildHeader(pcmSize, 16_000)
        assertEquals(44, header.size)
        // "RIFF"
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('I'.code.toByte(), header[1])
        // chunk size = 36 + data
        val chunk = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(36 + pcmSize, chunk)
        // data size at offset 40
        val dataSize = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(pcmSize, dataSize)
    }

    @Test
    fun streamingWrite_thenPatchHeader() {
        val tmp = File.createTempFile("session_test", ".wav")
        try {
            val sampleRate = 16_000
            val raf = java.io.RandomAccessFile(tmp, "rw")
            raf.write(WavEncoder.buildHeader(0, sampleRate))
            val frame = ShortArray(320) { (it % 100).toShort() } // 20ms
            val bytes = ByteArray(frame.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) bb.putShort(s)
            // 10 frames
            repeat(10) { raf.write(bytes) }
            val pcmBytes = 10 * bytes.size
            raf.seek(0)
            raf.write(WavEncoder.buildHeader(pcmBytes, sampleRate))
            raf.close()

            assertTrue(tmp.length() == 44L + pcmBytes)
            val all = tmp.readBytes()
            val dataSize = ByteBuffer.wrap(all, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(pcmBytes, dataSize)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun emptyRecording_shouldBeDiscarded() {
        // Mirrors finish() empty path: no PCM → no usable file
        val path: String? = null
        assertNull(SessionAudioRecorder.fileForPath(path))
        assertNull(SessionAudioRecorder.fileForPath("/no/such/file.wav"))
    }

    @Test
    fun fileForPath_rejectsTinyFiles() {
        val tmp = File.createTempFile("tiny", ".wav")
        try {
            tmp.writeBytes(ByteArray(20))
            assertNull(SessionAudioRecorder.fileForPath(tmp.absolutePath))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun fileForPath_acceptsValidWav() {
        val tmp = File.createTempFile("session_ok", ".wav")
        try {
            val pcm = ByteArray(100) { 1 }
            tmp.writeBytes(WavEncoder.pcm16MonoToWav(pcm, 16_000))
            val f = SessionAudioRecorder.fileForPath(tmp.absolutePath)
            assertNotNull(f)
            assertEquals(tmp.absolutePath, f!!.absolutePath)
        } finally {
            tmp.delete()
        }
    }
}
