package com.example.livetranslate.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiUrlResolverTest {

    @Test
    fun fullPathAfterV1_usedAsIs() {
        val full = "https://api.xiaomimimo.com/v1/chat/completions"
        assertEquals(
            full,
            ApiUrlResolver.resolve(full, "/v1/audio/transcriptions")
        )
        assertTrue(ApiUrlResolver.isFullEndpoint(full))
    }

    @Test
    fun hostOnly_appendsOpenAiStylePath() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            ApiUrlResolver.resolve("https://api.openai.com", "/v1/audio/transcriptions")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiUrlResolver.resolve("https://api.openai.com/", "/v1/chat/completions")
        )
        assertFalse(ApiUrlResolver.isFullEndpoint("https://api.openai.com"))
    }

    @Test
    fun hostWithV1Only_appendsPath() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiUrlResolver.resolve("https://api.openai.com/v1", "/v1/chat/completions")
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            ApiUrlResolver.resolve("https://api.openai.com/v1/", "/v1/audio/transcriptions")
        )
    }

    @Test
    fun customDeepPath_kept() {
        val full = "https://gateway.example.com/proxy/v1/foo/bar"
        assertEquals(full, ApiUrlResolver.resolve(full, "/v1/chat/completions"))
    }

    @Test
    fun fullUrlFlag_neverAppendsPath() {
        val custom = "https://my.gateway.com/custom/translate"
        assertEquals(
            custom,
            ApiUrlResolver.resolve(custom, "/v1/chat/completions", fullUrl = true)
        )
        // Without flag, host-only still appends
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiUrlResolver.resolve("https://api.openai.com", "/v1/chat/completions", fullUrl = false)
        )
    }
}
