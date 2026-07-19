package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun snapshot_lengthRegression_longEnough() {
        // Substantial Whisper-style rewrite (still ≥ half length, ≥ 16 chars)
        val prev = "hello world this is a long line"
        val next = "hello world this is" // 19 chars, > half of 31
        assertTrue(AsrTextMerger.isPlausibleSnapshotRegression(prev, next))
        assertEquals(next, AsrTextMerger.merge(prev, next))
    }

    @Test
    fun shortDelta_thatIsPrefix_mustNotWipeBuffer() {
        // MIMO-style: accumulator is long; next piece is a few chars that happen to
        // be a prefix of the buffer (or a short shared head) — must NOT replace.
        val prev = " Changed my entire life. I literally owe everything that I have right now."
        val next = " Ch" // prefix of prev, but tiny delta
        assertFalse(AsrTextMerger.isPlausibleSnapshotRegression(prev, next))
        val merged = AsrTextMerger.merge(prev, next)
        assertTrue(
            "expected buffer preserved/appended, got len=${merged.length}: $merged",
            merged.length >= prev.length
        )
        assertTrue(merged.contains("entire life"))
    }

    @Test
    fun mimoStyle_tokenDeltas_concatenateToFullUtterance() {
        // Replayed pattern from real mimo-v2.5-asr stream (block_001): pure word deltas.
        val deltas = listOf(
            " Changed",
            " my",
            " entire",
            " life.",
            " I",
            " literally",
            " owe",
            " everything",
            " that",
            " I",
            " have",
            " right",
            " now",
            " to",
            " Safir."
        )
        var acc = ""
        for (d in deltas) {
            acc = AsrTextMerger.merge(acc, d)
        }
        assertEquals(
            " Changed my entire life. I literally owe everything that I have right now to Safir.",
            acc
        )
    }

    @Test
    fun mimoStyle_laterShortDelta_doesNotResetToTail() {
        var acc = " Xi'an's metro network now stretches to over 400 kilometers."
        // A short piece that is a prefix of acc must not collapse the transcript
        acc = AsrTextMerger.merge(acc, " Xi")
        assertTrue(acc.contains("400 kilometers"))
        assertTrue(acc.length > 20)
    }

    @Test
    fun snapshot_nearPrefixRewrite() {
        // Whisper correction: long shared prefix, rewritten tail (both sides ≥ 12 chars)
        val prev = "hello world this"
        val next = "hello world that"
        assertEquals(next, AsrTextMerger.merge(prev, next))
    }

    @Test
    fun overlap_resendTail_noDuplicate() {
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
