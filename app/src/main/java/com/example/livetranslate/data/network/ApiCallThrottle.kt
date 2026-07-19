package com.example.livetranslate.data.network

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimum spacing between successive API **starts** for one client type (ASR or LLM).
 * 0 / negative interval = no wait. Thread-safe for concurrent callers of the same instance.
 */
class ApiCallThrottle {
    private val lastStartAtMs = AtomicLong(0L)

    /**
     * Suspend until at least [intervalMs] has passed since the previous successful
     * [await] on this instance, then mark a new start time.
     */
    suspend fun await(intervalMs: Int) {
        val gap = intervalMs.coerceAtLeast(0).toLong()
        if (gap <= 0L) {
            lastStartAtMs.set(System.currentTimeMillis())
            return
        }
        while (true) {
            val last = lastStartAtMs.get()
            val now = System.currentTimeMillis()
            val wait = if (last <= 0L) 0L else last + gap - now
            if (wait > 0L) {
                delay(wait)
                continue
            }
            val start = System.currentTimeMillis()
            if (lastStartAtMs.compareAndSet(last, start)) return
        }
    }

    fun reset() {
        lastStartAtMs.set(0L)
    }
}
