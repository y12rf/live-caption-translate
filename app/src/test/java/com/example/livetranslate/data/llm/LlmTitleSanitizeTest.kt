package com.example.livetranslate.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmTitleSanitizeTest {
    @Test
    fun stripsWrappersAndTakesFirstLine() {
        assertEquals(
            "量子计算导论",
            LlmClient.sanitizeTitle("标题：量子计算导论\n其他")
        )
        assertEquals(
            "课堂笔记",
            LlmClient.sanitizeTitle("\"课堂笔记\"")
        )
        assertEquals(
            "深度学习基础",
            LlmClient.sanitizeTitle("「深度学习基础」")
        )
    }
}
