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

    @Test
    fun snapshot_lengthRegression() {
        assertEquals("hi", AsrTextMerger.merge("hi there", "hi"))
    }

    @Test
    fun snapshot_nearPrefixRewrite() {
        // Common Whisper correction: shared prefix, rewritten tail
        assertEquals("hello word", AsrTextMerger.merge("hello wrd", "hello word"))
    }

    @Test
    fun overlap_resendTail_noDuplicate() {
        // Provider re-emits last phrase then continues
        val prev = "今天天气很好我们一起去"
        val next = "我们一起去公园玩"
        assertEquals("今天天气很好我们一起去公园玩", AsrTextMerger.merge(prev, next))
    }

    @Test
    fun overlap_englishResend() {
        val prev = "The quick brown fox jumps"
        val next = "fox jumps over the lazy dog"
        assertEquals(
            "The quick brown fox jumps over the lazy dog",
            AsrTextMerger.merge(prev, next)
        )
    }
}
