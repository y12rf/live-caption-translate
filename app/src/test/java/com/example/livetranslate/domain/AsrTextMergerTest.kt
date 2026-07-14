package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrTextMergerTest {
    @Test
    fun snapshot_whenPrefix() {
        assertEquals("hello world", AsrTextMerger.merge("hello", "hello world"))
    }

    @Test
    fun append_whenNotPrefix() {
        assertEquals("hello world", AsrTextMerger.merge("hello ", "world"))
    }
}
