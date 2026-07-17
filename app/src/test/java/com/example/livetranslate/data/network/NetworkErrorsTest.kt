package com.example.livetranslate.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetworkErrorsTest {
    @Test
    fun httpRetryableCodes() {
        assertTrue(NetworkErrors.isRetryableHttp(408))
        assertTrue(NetworkErrors.isRetryableHttp(429))
        assertTrue(NetworkErrors.isRetryableHttp(500))
        assertTrue(NetworkErrors.isRetryableHttp(503))
        assertFalse(NetworkErrors.isRetryableHttp(401))
        assertFalse(NetworkErrors.isRetryableHttp(400))
    }

    @Test
    fun throwableRetryable() {
        assertTrue(NetworkErrors.isRetryableThrowable(SocketTimeoutException("t")))
        assertTrue(NetworkErrors.isRetryableThrowable(UnknownHostException("h")))
        assertTrue(NetworkErrors.isRetryableThrowable(ConnectException("c")))
        assertTrue(NetworkErrors.isRetryableThrowable(IOException("connection reset")))
        assertTrue(
            NetworkErrors.isRetryableThrowable(
                IOException("wrap", SocketTimeoutException("inner"))
            )
        )
        // Unknown / non-transport errors must not burn retries
        assertFalse(NetworkErrors.isRetryableThrowable(IllegalStateException("bug")))
        assertFalse(NetworkErrors.isRetryableThrowable(IOException("unexpected parse failure")))
    }

    @Test
    fun userMessageMentionsTimeout() {
        val m = NetworkErrors.userMessage(SocketTimeoutException("x"), "ASR")
        assertTrue(m.contains("ASR"))
        assertTrue(m.contains("超时") || m.lowercase().contains("timeout"))
    }
}
