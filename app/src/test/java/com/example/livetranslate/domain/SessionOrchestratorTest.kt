package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
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
}
