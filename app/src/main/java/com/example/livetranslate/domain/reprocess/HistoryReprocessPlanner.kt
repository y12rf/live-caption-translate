package com.example.livetranslate.domain.reprocess

/**
 * Pure **history re-run** cut planner (scheme C′), shared by [ReprocessEngine] and offline tools.
 *
 * History path (what "重新识别翻译" uses when the session has sentence offsets):
 * ```
 * segmentOffsets (sentence starts / SRT cue starts)
 *   → TimelineWindowBuilder.build
 *   → AsrBlockPacker.pack (20 sentences / 90s valve)
 *   → List<AsrBlock>  // continuous PCM slices for multi-sentence ASR
 * ```
 *
 * Orphan / no-axis path builds [TimeWindow]s via VAD first, then calls [planFromWindows].
 *
 * No I/O, no Android, no ASR/LLM — only the cut plan.
 */
object HistoryReprocessPlanner {

    enum class WindowSource {
        /** History timeline / SRT sentence-start offsets. */
        Timeline,
        /** Pre-built windows (typically VAD for orphan recovery). */
        ExternalWindows
    }

    /**
     * Full cut plan for one WAV duration.
     *
     * @param windows sentence-level ranges used only to decide pack boundaries
     * @param blocks multi-sentence ASR upload ranges (continuous file slices)
     */
    data class CutPlan(
        val durationMs: Long,
        val windows: List<TimeWindow>,
        val blocks: List<AsrBlock>,
        val policy: AsrPackPolicy,
        val source: WindowSource
    ) {
        val windowCount: Int get() = windows.size
        val blockCount: Int get() = blocks.size
    }

    /**
     * History reprocess cut: offsets must form a usable timeline
     * ([TimelineWindowBuilder.hasUsableTimeline]).
     *
     * @throws IllegalArgumentException when duration invalid, timeline unusable, or result empty
     */
    fun planFromTimeline(
        segmentOffsets: List<Long>,
        durationMs: Long,
        policy: AsrPackPolicy = AsrPackPolicy.Default
    ): CutPlan {
        require(durationMs > 0L) { "invalid audio duration: $durationMs" }
        require(TimelineWindowBuilder.hasUsableTimeline(segmentOffsets)) {
            "no usable history timeline (empty or all-zero offsets)"
        }
        val windows = TimelineWindowBuilder.build(segmentOffsets, durationMs)
        require(windows.isNotEmpty()) { "timeline produced no windows" }
        return planFromWindows(windows, durationMs, policy, WindowSource.Timeline)
    }

    /**
     * Pack existing windows (VAD orphan path, or tests injecting windows).
     *
     * @throws IllegalArgumentException when duration invalid or windows/blocks empty
     */
    fun planFromWindows(
        windows: List<TimeWindow>,
        durationMs: Long,
        policy: AsrPackPolicy = AsrPackPolicy.Default,
        source: WindowSource = WindowSource.ExternalWindows
    ): CutPlan {
        require(durationMs > 0L) { "invalid audio duration: $durationMs" }
        require(windows.isNotEmpty()) { "windows empty" }
        val normalized = policy.normalized()
        val blocks = AsrBlockPacker.pack(windows, normalized)
        require(blocks.isNotEmpty()) { "packer produced no blocks" }
        return CutPlan(
            durationMs = durationMs,
            windows = windows,
            blocks = blocks,
            policy = normalized,
            source = source
        )
    }

    /**
     * Same decision as [ReprocessEngine]: prefer timeline when usable, otherwise null
     * so the caller runs VAD and then [planFromWindows].
     */
    fun tryPlanFromTimeline(
        segmentOffsets: List<Long>,
        durationMs: Long,
        policy: AsrPackPolicy = AsrPackPolicy.Default
    ): CutPlan? {
        if (durationMs <= 0L) return null
        if (!TimelineWindowBuilder.hasUsableTimeline(segmentOffsets)) return null
        val windows = TimelineWindowBuilder.build(segmentOffsets, durationMs)
        if (windows.isEmpty()) return null
        return planFromWindows(windows, durationMs, policy, WindowSource.Timeline)
    }
}
