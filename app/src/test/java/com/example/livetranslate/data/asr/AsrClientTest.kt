package com.example.livetranslate.data.asr

import com.example.livetranslate.domain.model.AsrStreamEvent
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrClientTest {
    @Test
    fun transcriptions_emitsDeltasFromSse() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"text\":\"Hel\"}\n\ndata: {\"text\":\"Hello\"}\n\ndata: [DONE]\n\n")
        )
        server.start()
        try {
            val client = AsrClient(OkHttpClient())
            val base = server.url("/").toString().trimEnd('/')
            val config = AsrConfig(
                baseUrl = base,
                apiKey = "key",
                model = "whisper-1",
                language = "en",
                apiStyle = AsrApiStyle.OpenAiTranscriptions,
                authStyle = ApiAuthStyle.Bearer
            )
            val events = client.transcribeStream(
                UtteranceAudio(ByteArray(320), 16000, CutReason.Silence),
                config
            ).toList()
            val texts = events.filterIsInstance<AsrStreamEvent.Delta>().map { it.text }
            assertTrue(texts.any { it.contains("Hello") || it == "Hello" })
            assertTrue(events.any { it is AsrStreamEvent.Completed })
            val recorded = server.takeRequest()
            assertTrue(recorded.path!!.contains("audio/transcriptions"))
            assertEquals("Bearer key", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun chatAudio_postsJsonWithApiKeyAndStream() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val client = AsrClient(OkHttpClient())
            val base = server.url("/").toString().trimEnd('/')
            val config = AsrConfig(
                baseUrl = base,
                apiKey = "mimo-secret",
                model = "mimo-v2.5-asr",
                language = "auto",
                apiStyle = AsrApiStyle.ChatCompletionsAudio,
                authStyle = ApiAuthStyle.ApiKeyHeader
            )
            val events = client.transcribeStream(
                UtteranceAudio(ByteArray(320), 16000, CutReason.Silence),
                config
            ).toList()
            assertTrue(events.any { it is AsrStreamEvent.Delta && it.text.contains("Hi") })
            val recorded = server.takeRequest()
            assertTrue(recorded.path!!.contains("chat/completions"))
            assertEquals("mimo-secret", recorded.getHeader("api-key"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("input_audio"))
            assertTrue(body.contains("mimo-v2.5-asr"))
            assertTrue(body.contains("asr_options"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun extractText_supportsDeltaField() {
        val client = AsrClient(OkHttpClient())
        assertTrue(client.extractText("{\"delta\":\"hi\"}") == "hi")
    }
}
