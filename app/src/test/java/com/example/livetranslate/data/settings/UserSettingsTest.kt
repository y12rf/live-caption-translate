package com.example.livetranslate.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsTest {
    @Test
    fun renderLlmSystemPrompt_replacesToAndFrom() {
        val s = UserSettings(
            llmSystemPrompt = "精通 {{to}}，来自 {{from}}",
            inputLanguage = "en",
            outputLanguage = "zh"
        )
        assertEquals("精通 zh，来自 en", s.renderLlmSystemPrompt())
    }

    @Test
    fun defaultPrompt_containsToPlaceholder() {
        assertTrue(UserSettings.DEFAULT_LLM_SYSTEM_PROMPT.contains("{{to}}"))
        val rendered = UserSettings(outputLanguage = "Chinese").renderLlmSystemPrompt()
        assertTrue(rendered.contains("Chinese"))
        assertTrue(!rendered.contains("{{to}}"))
    }

    @Test
    fun parseColorHex_rrggbb() {
        assertEquals(0xFFFF0000.toInt(), UserSettings.parseColorHex("#FF0000", 0))
        assertEquals(0xFF00FF00.toInt(), UserSettings.parseColorHex("00FF00", 0))
    }

    @Test
    fun parseColorHex_shortAndArgb() {
        assertEquals(0xFFFFFFFF.toInt(), UserSettings.parseColorHex("#FFF", 0))
        assertEquals(0x80FF0000.toInt(), UserSettings.parseColorHex("#80FF0000", 0))
    }

    @Test
    fun normalizeColorHex_invalidFallsBack() {
        assertEquals("#FFFFFF", UserSettings.normalizeColorHex("nope", "#FFFFFF"))
        assertEquals("#112233", UserSettings.normalizeColorHex("#112233", "#000000"))
    }
}
