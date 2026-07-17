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
        val c = OverlayScrollController(onShow = { shown.add(it) })
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
        val c = OverlayScrollController(onShow = { shown.add(it) })
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
        val c = OverlayScrollController(onShow = { shown.add(it) })
        c.onState(state(seg(1, "", "only zh")), UserSettings())
        // source blank and translation non-blank still enqueued via mapEn empty
        // Our controller skips only when BOTH blank — source blank alone still queues
        assertEquals(1, shown.size)
    }

    @Test
    fun backlog_firesWhenNextSentenceQueuedWhileScrolling() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val backlog = mutableListOf<Int>()
        val c = OverlayScrollController(
            onShow = { shown.add(it) },
            onBacklog = { backlog.add(it) }
        )
        c.finishBeforeNext = true
        val s = UserSettings()

        c.onState(state(seg(1, "a", "A")), s)
        assertEquals(1, shown.size)
        assertTrue(backlog.isEmpty())
        assertEquals(0, c.pendingCount)

        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        // Still on first caption; second is pending → catch-up signal
        assertEquals(1, shown.size)
        assertEquals(1, c.pendingCount)
        assertEquals(listOf(1), backlog)

        c.onState(
            state(seg(1, "a", "A"), seg(2, "b", "B"), seg(3, "c", "C")),
            s
        )
        assertEquals(1, shown.size)
        assertEquals(2, c.pendingCount)
        assertEquals(listOf(1, 2), backlog)

        c.onScrollFinished()
        // Shows "b" while "c" still pending → backlog again
        assertEquals(2, shown.size)
        assertEquals("b", shown[1].en)
        assertEquals(1, c.pendingCount)
        assertTrue(backlog.last() == 1)
    }

    @Test
    fun backlog_notFiredWhenFinishBeforeNextOff() {
        val backlog = mutableListOf<Int>()
        val c = OverlayScrollController(
            onShow = {},
            onBacklog = { backlog.add(it) }
        )
        c.finishBeforeNext = false
        val s = UserSettings()
        c.onState(state(seg(1, "a", "A")), s)
        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        assertTrue(backlog.isEmpty())
    }

    @Test
    fun catchUpSpeed_scalesWithDepthAndCaps() {
        assertEquals(60f, OverlayScrollController.catchUpSpeed(60f, 0), 0.01f)
        assertEquals(120f, OverlayScrollController.catchUpSpeed(60f, 1), 0.01f)
        assertEquals(180f, OverlayScrollController.catchUpSpeed(60f, 2), 0.01f)
        assertEquals(240f, OverlayScrollController.catchUpSpeed(60f, 3), 0.01f)
        // 4× of 160 would be 640 → capped at CATCH_UP_MAX
        assertEquals(
            OverlayScrollController.CATCH_UP_MAX_SPEED,
            OverlayScrollController.catchUpSpeed(160f, 4),
            0.01f
        )
        assertEquals(1f, OverlayScrollController.catchUpMultiplier(0), 0.01f)
        assertEquals(2f, OverlayScrollController.catchUpMultiplier(1), 0.01f)
        assertEquals(4f, OverlayScrollController.catchUpMultiplier(5), 0.01f)
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
