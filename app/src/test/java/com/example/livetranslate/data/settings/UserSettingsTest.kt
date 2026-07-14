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
}
