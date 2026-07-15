package com.example.livetranslate.data.network

import com.example.livetranslate.data.settings.UserSettings
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiLatencyProbeTest {

    @Test
    fun probeLlm_measuresSuccessLatency() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}""")
                .setBodyDelay(50, TimeUnit.MILLISECONDS)
        )
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val probe = ApiLatencyProbe(OkHttpClient())
            val r = probe.probeLlm(
                UserSettings(
                    llmBaseUrl = base,
                    llmApiKey = "k",
                    llmModel = "m",
                    llmFullUrl = false
                )
            )
            assertTrue(r.ok)
            assertEquals(200, r.httpCode)
            assertTrue(r.latencyMs >= 40)
            assertTrue(r.url.contains("/v1/chat/completions"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun probeAsr_reportsHttpError() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val probe = ApiLatencyProbe(OkHttpClient())
            val r = probe.probeAsr(
                UserSettings(
                    asrBaseUrl = base,
                    asrApiKey = "k",
                    asrModel = "whisper-1",
                    asrFullUrl = false
                )
            )
            assertTrue(!r.ok)
            assertEquals(401, r.httpCode)
            assertTrue(r.latencyMs >= 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun probeLlm_emptyKey_failsFast() {
        val probe = ApiLatencyProbe(OkHttpClient())
        val r = probe.probeLlm(UserSettings(llmApiKey = ""))
        assertTrue(!r.ok)
        assertTrue(r.detail.contains("empty", ignoreCase = true))
    }
}
