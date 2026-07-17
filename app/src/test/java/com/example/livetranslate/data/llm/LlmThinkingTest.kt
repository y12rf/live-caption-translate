package com.example.livetranslate.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmThinkingTest {
    @Test
    fun mode_fromStorage_defaultEnabledDisabled() {
        assertEquals(LlmThinkingMode.Default, LlmThinkingMode.fromStorage(null))
        assertEquals(LlmThinkingMode.Default, LlmThinkingMode.fromStorage(""))
        assertEquals(LlmThinkingMode.Default, LlmThinkingMode.fromStorage("Default"))
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage("Enabled"))
        assertEquals(LlmThinkingMode.Enabled, LlmThinkingMode.fromStorage("True"))
        assertEquals(LlmThinkingMode.Disabled, LlmThinkingMode.fromStorage("False"))
        assertEquals(LlmThinkingMode.Disabled, LlmThinkingMode.fromStorage("disabled"))
    }

    @Test
    fun effort_fromStorage_fourLevels_defaultLow() {
        assertEquals(LlmReasoningEffort.Low, LlmReasoningEffort.fromStorage(null))
        assertEquals(LlmReasoningEffort.Low, LlmReasoningEffort.fromStorage("low"))
        assertEquals(LlmReasoningEffort.Medium, LlmReasoningEffort.fromStorage("medium"))
        assertEquals(LlmReasoningEffort.High, LlmReasoningEffort.fromStorage("high"))
        assertEquals(LlmReasoningEffort.Max, LlmReasoningEffort.fromStorage("max"))
        assertEquals(LlmReasoningEffort.Max, LlmReasoningEffort.fromStorage("xhigh"))
    }

    @Test
    fun body_default_appendsNothing() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Default,
            effort = LlmReasoningEffort.High
        )
        sb.append('}')
        val json = sb.toString()
        assertEquals("{\"messages\":[]}", json)
        assertFalse(json.contains("thinking"))
        assertFalse(json.contains("reasoning_effort"))
    }

    @Test
    fun body_enabled_openAi_hasThinkingObjectAndReasoningEffort() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Enabled,
            effort = LlmReasoningEffort.Low
        )
        sb.append('}')
        val json = sb.toString()
        assertTrue(json.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(json.contains("\"reasoning_effort\":\"low\""))
        assertFalse(json.contains("output_config"))
        assertFalse(json.contains("\"thinking\":true"))
    }

    @Test
    fun body_disabled_hasNoEffort() {
        val sb = StringBuilder("{\"messages\":[]")
        LlmThinkingBody.appendAfterMessages(
            sb,
            mode = LlmThinkingMode.Disabled,
            effort = LlmReasoningEffort.Max
        )
        sb.append('}')
        val json = sb.toString()
        assertTrue(json.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertFalse(json.contains("reasoning_effort"))
        assertFalse(json.contains("output_config"))
    }
}
