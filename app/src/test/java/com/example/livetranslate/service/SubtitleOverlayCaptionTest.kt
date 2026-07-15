package com.example.livetranslate.service

import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.service.SubtitleOverlayService.Companion.toOverlayCaption
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOverlayCaptionTest {
    @Test
    fun prefersPartialsOverCumulative() {
        val cap = LiveSessionUiState(
            cumulativeEn = "Old EN line\nPrevious EN",
            cumulativeZh = "旧中文\n上一句",
            partialEn = "Streaming EN",
            partialZh = "流式中文"
        ).toOverlayCaption()
        assertEquals("Streaming EN", cap.en)
        assertEquals("流式中文", cap.zh)
    }

    @Test
    fun fallsBackToLastCompletedLine() {
        val cap = LiveSessionUiState(
            cumulativeEn = "First\nSecond line",
            cumulativeZh = "第一句\n第二句",
            partialEn = "",
            partialZh = ""
        ).toOverlayCaption()
        assertEquals("Second line", cap.en)
        assertEquals("第二句", cap.zh)
    }

    @Test
    fun emptyShowsEllipsis() {
        val cap = LiveSessionUiState().toOverlayCaption()
        assertEquals("…", cap.en)
        assertEquals("…", cap.zh)
    }
}
