package com.example.livetranslate.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildDisplayTest {
    @Test
    fun doesNotDuplicateWhenPartialEqualsLastLine() {
        val cumulative = "Hello world"
        val partial = "Hello world"
        assertEquals("Hello world", buildDisplay(cumulative, partial))
    }

    @Test
    fun doesNotDuplicateWhenPartialIsLastBlock() {
        val cumulative = "A\nHello world"
        val partial = "Hello world"
        assertEquals("A\nHello world", buildDisplay(cumulative, partial))
    }

    @Test
    fun appendsInFlightPartial() {
        assertEquals("A\nHel", buildDisplay("A", "Hel"))
    }

    @Test
    fun emptyShowsEllipsis() {
        assertEquals("", buildDisplay("", ""))
    }
}
