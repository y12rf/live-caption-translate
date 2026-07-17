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
                    systemPrompt = "你是一位精通 {{to}} 专业母语译者，致力于提供流畅、地道、符合表达习惯且高保真的翻译。",
                    authStyle = com.example.livetranslate.data.asr.ApiAuthStyle.Bearer
                )
            ).toList()
            val pieces = events.filterIsInstance<LlmStreamEvent.Delta>().map { it.text }
            assertEquals(listOf("你", "好"), pieces)
            val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
            assertEquals("你好", completed.fullText)
            val body = server.takeRequest().body.readUtf8()
            // Default LlmConfig thinking = Enabled → object form + high effort
            assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
            assertTrue(body.contains("\"reasoning_effort\":\"high\""))
            assertTrue(!body.contains("\"thinking\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun thinkingEnabled_openAi_afterMessages_andFullUrlUsedAsIs() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()
        try {
            val client = LlmClient(OkHttpClient())
            val full = server.url("/custom/llm").toString().trimEnd('/')
            client.translateStream(
                "hi",
                emptyList(),
                LlmConfig(
                    baseUrl = full,
                    apiKey = "key",
                    model = "m",
                    targetLanguage = "zh",
                    sourceLanguage = "en",
                    systemPrompt = "t",
                    fullUrl = true,
                    thinking = LlmThinkingMode.Enabled,
                    reasoningEffort = LlmReasoningEffort.Max,
                    reasoningEffortStyle = LlmReasoningEffortStyle.OpenAi
                )
            ).toList()
            val req = server.takeRequest()
            assertEquals("/custom/llm", req.path)
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
            assertTrue(body.contains("\"reasoning_effort\":\"max\""))
            val msgIdx = body.indexOf("\"messages\"")
            val thinkIdx = body.indexOf("\"thinking\"")
            assertTrue(msgIdx >= 0 && thinkIdx > msgIdx)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun thinkingDisabled_sentAsTypeDisabled() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()
        try {
            val client = LlmClient(OkHttpClient())
            val base = server.url("/").toString().trimEnd('/')
            client.translateStream(
                "hi",
                emptyList(),
                LlmConfig(
                    baseUrl = base,
                    apiKey = "key",
                    model = "m",
                    targetLanguage = "zh",
                    sourceLanguage = "en",
                    systemPrompt = "t",
                    thinking = LlmThinkingMode.Disabled
                )
            ).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
            assertTrue(!body.contains("reasoning_effort"))
            val msgIdx = body.indexOf("\"messages\"")
            val thinkIdx = body.indexOf("\"thinking\"")
            assertTrue(msgIdx >= 0 && thinkIdx > msgIdx)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun extractDelta_ignoresThinkingNestedContent() {
        val client = LlmClient(OkHttpClient())
        // Nested thinking.content must NOT be picked as the translation delta
        val payload =
            """{"choices":[{"delta":{"thinking":{"content":"reason..."},"content":"你好"}}]}"""
        assertEquals("你好", client.extractDeltaContent(payload))
    }

    @Test
    fun extractDelta_ignoresReasoningContentField() {
        val client = LlmClient(OkHttpClient())
        val onlyReasoning =
            """{"choices":[{"delta":{"reasoning_content":"let me think","content":null}}]}"""
        assertEquals(null, client.extractDeltaContent(onlyReasoning))

        val both =
            """{"choices":[{"delta":{"reasoning_content":"think","content":"译文"}}]}"""
        assertEquals("译文", client.extractDeltaContent(both))
    }

    @Test
    fun stripThinkingArtifacts_removesThinkTags() {
        assertEquals(
            "最终译文",
            LlmClient.stripThinkingArtifacts("<think>internal</think>最终译文")
        )
        assertEquals(
            "ok",
            LlmClient.stripThinkingArtifacts("<thinking>x</thinking>ok")
        )
    }
}
