package com.example.livetranslate.domain.reprocess

/**
 * Pack [TimeWindow]s into multi-sentence ASR blocks (scheme C′).
 * Closes a block when sentence count hits [AsrPackPolicy.sentencesPerBlock]
 * or cumulative span hits [AsrPackPolicy.maxBlockDurationMs] (whichever first).
 */
object AsrBlockPacker {

    fun pack(
        windows: List<TimeWindow>,
        policy: AsrPackPolicy = AsrPackPolicy.Default
    ): List<AsrBlock> {
        if (windows.isEmpty()) return emptyList()
        val p = policy.normalized()
        val maxN = p.sentencesPerBlock
        val maxDur = p.maxBlockDurationMs

        val provisional = ArrayList<Triple<Long, Long, Int>>() // start, end, count
        var blockStart = windows.first().startMs
        var blockEnd = windows.first().endMs
        var count = 0

        fun flush() {
            if (count <= 0) return
            provisional.add(Triple(blockStart, blockEnd, count))
            count = 0
        }

        for (w in windows) {
            if (count == 0) {
                blockStart = w.startMs
                blockEnd = w.endMs
                count = 1
                // Single window longer than cap: still emit alone
                if (w.durationMs > maxDur) {
                    flush()
                }
                continue
            }
            val nextEnd = w.endMs
            val nextCount = count + 1
            val span = nextEnd - blockStart
            val wouldExceedCount = nextCount > maxN
            val wouldExceedDur = span > maxDur
            if (wouldExceedCount || wouldExceedDur) {
                flush()
                blockStart = w.startMs
                blockEnd = w.endMs
                count = 1
                if (w.durationMs > maxDur) {
                    flush()
                }
            } else {
                blockEnd = nextEnd
                count = nextCount
            }
        }
        flush()

        val total = provisional.size
        return provisional.mapIndexed { idx, (start, end, c) ->
            AsrBlock(
                startMs = start,
                endMs = end,
                windowCount = c,
                index = idx,
                total = total
            )
        }
    }
}
