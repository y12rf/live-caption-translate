package com.example.livetranslate.data.asr

import com.example.livetranslate.domain.model.AsrStreamEvent
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrClientTest {
    @Test
    fun emitsDeltasFromSse() = runTest {
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
            val config = AsrConfig(base, "key", "whisper-1", "en")
            val events = client.transcribeStream(
                UtteranceAudio(ByteArray(320), 16000, CutReason.Silence),
                config
            ).toList()
            val texts = events.filterIsInstance<AsrStreamEvent.Delta>().map { it.text }
            assertTrue(texts.any { it.contains("Hello") || it == "Hello" })
            assertTrue(events.any { it is AsrStreamEvent.Completed })
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
