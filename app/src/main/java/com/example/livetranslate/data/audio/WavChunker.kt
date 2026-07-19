package com.example.livetranslate.data.audio

import android.content.Context
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.flow.collect
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline ASR batching helpers.
 *
 * Preferred path: Silero VAD → pack utterances into one ASR upload, flushed when
 * either [utterancesPerBatch] is reached **or** packed PCM exceeds [maxBatchDurationMs].
 * Duration cap is critical: long packs (minutes) cause many ASR models to drop the
 * opening and loop mid-phrases ("first block missing + repeated chunk").
 *
 * Legacy time-slice [chunkPcm] remains for tests / fallback.
 */
object WavChunker {
    /** Soft cap on VAD sentences per ASR request (secondary to duration). */
    const val UTTERANCES_PER_ASR = 15

    /**
     * Hard cap on packed PCM duration per ASR request (ms).
     * Aligns with the original offline design (~30s slices).
     */
    const val DEFAULT_MAX_BATCH_DURATION_MS = 30_000L

    /** Legacy fixed-duration slice (ms) for [chunkPcm]. */
    const val DEFAULT_CHUNK_MS = 30_000

    data class PcmChunk(
        val pcm: ByteArray,
        val sampleRate: Int,
        val index: Int,
        val total: Int,
        val startMs: Long,
        /** How many VAD utterances were merged into this ASR batch (0 if time-sliced). */
        val utteranceCount: Int = 0
    ) {
        /** Duration of this PCM blob in ms (16-bit mono). */
        val durationMs: Long
            get() = pcmDurationMs(pcm.size, sampleRate)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PcmChunk) return false
            return sampleRate == other.sampleRate &&
                index == other.index &&
                total == other.total &&
                startMs == other.startMs &&
                utteranceCount == other.utteranceCount &&
                pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var h = pcm.contentHashCode()
            h = 31 * h + sampleRate
            h = 31 * h + index
            h = 31 * h + total
            h = 31 * h + startMs.hashCode()
            h = 31 * h + utteranceCount
            return h
        }
    }

    /**
     * Silero VAD over the whole WAV, packing cuts until count **or** duration cap.
     * Streams VAD output so only one batch of utterances is held in RAM at a time
     * (avoids OOM on long lectures). Last batch may be shorter.
     */
    suspend fun chunkByVad(
        file: File,
        settings: UserSettings,
        appContext: Context,
        utterancesPerBatch: Int = UTTERANCES_PER_ASR,
        maxBatchDurationMs: Long = DEFAULT_MAX_BATCH_DURATION_MS,
        onProgress: (progress: Float, elapsedMs: Long) -> Unit = { _, _ -> }
    ): List<PcmChunk> {
        require(utterancesPerBatch > 0)
        require(maxBatchDurationMs > 0)
        val segmenter = FileAudioSegmenter(appContext = appContext)
        val batch = ArrayList<UtteranceAudio>(utterancesPerBatch.coerceAtMost(32))
        val provisional = ArrayList<PcmChunk>()
        var index = 0
        segmenter.segment(file, settings, onProgress).collect { utt ->
            if (shouldFlushBeforeAdd(batch, utt, utterancesPerBatch, maxBatchDurationMs)) {
                provisional.add(packGroup(batch, index, totalPlaceholder = -1))
                batch.clear()
                index++
            }
            batch.add(utt)
        }
        if (batch.isNotEmpty()) {
            provisional.add(packGroup(batch, index, totalPlaceholder = -1))
            batch.clear()
        }
        return finalizeTotals(provisional)
    }

    /**
     * Pack already-cut VAD utterances into ASR batches by count and/or duration.
     */
    fun packVadUtterances(
        utterances: List<UtteranceAudio>,
        utterancesPerBatch: Int = UTTERANCES_PER_ASR,
        maxBatchDurationMs: Long = DEFAULT_MAX_BATCH_DURATION_MS
    ): List<PcmChunk> {
        require(utterancesPerBatch > 0)
        require(maxBatchDurationMs > 0)
        if (utterances.isEmpty()) return emptyList()
        val batch = ArrayList<UtteranceAudio>(utterancesPerBatch.coerceAtMost(32))
        val provisional = ArrayList<PcmChunk>()
        var index = 0
        for (utt in utterances) {
            if (shouldFlushBeforeAdd(batch, utt, utterancesPerBatch, maxBatchDurationMs)) {
                provisional.add(packGroup(batch, index, totalPlaceholder = -1))
                batch.clear()
                index++
            }
            batch.add(utt)
        }
        if (batch.isNotEmpty()) {
            provisional.add(packGroup(batch, index, totalPlaceholder = -1))
        }
        return finalizeTotals(provisional)
    }

    /**
     * Flush current batch before adding [next] when count is full, or when packed
     * duration would exceed [maxBatchDurationMs]. A single utterance longer than
     * the cap is still emitted alone (cannot split further here).
     */
    internal fun shouldFlushBeforeAdd(
        batch: List<UtteranceAudio>,
        next: UtteranceAudio,
        utterancesPerBatch: Int,
        maxBatchDurationMs: Long
    ): Boolean {
        if (batch.isEmpty()) return false
        if (batch.size >= utterancesPerBatch) return true
        val rate = batch.first().sampleRate.takeIf { it > 0 } ?: next.sampleRate
        if (rate <= 0) return batch.size >= utterancesPerBatch
        val batchMs = pcmDurationMs(batch.sumOf { it.pcm.size }, rate)
        val nextMs = pcmDurationMs(next.pcm.size, next.sampleRate.takeIf { it > 0 } ?: rate)
        return batchMs + nextMs > maxBatchDurationMs
    }

    private fun finalizeTotals(provisional: List<PcmChunk>): List<PcmChunk> {
        val total = provisional.size
        if (total == 0) return emptyList()
        return provisional.mapIndexed { i, c ->
            PcmChunk(
                pcm = c.pcm,
                sampleRate = c.sampleRate,
                index = i,
                total = total,
                startMs = c.startMs,
                utteranceCount = c.utteranceCount
            )
        }
    }

    private fun packGroup(
        group: List<UtteranceAudio>,
        index: Int,
        totalPlaceholder: Int
    ): PcmChunk {
        val sampleRate = group.first().sampleRate
        val totalBytes = group.sumOf { it.pcm.size }
        val pcm = ByteArray(totalBytes)
        var pos = 0
        for (u in group) {
            System.arraycopy(u.pcm, 0, pcm, pos, u.pcm.size)
            pos += u.pcm.size
        }
        return PcmChunk(
            pcm = pcm,
            sampleRate = sampleRate,
            index = index,
            total = totalPlaceholder.coerceAtLeast(0),
            startMs = group.first().offsetMs.coerceAtLeast(0L),
            utteranceCount = group.size
        )
    }

    /** 16-bit mono PCM duration in ms. */
    fun pcmDurationMs(byteCount: Int, sampleRate: Int): Long {
        if (sampleRate <= 0 || byteCount <= 0) return 0L
        return (byteCount / 2L) * 1000L / sampleRate
    }

    /**
     * Read [file] and return successive PCM chunks of about [chunkMs] each.
     * Last chunk may be shorter. Empty / invalid audio → empty list.
     */
    fun chunkPcm(
        file: File,
        chunkMs: Int = DEFAULT_CHUNK_MS,
        targetSampleRate: Int = AudioCapture.ASR_SAMPLE_RATE
    ): List<PcmChunk> {
        require(chunkMs > 0)
        val open = WavPcmReader.open(file)
        if (open.bitsPerSample != 16) {
            throw IllegalArgumentException("仅支持 16-bit PCM，当前 ${open.bitsPerSample}")
        }
        val mono16k = readAllMono16k(open, targetSampleRate)
        if (mono16k.isEmpty()) return emptyList()

        val bytesPerChunk = (targetSampleRate * 2L * chunkMs / 1000L).toInt().coerceAtLeast(2)
        val aligned = bytesPerChunk - (bytesPerChunk % 2)
        if (aligned <= 0) return emptyList()

        val total = (mono16k.size + aligned - 1) / aligned
        val out = ArrayList<PcmChunk>(total)
        var offset = 0
        var index = 0
        while (offset < mono16k.size) {
            val end = minOf(offset + aligned, mono16k.size)
            val slice = mono16k.copyOfRange(offset, end)
            val startMs = offset / 2 * 1000L / targetSampleRate
            out.add(
                PcmChunk(
                    pcm = slice,
                    sampleRate = targetSampleRate,
                    index = index,
                    total = total,
                    startMs = startMs,
                    utteranceCount = 0
                )
            )
            index++
            offset = end
        }
        return out
    }

    private fun readAllMono16k(open: WavPcmReader.OpenWav, targetSampleRate: Int): ByteArray {
        val raw = open.openPcmStream().use { it.readBytes() }
        if (raw.isEmpty()) return ByteArray(0)
        val mono = toMono16(raw, open.channels)
        return if (open.sampleRate == targetSampleRate) {
            mono
        } else {
            resamplePcm16Mono(mono, open.sampleRate, targetSampleRate)
        }
    }

    private fun toMono16(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val samples = pcm.size / 2 / channels
        val out = ByteArray(samples * 2)
        val bbIn = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val bbOut = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) {
            var sum = 0
            repeat(channels) { sum += bbIn.short.toInt() }
            bbOut.putShort((sum / channels).toShort())
        }
        return out
    }

    private fun resamplePcm16Mono(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate || pcm.isEmpty()) return pcm
        val inSamples = pcm.size / 2
        val outSamples = (inSamples.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val inBuf = ShortArray(inSamples)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until inSamples) inBuf[i] = bb.short
        val out = ByteArray(outSamples * 2)
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outSamples) {
            val src = i.toDouble() * fromRate / toRate
            val i0 = src.toInt().coerceIn(0, inSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
            val frac = src - i0
            val v = inBuf[i0] * (1.0 - frac) + inBuf[i1] * frac
            outBb.putShort(
                v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            )
        }
        return out
    }
}
