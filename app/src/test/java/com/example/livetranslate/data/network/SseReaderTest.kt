package com.example.livetranslate.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SseReaderTest {
    @Test
    fun parsesDataLines_skipsDoneAndEmpty() {
        val raw = "data: {\"a\":1}\n\ndata: [DONE]\ndata: {\"b\":2}\n"
        val payloads = SseReader.parsePayloads(raw.byteInputStream().bufferedReader())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), payloads)
    }
}
