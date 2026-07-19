package com.example.livetranslate.data.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun minGreaterThanMax_warnsAndClamps() {
        val r = SettingsValidation.validate(
            UserSettings(minUtteranceMs = 5000, maxUtteranceMs = 1000)
        )
        assertTrue(r.warnings.any { it.contains("Min utterance") })
        assertTrue(r.sanitized.minUtteranceMs <= r.sanitized.maxUtteranceMs)
    }

    @Test
    fun batchSizeOutOfRange_warns() {
        val r = SettingsValidation.validate(UserSettings(offlineVadBatchSize = 0))
        assertTrue(r.warnings.any { it.contains("batch", ignoreCase = true) })
        assertTrue(r.sanitized.offlineVadBatchSize >= 1)
    }

    @Test
    fun emptyKeys_warn() {
        val r = SettingsValidation.validate(
            UserSettings(asrApiKey = "", llmApiKey = "")
        )
        assertTrue(r.warnings.any { it.contains("ASR API key") })
        assertTrue(r.warnings.any { it.contains("LLM API key") })
    }

    @Test
    fun apiIntervalOutOfRange_clamps() {
        val r = SettingsValidation.validate(
            UserSettings(asrApiIntervalMs = -5, llmApiIntervalMs = 999_999)
        )
        assertTrue(r.sanitized.asrApiIntervalMs == 0)
        assertTrue(r.sanitized.llmApiIntervalMs == 60_000)
        assertTrue(r.warnings.any { it.contains("ASR API interval") })
        assertTrue(r.warnings.any { it.contains("LLM API interval") })
    }
}
