package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReprocessTitleTest {
    @Test
    fun prefixesOriginal() {
        assertEquals("Re课堂笔记", ReprocessTitle.reTitle("课堂笔记"))
    }

    @Test
    fun blankFallsBack() {
        assertEquals("Re未命名会话", ReprocessTitle.reTitle("  "))
        assertEquals("Re未命名会话", ReprocessTitle.reTitle(null))
    }

    @Test
    fun doesNotStripExistingRe() {
        assertEquals("ReRe课堂", ReprocessTitle.reTitle("Re课堂"))
    }

    @Test
    fun orphanTitle() {
        assertEquals("Re未保存录音", ReprocessTitle.orphanTitle())
    }
}
