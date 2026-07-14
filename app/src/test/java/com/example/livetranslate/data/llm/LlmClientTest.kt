package com.example.livetranslate.data.llm

import com.example.livetranslate.domain.model.LlmStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {
    @Test
    fun emitsAppendDeltas() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"你"}}]}

                    data: {"choices":[{"delta":{"content":"好"}}]}

                    data: [DONE]

                    """.trimIndent() + "\n"
                )
        )
        server.start()
        try {
            val client = LlmClient(OkHttpClient())
            val base = server.url("/").toString().trimEnd('/')
            val events = client.translateStream(
                "hello",
                emptyList(),
                LlmConfig(
                    baseUrl = base,
                    apiKey = "key",
                    model = "gpt-4o-mini",
                    targetLanguage = "zh",
                    sourceLanguage = "en",
                    systemPrompt = "你是一位精通 {{to}} 专业母语译者，致力于提供流畅、地道、符合表达习惯且高保真的翻译。"
                )
            ).toList()
            val pieces = events.filterIsInstance<LlmStreamEvent.Delta>().map { it.text }
            assertEquals(listOf("你", "好"), pieces)
            val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
            assertEquals("你好", completed.fullText)
        } finally {
            server.shutdown()
        }
    }
}
