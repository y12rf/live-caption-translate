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
 * When the active text mode needs a translation (Both / TranslationOnly) and the
 * session is not ASR-only, the **first** sighting of an incomplete empty-ZH
 * segment is **held**. It is released when:
 * - translation becomes ready ([isTranslationReady]), or
 * - the segment's [TranscriptSegment.timestampMs] changes (LLM finish / fail path
 *   bumps the stamp even if ZH stays empty).
 *
 * Mid-scroll translation fill-ins still call [onUpdate] so a late ZH update can
 * paint onto the current caption without restarting the queue.
 *
 * When a new sentence is recognized while the current line is still scrolling,
 * [onBacklog] fires with the pending queue depth so the UI can **speed up**
 * the active marquee and catch up.
 */
class OverlayScrollController(
    private val onShow: (OverlayCaption) -> Unit,
    private val onBacklog: (queueDepth: Int) -> Unit = {},
    /** Refresh the currently displayed caption (e.g. translation just arrived). */
    private val onUpdate: (OverlayCaption) -> Unit = onShow
) {
    data class OverlayCaption(val en: String, val zh: String)

    private data class Queued(
        val id: Long,
        var en: String,
        var zh: String,
        var stampMs: Long
    )

    private val queue = ArrayDeque<Queued>()
    /** ASR-committed but waiting for translation settle before entering [queue]. */
    private val held = LinkedHashMap<Long, Queued>()
    private val seenIds = LinkedHashSet<Long>()
    private var currentId: Long? = null
    /** Last EN/ZH pushed to the UI for [currentId] (to avoid no-op onUpdate). */
    private var currentEn: String = ""
    private var currentZh: String = ""
    private var scrolling = false
    var finishBeforeNext: Boolean = true

    /** Number of committed captions waiting behind the one currently shown. */
    val pendingCount: Int get() = queue.size

    fun reset() {
        queue.clear()
        held.clear()
        seenIds.clear()
        currentId = null
        currentEn = ""
        currentZh = ""
        scrolling = false
    }

    /**
     * After layout switch: drop queue/display state but mark existing segments as
     * already seen so [onState] does not re-play the entire lecture.
     */
    fun resetCatchUp(segments: List<TranscriptSegment>) {
        queue.clear()
        held.clear()
        currentId = null
        currentEn = ""
        currentZh = ""
        scrolling = false
        seenIds.clear()
        for (seg in segments) {
            if (seg.localId != 0L) seenIds.add(seg.localId)
        }
    }

    /**
     * Overlay display was cleared externally (e.g. idle blank). Drop "currently
     * scrolling" so the next queued caption can show, without wiping the queue.
     */
    fun notifyDisplayCleared() {
        scrolling = false
        currentId = null
        currentEn = ""
        currentZh = ""
        tryShowNext()
    }

    /**
     * Ingest live session state; enqueue newly committed segments.
     */
    fun onState(state: LiveSessionUiState, settings: UserSettings) {
        val mode = settings.overlayTextModeEnum()
        val asrOnly = settings.asrOnlyMode
        val holdForZh = shouldHoldForTranslation(mode, asrOnly)
        var newlyEnqueued = 0
        for (seg in state.segments) {
            if (seg.source.isBlank() && seg.translation.isBlank()) continue
            val en = mapEn(seg, mode)
            val zh = mapZh(seg, mode, asrOnly)
            // Skip captions that map to nothing for the current text mode
            if (en.isBlank() && zh.isBlank()) {
                if (seg.localId !in seenIds) seenIds.add(seg.localId)
                continue
            }
            if (seg.localId !in seenIds) {
                seenIds.add(seg.localId)
                if (holdForZh && !isTranslationReady(seg, zh)) {
                    held[seg.localId] = Queued(seg.localId, en, zh, seg.timestampMs)
                    continue
                }
                queue.addLast(Queued(seg.localId, en, zh, seg.timestampMs))
                newlyEnqueued++
            } else {
                // Update translation when LLM finishes / fails for held / queued / current
                held[seg.localId]?.let { h ->
                    val stampChanged = seg.timestampMs != h.stampMs
                    h.en = en
                    h.zh = zh
                    h.stampMs = seg.timestampMs
                    // Ready text, or any real segment mutation (finish/fail bumps stamp).
                    // Unrelated LiveSessionUiState emissions keep the same stamp → stay held.
                    if (isTranslationReady(seg, zh) || stampChanged) {
                        held.remove(seg.localId)
                        queue.addLast(h)
                        newlyEnqueued++
                    }
                    return@let
                } ?: run {
                    queue.find { it.id == seg.localId }?.let {
                        it.en = en
                        it.zh = zh
                        it.stampMs = seg.timestampMs
                    }
                    // Refresh the line currently on screen when content changes
                    // (typically late translation), even while scrolling.
                    if (currentId == seg.localId && (en != currentEn || zh != currentZh)) {
                        currentEn = en
                        currentZh = zh
                        onUpdate(OverlayCaption(en, zh))
                    }
                }
            }
        }
        pruneSeenIds(state.segments)
        tryShowNext()
        // Next sentence recognized while current is still scrolling → catch up
        if (finishBeforeNext && scrolling && newlyEnqueued > 0 && queue.isNotEmpty()) {
            onBacklog(queue.size)
        }
    }

    /**
     * Bound [seenIds] without re-introducing long-session replay: only drop IDs that
     * are no longer in the active segment list / hold / queue / current caption.
     */
    private fun pruneSeenIds(segments: List<TranscriptSegment>) {
        if (seenIds.size <= SEEN_SOFT_CAP) return
        val keep = HashSet<Long>(segments.size + held.size + queue.size + 4)
        for (seg in segments) keep.add(seg.localId)
        keep.addAll(held.keys)
        for (q in queue) keep.add(q.id)
        currentId?.let { keep.add(it) }
        seenIds.retainAll(keep)
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
        currentEn = next.en
        currentZh = next.zh
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

        /**
         * When true, ScrollLine waits for a follow-up update before first show
         * (Both / TranslationOnly, and not ASR-only).
         */
        fun shouldHoldForTranslation(mode: OverlayTextMode, asrOnly: Boolean): Boolean {
            if (asrOnly) return false
            return mode == OverlayTextMode.Both || mode == OverlayTextMode.TranslationOnly
        }

        /**
         * Ready on first sighting when ZH has content or segment is already finalized
         * (`incomplete == false`).
         */
        fun isTranslationReady(seg: TranscriptSegment, mappedZh: String): Boolean {
            if (mappedZh.isNotBlank()) return true
            if (!seg.incomplete) return true
            return false
        }

        /** Start pruning [seenIds] only above this size. */
        private const val SEEN_SOFT_CAP = 400
    }
}
