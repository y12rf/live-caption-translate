package com.example.livetranslate.ui.caption

import com.example.livetranslate.data.settings.OverlayTextMode
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
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
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
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
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
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
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
            onShow = { cap, _ -> shown.add(cap) },
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
        // Shows "b" while "c" still pending → catch-up depth passed on show, no extra
        // backlog required for the new line (depth is onShow arg); onState already done.
        assertEquals(2, shown.size)
        assertEquals("b", shown[1].en)
        assertEquals(1, c.pendingCount)
    }

    @Test
    fun onShow_receivesCatchUpDepthWhenQueueRemains() {
        val depths = mutableListOf<Int>()
        val c = OverlayScrollController(
            onShow = { _, depth -> depths.add(depth) }
        )
        c.finishBeforeNext = true
        val s = UserSettings()

        // Three ready at once: first shown with depth 2 behind it
        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B"), seg(3, "c", "C")), s)
        assertEquals(listOf(2), depths)

        c.onScrollFinished()
        assertEquals(listOf(2, 1), depths)

        c.onScrollFinished()
        assertEquals(listOf(2, 1, 0), depths)
    }

    @Test
    fun backlog_reassertedOnSubsequentStateWhileStillBacklogged() {
        val backlog = mutableListOf<Int>()
        val c = OverlayScrollController(
            onShow = { _, _ -> },
            onBacklog = { backlog.add(it) }
        )
        c.finishBeforeNext = true
        val s = UserSettings()
        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        assertEquals(listOf(1), backlog)

        // Same queue, re-emit state (e.g. partial/phase tick) → re-assert catch-up
        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        assertEquals(listOf(1, 1), backlog)
    }

    @Test
    fun backlog_notFiredWhenFinishBeforeNextOff() {
        val backlog = mutableListOf<Int>()
        val c = OverlayScrollController(
            onShow = { _, _ -> },
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

    @Test
    fun marqueeTravel_endsWhenLastGlyphAtRightEdge() {
        // text wider than content: travel = text - content (not text + gap)
        assertEquals(100f, CaptionLineView.marqueeTravelPx(400f, 300f), 0.01f)
        // fits: no travel
        assertEquals(0f, CaptionLineView.marqueeTravelPx(200f, 300f), 0.01f)
        assertEquals(0f, CaptionLineView.marqueeTravelPx(0f, 300f), 0.01f)
    }

    @Test
    fun dwellMs_shortensWithCatchUp() {
        assertEquals(1600L, CaptionLineView.dwellMsForCatchUp(0, 60f))
        assertEquals(350L, CaptionLineView.dwellMsForCatchUp(1, 120f))
        assertEquals(350L, CaptionLineView.dwellMsForCatchUp(2, 180f))
        assertEquals(0L, CaptionLineView.dwellMsForCatchUp(3, 240f))
        assertEquals(0L, CaptionLineView.dwellMsForCatchUp(1, 200f))
    }

    @Test
    fun holdsIncompleteUntilTranslationArrives_bothMode() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        c.finishBeforeNext = true
        val s = UserSettings(overlayTextMode = OverlayTextMode.Both.name)

        // ASR first: incomplete, empty ZH → held, not shown
        c.onState(state(seg(1, "hello", "", incomplete = true)), s)
        assertEquals(0, shown.size)
        assertEquals(0, c.pendingCount)

        // LLM done → released with both lines
        c.onState(state(seg(1, "hello", "你好", incomplete = false)), s)
        assertEquals(1, shown.size)
        assertEquals("hello", shown[0].en)
        assertEquals("你好", shown[0].zh)
    }

    @Test
    fun releasesHeldWhenFinalizedEmptyTranslation() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        val s = UserSettings(overlayTextMode = OverlayTextMode.Both.name)

        c.onState(state(seg(1, "uh", "", incomplete = true)), s)
        assertEquals(0, shown.size)

        // Filler skipped: incomplete=false, empty ZH — still show source
        c.onState(state(seg(1, "uh", "", incomplete = false)), s)
        assertEquals(1, shown.size)
        assertEquals("uh", shown[0].en)
        assertEquals("", shown[0].zh)
    }

    @Test
    fun releasesHeldOnFailedTranslation_stampBump() {
        // markSegmentFailed keeps incomplete=true / empty ZH but bumps timestampMs
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        val s = UserSettings(overlayTextMode = OverlayTextMode.Both.name)

        c.onState(state(seg(1, "hello", "", incomplete = true, stamp = 1000L)), s)
        assertEquals(0, shown.size)

        // Unrelated state re-emit (same stamp) must NOT release early
        c.onState(state(seg(1, "hello", "", incomplete = true, stamp = 1000L)), s)
        assertEquals(0, shown.size)

        // Fail path: same text, bumped stamp → release source-only caption
        c.onState(state(seg(1, "hello", "", incomplete = true, stamp = 2000L)), s)
        assertEquals(1, shown.size)
        assertEquals("hello", shown[0].en)
        assertEquals("", shown[0].zh)
    }

    @Test
    fun resetCatchUp_doesNotReplayExistingSegments() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        val s = UserSettings()
        val segs = listOf(seg(1, "a", "A"), seg(2, "b", "B"), seg(3, "c", "C"))
        c.resetCatchUp(segs)
        c.onState(state(*segs.toTypedArray()), s)
        assertEquals(0, shown.size)

        // Only brand-new id enqueues
        c.onState(state(*(segs + seg(4, "d", "D")).toTypedArray()), s)
        assertEquals(1, shown.size)
        assertEquals("d", shown[0].en)
    }

    @Test
    fun skipsBothBlankAfterModeMap() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        // SourceOnly maps ZH away; blank EN skipped earlier — use TranslationOnly empty
        val s = UserSettings(overlayTextMode = OverlayTextMode.TranslationOnly.name)
        c.onState(state(seg(1, "hello", "", incomplete = true)), s)
        // Held until ready; fail stamp with still empty ZH → maps to blank/blank → not shown
        c.onState(state(seg(1, "hello", "", incomplete = true, stamp = 2000L)), s)
        assertEquals(0, shown.size)
    }

    @Test
    fun notifyDisplayCleared_showsNextQueued() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        c.finishBeforeNext = true
        val s = UserSettings()

        c.onState(state(seg(1, "a", "A"), seg(2, "b", "B")), s)
        assertEquals(1, shown.size)
        assertEquals("a", shown[0].en)
        assertEquals(1, c.pendingCount)

        // Idle blank path: drop current mid-scroll, advance queue
        c.notifyDisplayCleared()
        assertEquals(2, shown.size)
        assertEquals("b", shown[1].en)
        assertEquals(0, c.pendingCount)
    }

    @Test
    fun sourceOnly_doesNotHoldForTranslation() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        val s = UserSettings(overlayTextMode = OverlayTextMode.SourceOnly.name)

        c.onState(state(seg(1, "hello", "", incomplete = true)), s)
        assertEquals(1, shown.size)
        assertEquals("hello", shown[0].en)
        assertEquals("", shown[0].zh)
    }

    @Test
    fun asrOnly_doesNotHoldForTranslation() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        val s = UserSettings(asrOnlyMode = true)

        c.onState(state(seg(1, "hello", "", incomplete = true)), s)
        assertEquals(1, shown.size)
        assertEquals("hello", shown[0].en)
        assertEquals("", shown[0].zh)
    }

    @Test
    fun updatesCurrentCaptionWhileScrolling_whenTranslationArrivesLate() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val updated = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(
            onShow = { cap, _ -> shown.add(cap) },
            onUpdate = { updated.add(it) }
        )
        c.finishBeforeNext = true
        // SourceOnly so first show is not held
        val s = UserSettings(overlayTextMode = OverlayTextMode.SourceOnly.name)
        c.onState(state(seg(1, "hello", "", incomplete = true)), s)
        assertEquals(1, shown.size)
        assertTrue(updated.isEmpty())

        // Mode Both with ZH ready while still on current caption
        val both = UserSettings(overlayTextMode = OverlayTextMode.Both.name)
        c.onState(state(seg(1, "hello", "你好", incomplete = false)), both)
        assertEquals(1, updated.size)
        assertEquals("你好", updated[0].zh)
        assertEquals("hello", updated[0].en)
    }

    @Test
    fun slowLlm_doesNotDropTranslationBehindFastSpeech() {
        val shown = mutableListOf<OverlayScrollController.OverlayCaption>()
        val c = OverlayScrollController(onShow = { cap, _ -> shown.add(cap) })
        c.finishBeforeNext = true
        val s = UserSettings(overlayTextMode = OverlayTextMode.Both.name)

        // Two ASR commits before any LLM result
        c.onState(
            state(
                seg(1, "one", "", incomplete = true),
                seg(2, "two", "", incomplete = true)
            ),
            s
        )
        assertEquals(0, shown.size)

        // First translation arrives → only first caption shows
        c.onState(
            state(
                seg(1, "one", "一", incomplete = false),
                seg(2, "two", "", incomplete = true)
            ),
            s
        )
        assertEquals(1, shown.size)
        assertEquals("one", shown[0].en)
        assertEquals("一", shown[0].zh)

        // Second translation arrives while first still scrolling → queued with ZH
        c.onState(
            state(
                seg(1, "one", "一", incomplete = false),
                seg(2, "two", "二", incomplete = false)
            ),
            s
        )
        assertEquals(1, shown.size)
        assertEquals(1, c.pendingCount)

        c.onScrollFinished()
        assertEquals(2, shown.size)
        assertEquals("two", shown[1].en)
        assertEquals("二", shown[1].zh)
    }

    private fun seg(
        id: Long,
        en: String,
        zh: String,
        incomplete: Boolean = zh.isBlank(),
        stamp: Long = id * 1000L
    ) = TranscriptSegment(
        source = en,
        translation = zh,
        cutReason = CutReason.Silence,
        incomplete = incomplete,
        timestampMs = stamp,
        localId = id
    )

    private fun state(vararg segs: TranscriptSegment) = LiveSessionUiState(
        segments = segs.toList()
    )
}
