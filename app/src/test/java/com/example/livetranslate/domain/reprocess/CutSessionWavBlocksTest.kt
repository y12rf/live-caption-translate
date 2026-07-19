package com.example.livetranslate.domain.reprocess

import com.example.livetranslate.data.audio.WavEncoder
import com.example.livetranslate.data.audio.WavPcmReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline cut using the **same** history-reprocess planner as the app:
 * [HistoryReprocessPlanner.planFromTimeline] (SRT starts = segment offsetMs).
 *
 * Pairing:
 * - `test/session_20260718_045739.wav`
 * - `test/session_2_1784321859406.srt`
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*.CutSessionWavBlocksTest"
 * ```
 */
class CutSessionWavBlocksTest {

    @Test
    fun cutBlocksViaHistoryReprocessPlanner() {
        val wav = resolve("session_20260718_045739.wav")
        val srt = resolve("session_2_1784321859406.srt")
        assumeTrue("missing ${wav.absolutePath}", wav.isFile)
        assumeTrue("missing ${srt.absolutePath}", srt.isFile)

        val open = WavPcmReader.open(wav)
        require(open.channels == 1 && open.bitsPerSample == 16)

        val offsets = parseSrtStartOffsets(srt)
        val plan = HistoryReprocessPlanner.planFromTimeline(
            segmentOffsets = offsets,
            durationMs = open.durationMs,
            policy = AsrPackPolicy.Default
        )

        println(
            "HistoryReprocessPlanner: source=${plan.source} " +
                "windows=${plan.windowCount} blocks=${plan.blockCount} " +
                "durationMs=${plan.durationMs}"
        )

        val outDir = File(wav.parentFile, "chunks_session_20260718_045739")
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val manifest = StringBuilder()
        manifest.appendLine("# History reprocess cut plan (HistoryReprocessPlanner)")
        manifest.appendLine("# wav=${wav.name} srt=${srt.name}")
        manifest.appendLine(
            "# source=${plan.source} durationMs=${plan.durationMs} " +
                "srtCues=${offsets.size} windows=${plan.windowCount} blocks=${plan.blockCount}"
        )
        manifest.appendLine("# policy: ${plan.policy}")
        manifest.appendLine("index\tstartMs\tendMs\tdurMs\twindows\tfile")

        for (b in plan.blocks) {
            val pcm = WavPcmReader.readPcmRange(wav, b.startMs, b.endMs)
            val name = "block_%03d_%d-%dms.wav".format(b.index + 1, b.startMs, b.endMs)
            File(outDir, name).writeBytes(WavEncoder.pcm16MonoToWav(pcm, open.sampleRate))
            manifest.appendLine(
                "${b.index + 1}\t${b.startMs}\t${b.endMs}\t${b.durationMs}\t${b.windowCount}\t$name"
            )
            println(
                "  block ${b.index + 1}/${b.total}  " +
                    "${b.startMs}-${b.endMs}ms (${b.durationMs}ms) windows=${b.windowCount}"
            )
        }

        File(outDir, "manifest.tsv").writeText(manifest.toString())
        File(outDir, "windows.tsv").writeText(
            buildString {
                appendLine("index\tstartMs\tendMs\tdurMs")
                plan.windows.forEach { w ->
                    appendLine("${w.index + 1}\t${w.startMs}\t${w.endMs}\t${w.durationMs}")
                }
            }
        )
        println("Wrote ${plan.blockCount} blocks → ${outDir.absolutePath}")
    }

    private fun parseSrtStartOffsets(srt: File): List<Long> {
        val re = Regex(
            """(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})"""
        )
        return srt.readLines().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            val g = m.groupValues
            g[1].toLong() * 3_600_000L +
                g[2].toLong() * 60_000L +
                g[3].toLong() * 1_000L +
                g[4].toLong()
        }
    }

    private fun resolve(name: String): File {
        val candidates = listOf(
            File("test", name),
            File("../test", name),
            File("E:/Desktop/project/test", name)
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.last()
    }
}
