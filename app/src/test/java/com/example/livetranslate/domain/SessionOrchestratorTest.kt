package com.example.livetranslate.domain

import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-logic checks related to orchestrator contracts.
 * Full orchestrator is validated via device QA + MockWebServer client tests.
 */
class SessionOrchestratorTest {
    @Test
    fun asrMerger_snapshotAndAppend() {
        assertEquals("Hello", AsrTextMerger.merge("Hel", "Hello"))
        assertEquals("Hello world", AsrTextMerger.merge("Hello ", "world"))
    }

    @Test
    fun utteranceAudio_preservesRecordingOffsetForSrt() {
        val pcm = ByteArray(4) { it.toByte() }
        val u = UtteranceAudio(pcm, 16_000, CutReason.Silence, offsetMs = 12_500L)
        assertEquals(12_500L, u.offsetMs)
        // Retry / queue path must keep the cut-time stamp (not re-derived at translate time).
        val retried = u.copy()
        assertEquals(12_500L, retried.offsetMs)
        assertEquals(u, retried)
        assertNotEquals(u, u.copy(offsetMs = 0L))
    }

    @Test
    fun transcriptSegment_localIdSurvivesCopyForLlmRetry() {
        val seg = com.example.livetranslate.domain.model.TranscriptSegment(
            source = "Hi",
            translation = "",
            cutReason = CutReason.Silence,
            incomplete = true,
            offsetMs = 1_000L,
            localId = 42L
        )
        val updated = seg.copy(translation = "你好", incomplete = false)
        assertEquals(42L, updated.localId)
        assertEquals(1_000L, updated.offsetMs)
        assertEquals("你好", updated.translation)
    }
}
