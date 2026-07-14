package com.example.livetranslate.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {
    @Test
    fun headerAndSize_areCorrect() {
        val pcm = ByteArray(4) { 1 }
        val wav = WavEncoder.pcm16MonoToWav(pcm, sampleRate = 16000)
        assertEquals(44 + 4, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals(1, wav[20].toInt() and 0xFF)
        assertEquals(0, wav[21].toInt() and 0xFF)
        assertEquals(1, wav[22].toInt() and 0xFF)
    }
}
