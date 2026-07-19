package com.example.livetranslate.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCallThrottleTest {
    @Test
    fun zeroInterval_doesNotBlock() = runBlocking {
        val t = ApiCallThrottle()
        val t0 = System.currentTimeMillis()
        t.await(0)
        t.await(0)
        assertTrue(System.currentTimeMillis() - t0 < 200)
    }

    @Test
    fun positiveInterval_spacesCalls() = runBlocking {
        val t = ApiCallThrottle()
        t.await(100)
        val t0 = System.currentTimeMillis()
        t.await(100)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("expected ~100ms gap, got ${elapsed}ms", elapsed >= 80)
    }
}
