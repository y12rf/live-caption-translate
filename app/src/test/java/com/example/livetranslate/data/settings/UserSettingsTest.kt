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
    fun renderLlmSystemPrompt_glossaryEmptyAndFilled() {
        val empty = UserSettings(
            llmSystemPrompt = "T:\n{{glossary}}",
            glossaryTerms = emptyList()
        )
        assertEquals("T:\n", empty.renderLlmSystemPrompt())

        val filled = UserSettings(
            llmSystemPrompt = "T:\n{{glossary}}",
            glossaryTerms = listOf(GlossaryEntry("API", "接口"))
        )
        assertEquals("T:\nAPI → 接口", filled.renderLlmSystemPrompt())
    }

    @Test
    fun render_withoutGlossaryPlaceholder_doesNotInject() {
        val s = UserSettings(
            llmSystemPrompt = "plain only {{to}}",
            outputLanguage = "zh",
            glossaryTerms = listOf(GlossaryEntry("a", "b"))
        )
        val r = s.renderLlmSystemPrompt()
        assertEquals("plain only zh", r)
        assertTrue(!r.contains("a → b"))
    }

    @Test
    fun defaultPrompt_containsRequiredPlaceholders() {
        val p = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT
        assertTrue(p.contains("{{from}}"))
        assertTrue(p.contains("{{to}}"))
        assertTrue(p.contains("{{glossary}}"))
        // English structured rules for simultaneous interpretation
        assertTrue(p.contains("Output ONLY the translation"))
        assertTrue(p.contains("Glossary"))
        val rendered = UserSettings(
            inputLanguage = "en",
            outputLanguage = "Chinese"
        ).renderLlmSystemPrompt()
        assertTrue(rendered.contains("Chinese"))
        assertTrue(rendered.contains("en"))
        assertTrue(!rendered.contains("{{from}}"))
        assertTrue(!rendered.contains("{{to}}"))
        assertTrue(!rendered.contains("{{glossary}}"))
    }

    @Test
    fun defaultUserAndTitlePrompts_havePlaceholders() {
        assertTrue(UserSettings.DEFAULT_LLM_USER_PROMPT.contains("{{history}}"))
        assertTrue(UserSettings.DEFAULT_LLM_USER_PROMPT.contains("{{text}}"))
        assertTrue(UserSettings.DEFAULT_LLM_TITLE_USER_PROMPT.contains("{{dialogue}}"))
        assertTrue(UserSettings.DEFAULT_LLM_TITLE_SYSTEM_PROMPT.isNotBlank())
    }

    @Test
    fun toLlmConfig_carriesEditablePrompts() {
        val s = UserSettings(
            llmUserPrompt = "U {{text}}",
            llmTitleSystemPrompt = "TS",
            llmTitleUserPrompt = "TU {{dialogue}}",
            llmApiKey = "k",
            llmModel = "m"
        )
        val c = s.toLlmConfig()
        assertEquals("U {{text}}", c.userPromptTemplate)
        assertEquals("TS", c.titleSystemPrompt)
        assertEquals("TU {{dialogue}}", c.titleUserPromptTemplate)
        assertTrue(c.systemPrompt.isNotBlank())
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
