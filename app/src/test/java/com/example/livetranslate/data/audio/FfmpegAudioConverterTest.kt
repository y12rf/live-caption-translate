package com.example.livetranslate.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAudioConverterTest {
    @Test
    fun quote_wrapsPathWithSpaces() {
        val q = FfmpegAudioConverter.quote("/sdcard/My Audio/file.mp3")
        assertEquals("'/sdcard/My Audio/file.mp3'", q)
    }

    @Test
    fun quote_escapesSingleQuotes() {
        val q = FfmpegAudioConverter.quote("/a/b'c.wav")
        assertTrue(q.startsWith("'"))
        assertTrue(q.endsWith("'"))
        assertTrue(q.contains("'\\''") || q.contains("b'\\''c"))
    }
}
