package com.example.livetranslate.domain.reprocess

/**
 * Build PCM cut windows from history sentence-start offsets.
 * [offsetMs] semantics match live/history: sentence head, not cut end.
 */
object TimelineWindowBuilder {

    /** Minimum window length kept as its own slice (ms). Shorter windows are merged forward. */
    const val MIN_WINDOW_MS = 200L

    /**
     * @param offsets sentence-start offsets in recording time (any order OK; will sort)
     * @param durationMs full WAV duration
     * @return non-empty windows, or empty if no usable axis
     * @throws IllegalArgumentException when duration invalid
     */
    fun build(offsets: List<Long>, durationMs: Long): List<TimeWindow> {
        if (durationMs <= 0L) {
            throw IllegalArgumentException("invalid audio duration")
        }
        if (offsets.isEmpty()) return emptyList()

        val hasAxis = offsets.any { it > 0L }
        if (!hasAxis && offsets.all { it <= 0L }) {
            // All-zero axis (old Re sessions): caller should use VAD path.
            return emptyList()
        }

        val sorted = offsets
            .map { it.coerceIn(0L, durationMs) }
            .sorted()

        val raw = ArrayList<TimeWindow>(sorted.size)
        for (i in sorted.indices) {
            val start = sorted[i]
            val end = if (i + 1 < sorted.size) sorted[i + 1] else durationMs
            if (end <= start) continue
            raw.add(TimeWindow(startMs = start, endMs = end, index = raw.size))
        }
        if (raw.isEmpty()) return emptyList()

        // Merge ultra-short windows into the next (or previous if last).
        val merged = ArrayList<TimeWindow>(raw.size)
        var i = 0
        while (i < raw.size) {
            var start = raw[i].startMs
            var end = raw[i].endMs
            while (end - start < MIN_WINDOW_MS && i + 1 < raw.size) {
                i++
                end = raw[i].endMs
            }
            // Still short at end: merge into previous if any
            if (end - start < MIN_WINDOW_MS && merged.isNotEmpty()) {
                val prev = merged.removeAt(merged.lastIndex)
                start = prev.startMs
            }
            if (end > start) {
                merged.add(TimeWindow(startMs = start, endMs = end, index = merged.size))
            }
            i++
        }
        return merged.mapIndexed { idx, w -> w.copy(index = idx) }
    }

    /** True when offsets look like a usable history timeline. */
    fun hasUsableTimeline(offsets: List<Long>): Boolean =
        offsets.any { it > 0L }
}
