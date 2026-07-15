package com.example.livetranslate.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryCodecTest {
    @Test
    fun roundTrip() {
        val list = listOf(
            GlossaryEntry("API", "接口"),
            GlossaryEntry("latency", "延迟")
        )
        val json = GlossaryCodec.encode(list)
        assertEquals(list, GlossaryCodec.decode(json))
    }

    @Test
    fun dropsEmptySource() {
        val json = GlossaryCodec.encode(
            listOf(GlossaryEntry("", "x"), GlossaryEntry("ok", "好"))
        )
        assertEquals(listOf(GlossaryEntry("ok", "好")), GlossaryCodec.decode(json))
    }

    @Test
    fun formatBlock() {
        val block = GlossaryCodec.formatBlock(
            listOf(GlossaryEntry("a", "甲"), GlossaryEntry("b", "乙"))
        )
        assertEquals("a → 甲\nb → 乙", block)
        assertEquals("", GlossaryCodec.formatBlock(emptyList()))
    }

    @Test
    fun escapesQuotes() {
        val list = listOf(GlossaryEntry("say \"hi\"", "说「你好」"))
        assertEquals(list, GlossaryCodec.decode(GlossaryCodec.encode(list)))
    }

    @Test
    fun emptyJson() {
        assertTrue(GlossaryCodec.decode(null).isEmpty())
        assertTrue(GlossaryCodec.decode("[]").isEmpty())
    }
}
