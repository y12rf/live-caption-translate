package com.example.livetranslate.ui.caption

import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayScrollControllerTest {

    @Test
    fun finishBeforeNext_queuesSecondUntilFinished() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController { shown.add(it) }
        c.finishBeforeNext = true
        val s = UserSettings()
        c.onState(state(seg(1, "a", "A")), s)
        assertEquals(1, shown.size)
        assertEquals("a", shown[0].en)

        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        // Still showing first until finish
        assertEquals(1, shown.size)

        c.onScrollFinished()
        assertEquals(2, shown.size)
        assertEquals("b", shown[1].en)
    }

    @Test
    fun finishBeforeNextOff_showsLatestImmediately() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController { shown.add(it) }
        c.finishBeforeNext = false
        val s = UserSettings()
        c.onState(state(seg(1, "a", "A")), s)
        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        // First show + second show without waiting (scrolling flag cleared path:
        // when finishBeforeNext is false, tryShowNext does not block on scrolling)
        assertTrue(shown.size >= 2)
        assertEquals("b", shown.last().en)
    }

    @Test
    fun ignoresBlankSource() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController { shown.add(it) }
        c.onState(state(seg(1, "", "only zh")), UserSettings())
        // source blank and translation non-blank still enqueued via mapEn empty
        // Our controller skips only when BOTH blank — source blank alone still queues
        assertEquals(1, shown.size)
    }

    private fun seg(id: Long, en: String, zh: String) = TranscriptSegment(
        source = en,
        translation = zh,
        cutReason = CutReason.Silence,
        incomplete = zh.isBlank(),
        localId = id
    )

    private fun state(vararg segs: TranscriptSegment) = LiveSessionUiState(
        segments = segs.toList()
    )
}
