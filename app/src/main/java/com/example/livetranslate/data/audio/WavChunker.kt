package com.example.livetranslate.data.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Split a PCM WAV into fixed-duration mono 16-bit chunks for offline ASR.
 */
object WavChunker {
    const val DEFAULT_CHUNK_MS = 30_000

    data class PcmChunk(
        val pcm: ByteArray,
        val sampleRate: Int,
        val index: Int,
        val total: Int,
        val startMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PcmChunk) return false
            return sampleRate == other.sampleRate &&
                index == other.index &&
                total == other.total &&
                startMs == other.startMs &&
                pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var h = pcm.contentHashCode()
            h = 31 * h + sampleRate
            h = 31 * h + index
            h = 31 * h + total
            h = 31 * h + startMs.hashCode()
            return h
        }
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
        // Align to sample boundary
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
                    startMs = startMs
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

    /** Linear resample 16-bit mono PCM. */
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
            outBb.putShort(v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return out
    }
}
