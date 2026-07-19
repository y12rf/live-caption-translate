package com.example.livetranslate.domain.reprocess

import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.BatchTranslation
import com.example.livetranslate.domain.model.ContextTurn
import com.example.livetranslate.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end **I/O contract** tests for history reprocess after cut:
 *
 * ```
 * mock ASR block #1 → "AAA.BBB.CCC."
 * mock ASR block #2 → "DDD.EEE.FFF."
 *   → join → punctuation split → sources
 * mock LLM batch  → "译A|||译B|||…"
 *   → fail-closed parse → bilingual segments (offsetMs = 0)
 * ```
 *
 * No network / Android — only the pure stages [ReprocessEngine] uses after ASR blocks return.
 */
class HistoryReprocessAsrLlmIoTest {

    private val settings = UserSettings(
        inputLanguage = "en",
        outputLanguage = "zh",
        contextWindowSize = 2,
        llmApiKey = "test-key",
        asrApiKey = "test-key"
    )

    // -------------------------------------------------------------------------
    // Main scenario: two ASR blocks → six sources → one LLM batch
    // -------------------------------------------------------------------------

    @Test
    fun twoAsrBlocks_aaaBbbCcc_dddEeeFff_finalSegmentsMatch() {
        // --- 1) Mock ASR outputs (what each multi-sentence block would return) ---
        val asrBlock1 = "AAA.BBB.CCC."
        val asrBlock2 = "DDD.EEE.FFF."
        val asrBlocks = listOf(asrBlock1, asrBlock2)

        // --- 2) Same join as ReprocessEngine after all blocks succeed ---
        val fullText = HistoryReprocessText.joinBlockTranscripts(asrBlocks)
        assertEquals("AAA.BBB.CCC. DDD.EEE.FFF.", fullText)

        // --- 3) Punctuation split → source sentences for translate ---
        val sources = HistoryReprocessText.splitSources(fullText)
        assertEquals(
            listOf("AAA.", "BBB.", "CCC.", "DDD.", "EEE.", "FFF."),
            sources
        )

        // --- 4) Mock LLM batching (DEFAULT_BATCH_SIZE = 20 → single batch) ---
        val batches = BatchTranslation.chunkSources(sources, BatchTranslation.DEFAULT_BATCH_SIZE)
        assertEquals(1, batches.size)
        assertEquals(sources, batches[0])

        // What we would send to the model (user message contains ||| packed sources)
        val (system, user) = BatchTranslation.buildMessages(
            sources = batches[0],
            context = emptyList(),
            settings = settings,
            requireNonEmptySlots = true
        )
        assertTrue(system.contains("exactly 6"))
        assertTrue(
            user.contains(
                listOf("AAA.", "BBB.", "CCC.", "DDD.", "EEE.", "FFF.")
                    .joinToString(BatchTranslation.DELIMITER)
            )
        )

        // --- 5) Mock LLM response: 6 aligned slots ---
        val llmRaw = listOf("译AAA", "译BBB", "译CCC", "译DDD", "译EEE", "译FFF")
            .joinToString(BatchTranslation.DELIMITER)
        assertTrue(
            "LLM raw must pass fail-closed checks",
            BatchTranslation.isRawBatchFullySuccessful(llmRaw, expected = 6)
        )
        val translations = BatchTranslation.parseTranslations(llmRaw, expected = 6)

        // --- 6) Build final history rows (offsetMs always 0 on Re sessions) ---
        val segments = HistoryReprocessText.bilingualSegments(
            sources = sources,
            translations = translations,
            nowMs = 1_700_000_000_000L
        )

        assertEquals(6, segments.size)
        assertEquals(
            listOf("AAA.", "BBB.", "CCC.", "DDD.", "EEE.", "FFF."),
            segments.map { it.source }
        )
        assertEquals(
            listOf("译AAA", "译BBB", "译CCC", "译DDD", "译EEE", "译FFF"),
            segments.map { it.translation }
        )
        assertTrue(segments.all { it.offsetMs == 0L && !it.incomplete })
        assertTrue(segments.all { it.translation.isNotBlank() })
    }

    // -------------------------------------------------------------------------
    // Pipeline helper used by remaining tests (mirrors engine post-ASR path)
    // -------------------------------------------------------------------------

    /**
     * Minimal stand-in for: block ASR texts → split → batch LLM → segments.
     * [llmBatch] is called once per source chunk with the packed user payload context.
     */
    private fun runPostAsrPipeline(
        asrBlockTexts: List<String>,
        llmBatch: (sources: List<String>, context: List<ContextTurn>) -> String
    ): List<TranscriptSegment> {
        val full = HistoryReprocessText.joinBlockTranscripts(asrBlockTexts)
        val sources = HistoryReprocessText.splitSources(full)
        val batches = BatchTranslation.chunkSources(sources, BatchTranslation.DEFAULT_BATCH_SIZE)
        val window = ArrayDeque<ContextTurn>()
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val allZh = ArrayList<String>(sources.size)

        for (batch in batches) {
            val raw = llmBatch(batch, window.toList())
            if (!BatchTranslation.isRawBatchFullySuccessful(raw, batch.size)) {
                throw AssertionError("fail-closed: bad LLM batch for $batch → $raw")
            }
            val zhList = BatchTranslation.parseTranslations(raw, batch.size)
            for (i in batch.indices) {
                val src = batch[i]
                val zh = zhList[i].trim()
                allZh.add(zh)
                window.addLast(ContextTurn(src, zh))
                while (window.size > windowSize) window.removeFirst()
            }
        }
        return HistoryReprocessText.bilingualSegments(sources, allZh, nowMs = 42L)
    }

    @Test
    fun pipelineHelper_recordsAsrInputAndLlmIo() {
        val asrLog = ArrayList<String>()
        val llmRequests = ArrayList<List<String>>()
        val llmResponses = ArrayList<String>()

        // Fake ASR: two sequential block transcripts
        fun mockAsr(blockIndex: Int): String {
            val text = when (blockIndex) {
                0 -> "AAA.BBB.CCC."
                1 -> "DDD.EEE.FFF."
                else -> error("unexpected block $blockIndex")
            }
            asrLog.add(text)
            return text
        }

        val blockTexts = listOf(mockAsr(0), mockAsr(1))
        assertEquals(listOf("AAA.BBB.CCC.", "DDD.EEE.FFF."), asrLog)

        // Fake LLM: echo index-tagged Chinese for each source slot
        val segs = runPostAsrPipeline(blockTexts) { sources, _ ->
            llmRequests.add(sources)
            val raw = sources.mapIndexed { i, s ->
                "ZH($i:${s.trimEnd('.')})"
            }.joinToString(BatchTranslation.DELIMITER)
            llmResponses.add(raw)
            raw
        }

        // ASR I/O
        assertEquals(2, asrLog.size)
        assertEquals("AAA.BBB.CCC.", asrLog[0])
        assertEquals("DDD.EEE.FFF.", asrLog[1])

        // LLM I/O: one batch of 6
        assertEquals(1, llmRequests.size)
        assertEquals(
            listOf("AAA.", "BBB.", "CCC.", "DDD.", "EEE.", "FFF."),
            llmRequests[0]
        )
        assertEquals(1, llmResponses.size)
        assertTrue(llmResponses[0].split(BatchTranslation.DELIMITER).size == 6)

        // Final transcript
        assertEquals(6, segs.size)
        assertEquals("AAA.", segs[0].source)
        assertEquals("ZH(0:AAA)", segs[0].translation)
        assertEquals("FFF.", segs[5].source)
        assertEquals("ZH(5:FFF)", segs[5].translation)
    }

    @Test
    fun llmMissingSlot_failClosed_rejectsBatch() {
        // Only 2 slots for 3 sources
        val bad = "甲|||乙"
        assertTrue(BatchTranslation.hadCountMismatch(bad, 3))
        assertFalse(BatchTranslation.isRawBatchFullySuccessful(bad, 3))

        // Engine would fall back to single-sentence path; here we assert batch reject only.
        val padded = BatchTranslation.parseTranslations(bad, 3)
        assertEquals(listOf("甲", "乙", ""), padded)
        assertFalse(BatchTranslation.isBatchFullySuccessful(padded, 3))
    }

    @Test
    fun llmEmptyMiddleSlot_failClosed() {
        val raw = "甲||| |||丙"
        // empty middle after trim
        assertFalse(BatchTranslation.isRawBatchFullySuccessful(raw, 3))
    }

    @Test
    fun asrOnlyPath_noLlm_sixSourcesEmptyTranslation() {
        val full = HistoryReprocessText.joinBlockTranscripts(
            listOf("AAA.BBB.CCC.", "DDD.EEE.FFF.")
        )
        val sources = HistoryReprocessText.splitSources(full)
        val segs = HistoryReprocessText.asrOnlySegments(sources, nowMs = 1L)
        assertEquals(6, segs.size)
        assertTrue(segs.all { it.translation.isEmpty() && it.offsetMs == 0L && !it.incomplete })
        assertEquals("CCC.", segs[2].source)
        assertEquals("DDD.", segs[3].source)
    }

    @Test
    fun moreThanBatchSize_twoLlmCalls() {
        // 3+3 already one batch; force batch size 4 so 6 sources → 2 LLM calls
        val full = HistoryReprocessText.joinBlockTranscripts(
            listOf("AAA.BBB.CCC.", "DDD.EEE.FFF.")
        )
        val sources = HistoryReprocessText.splitSources(full)
        val chunks = BatchTranslation.chunkSources(sources, batchSize = 4)
        assertEquals(2, chunks.size)
        assertEquals(listOf("AAA.", "BBB.", "CCC.", "DDD."), chunks[0])
        assertEquals(listOf("EEE.", "FFF."), chunks[1])

        val llmCalls = ArrayList<List<String>>()
        val segs = runWithBatchSize(sources, batchSize = 4) { batch, _ ->
            llmCalls.add(batch)
            batch.joinToString(BatchTranslation.DELIMITER) { "T-$it" }
        }
        assertEquals(2, llmCalls.size)
        assertEquals(6, segs.size)
        assertEquals("T-AAA.", segs[0].translation)
        assertEquals("T-FFF.", segs[5].translation)
    }

    private fun runWithBatchSize(
        sources: List<String>,
        batchSize: Int,
        llmBatch: (List<String>, List<ContextTurn>) -> String
    ): List<TranscriptSegment> {
        val batches = BatchTranslation.chunkSources(sources, batchSize)
        val window = ArrayDeque<ContextTurn>()
        val allZh = ArrayList<String>()
        for (batch in batches) {
            val raw = llmBatch(batch, window.toList())
            require(BatchTranslation.isRawBatchFullySuccessful(raw, batch.size))
            val zhList = BatchTranslation.parseTranslations(raw, batch.size)
            for (i in batch.indices) {
                allZh.add(zhList[i].trim())
                window.addLast(ContextTurn(batch[i], zhList[i].trim()))
            }
        }
        return HistoryReprocessText.bilingualSegments(sources, allZh, nowMs = 1L)
    }
}
