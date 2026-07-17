package com.example.livetranslate.ui.caption

import com.example.livetranslate.data.settings.OverlayTextMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.model.TranscriptSegment

/**
 * Queue for ScrollLine overlay: only **committed** segments enter the queue.
 * When [finishBeforeNext] is true, the next caption is shown only after the
 * current marquee reports finished (no mid-scroll jump to the next utterance).
 * Streaming partials are never enqueued.
 *
 * When a new sentence is recognized while the current line is still scrolling,
 * [onBacklog] fires with the pending queue depth so the UI can **speed up**
 * the active marquee and catch up.
 */
class OverlayScrollController(
    private val onShow: (OverlayCaption) -> Unit,
    private val onBacklog: (queueDepth: Int) -> Unit = {}
) {
    data class OverlayCaption(val en: String, val zh: String)

    private data class Queued(val id: Long, var en: String, var zh: String)

    private val queue = ArrayDeque<Queued>()
    private val seenIds = LinkedHashSet<Long>()
    private var currentId: Long? = null
    private var scrolling = false
    var finishBeforeNext: Boolean = true

    /** Number of committed captions waiting behind the one currently shown. */
    val pendingCount: Int get() = queue.size

    fun reset() {
        queue.clear()
        seenIds.clear()
        currentId = null
        scrolling = false
    }

    /**
     * Ingest live session state; enqueue newly committed segments.
     */
    fun onState(state: LiveSessionUiState, settings: UserSettings) {
        val mode = settings.overlayTextModeEnum()
        val asrOnly = settings.asrOnlyMode
        var newlyEnqueued = 0
        for (seg in state.segments) {
            if (seg.source.isBlank() && seg.translation.isBlank()) continue
            if (seg.localId !in seenIds) {
                seenIds.add(seg.localId)
                // Bound memory for long sessions
                while (seenIds.size > 200) {
                    val first = seenIds.first()
                    seenIds.remove(first)
                }
                queue.addLast(Queued(seg.localId, mapEn(seg, mode), mapZh(seg, mode, asrOnly)))
                newlyEnqueued++
            } else {
                // Update translation when LLM finishes for a queued or current item
                val en = mapEn(seg, mode)
                val zh = mapZh(seg, mode, asrOnly)
                queue.find { it.id == seg.localId }?.let {
                    it.en = en
                    it.zh = zh
                }
                if (currentId == seg.localId && !scrolling) {
                    onShow(OverlayCaption(en, zh))
                }
            }
        }
        tryShowNext()
        // Next sentence recognized while current is still scrolling → catch up
        if (finishBeforeNext && scrolling && newlyEnqueued > 0 && queue.isNotEmpty()) {
            onBacklog(queue.size)
        }
    }

    /** Called when both caption lines finished their marquee/dwell. */
    fun onScrollFinished() {
        scrolling = false
        tryShowNext()
    }

    private fun tryShowNext() {
        if (scrolling && finishBeforeNext) return
        val next = queue.removeFirstOrNull() ?: return
        currentId = next.id
        scrolling = true
        onShow(OverlayCaption(next.en, next.zh))
        // Already have more queued: start this line at catch-up speed too
        if (finishBeforeNext && queue.isNotEmpty()) {
            onBacklog(queue.size)
        }
    }

    private fun mapEn(seg: TranscriptSegment, mode: OverlayTextMode): String =
        when (mode) {
            OverlayTextMode.TranslationOnly -> ""
            else -> seg.source.trim().takeLast(220)
        }

    private fun mapZh(seg: TranscriptSegment, mode: OverlayTextMode, asrOnly: Boolean): String =
        when {
            asrOnly -> ""
            mode == OverlayTextMode.SourceOnly -> ""
            else -> seg.translation.trim().takeLast(220)
        }

    companion object {
        /** Max temporary marquee speed while catching up (px/s). */
        const val CATCH_UP_MAX_SPEED = 480f

        /**
         * Speed multiplier for [queueDepth] pending captions behind the current one.
         * depth 0 → 1×, 1 → 2×, 2 → 3×, 3+ → 4×.
         */
        fun catchUpMultiplier(queueDepth: Int): Float {
            if (queueDepth <= 0) return 1f
            return (1 + queueDepth).coerceAtMost(4).toFloat()
        }

        /**
         * Effective marquee speed given base settings speed and pending queue depth.
         */
        fun catchUpSpeed(basePxS: Float, queueDepth: Int): Float {
            if (queueDepth <= 0) return basePxS
            return (basePxS * catchUpMultiplier(queueDepth)).coerceAtMost(CATCH_UP_MAX_SPEED)
        }
    }
}
