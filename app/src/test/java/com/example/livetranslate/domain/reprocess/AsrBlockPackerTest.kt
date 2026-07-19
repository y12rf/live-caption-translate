package com.example.livetranslate.domain.reprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBlockPackerTest {

    private fun windows(count: Int, eachMs: Long = 1_000L): List<TimeWindow> {
        return (0 until count).map { i ->
            TimeWindow(
                startMs = i * eachMs,
                endMs = (i + 1) * eachMs,
                index = i
            )
        }
    }

    @Test
    fun pack_default20_splits25() {
        val blocks = AsrBlockPacker.pack(
            windows(25),
            AsrPackPolicy(sentencesPerBlock = 20, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertEquals(2, blocks.size)
        assertEquals(20, blocks[0].windowCount)
        assertEquals(5, blocks[1].windowCount)
        assertEquals(0, blocks[0].index)
        assertEquals(2, blocks[0].total)
    }

    @Test
    fun pack_max30_on35() {
        val blocks = AsrBlockPacker.pack(
            windows(35),
            AsrPackPolicy(sentencesPerBlock = 30, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertEquals(2, blocks.size)
        assertEquals(30, blocks[0].windowCount)
        assertEquals(5, blocks[1].windowCount)
    }

    @Test
    fun pack_durationCapBeforeCount() {
        // 10 windows × 10s = would be one block by count, but 90s cap → first 9 = 90s
        val blocks = AsrBlockPacker.pack(
            windows(12, eachMs = 10_000L),
            AsrPackPolicy(sentencesPerBlock = 20, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertTrue(blocks.size >= 2)
        assertTrue(blocks[0].durationMs <= 90_000L)
        assertEquals(9, blocks[0].windowCount) // 0..90s
        assertEquals(0L, blocks[0].startMs)
        assertEquals(90_000L, blocks[0].endMs)
    }

    @Test
    fun pack_singleOverCap_alone() {
        val w = listOf(TimeWindow(0L, 120_000L, 0))
        val blocks = AsrBlockPacker.pack(
            w,
            AsrPackPolicy(sentencesPerBlock = 20, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertEquals(1, blocks.size)
        assertEquals(120_000L, blocks[0].durationMs)
        assertEquals(1, blocks[0].windowCount)
    }

    @Test
    fun pack_empty() {
        assertTrue(AsrBlockPacker.pack(emptyList()).isEmpty())
    }

    @Test
    fun pack_exactDefault20_singleBlock() {
        val blocks = AsrBlockPacker.pack(
            windows(20),
            AsrPackPolicy(sentencesPerBlock = 20, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertEquals(1, blocks.size)
        assertEquals(20, blocks[0].windowCount)
        assertEquals(0L, blocks[0].startMs)
        assertEquals(20_000L, blocks[0].endMs)
    }

    @Test
    fun pack_continuousFileSlice_usesFirstStartLastEnd() {
        val w = listOf(
            TimeWindow(100L, 500L, 0),
            TimeWindow(500L, 900L, 1),
            TimeWindow(900L, 1_200L, 2)
        )
        val blocks = AsrBlockPacker.pack(
            w,
            AsrPackPolicy(sentencesPerBlock = 20, maxSentences = 30, maxBlockDurationMs = 90_000L)
        )
        assertEquals(1, blocks.size)
        assertEquals(100L, blocks[0].startMs)
        assertEquals(1_200L, blocks[0].endMs)
        assertEquals(3, blocks[0].windowCount)
    }

    @Test
    fun policy_clampsSentencesTo30() {
        val p = AsrPackPolicy(sentencesPerBlock = 99, maxSentences = 99).normalized()
        assertEquals(30, p.maxSentences)
        assertEquals(30, p.sentencesPerBlock)
    }

    @Test
    fun policy_defaultIsCPrime() {
        val d = AsrPackPolicy.Default
        assertEquals(20, d.sentencesPerBlock)
        assertEquals(30, d.maxSentences)
        assertEquals(90_000L, d.maxBlockDurationMs)
    }
}
