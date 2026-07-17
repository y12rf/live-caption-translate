package com.example.livetranslate.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmThinkingTest {
    @Test
    fun mode_fromStorage_legacyAndNew() {
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage(null))
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage("Default"))
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage("True"))
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage("enabled"))
        assertEquals(LlmThinkingMode.Disabled, LlmThinkingMode.fromStorage("False"))
        assertEquals(LlmThinkingMode.Disabled, LlmThinkingMode.fromStorage("disabled"))
    }

    @Test
    fun effort_fromStorage_compatMaps() {
        assertEquals(LlmReasoningEffort.High, LlmReasoningEffort.fromStorage(null))
        assertEquals(LlmReasoningEffort.High, LlmReasoningEffort.fromStorage("low"))
        assertEquals(LlmReasoningEffort.High, LlmReasoningEffort.fromStorage("medium"))
        assertEquals(LlmReasoningEffort.High, LlmReasoningEffort.fromStorage("high"))
        assertEquals(LlmReasoningEffort.Max, LlmReasoningEffort.fromStorage("max"))
        assertEquals(LlmReasoningEffort.Max, LlmReasoningEffort.fromStorage("xhigh"))
    }

    @Test
    fun body_enabled_openAi_hasThinkingObjectAndReasoningEffort() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Enabled,
            effort = LlmReasoningEffort.High,
            style = LlmReasoningEffortStyle.OpenAi
        )
        sb.append('}')
        val json = sb.toString()
        assertTrue(json.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(json.contains("\"reasoning_effort\":\"high\""))
        assertFalse(json.contains("output_config"))
        assertFalse(json.contains("\"thinking\":true"))
    }

    @Test
    fun body_enabled_anthropic_usesOutputConfigEffort() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Enabled,
            effort = LlmReasoningEffort.Max,
            style = LlmReasoningEffortStyle.Anthropic
        )
        sb.append('}')
        val json = sb.toString()
        assertTrue(json.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(json.contains("\"output_config\":{\"effort\":\"max\"}"))
        assertFalse(json.contains("reasoning_effort"))
    }

    @Test
    fun body_disabled_hasNoEffort() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Disabled,
            effort = LlmReasoningEffort.Max,
            style = LlmReasoningEffortStyle.OpenAi
        )
        sb.append('}')
        val json = sb.toString()
        assertTrue(json.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertFalse(json.contains("reasoning_effort"))
        assertFalse(json.contains("output_config"))
    }
}
