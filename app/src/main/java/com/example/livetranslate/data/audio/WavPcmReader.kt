package com.example.livetranslate.data.audio

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PCM WAV reader for FFmpeg output (16-bit mono/stereo little-endian).
 * Locates the `data` chunk so non-canonical headers still work.
 */
object WavPcmReader {
    data class OpenWav(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Int,
        val file: File
    ) {
        fun openPcmStream(): InputStream {
            val fis = FileInputStream(file)
            if (fis.skip(dataOffset) != dataOffset) {
                fis.close()
                throw IllegalStateException("无法定位 WAV data 块")
            }
            return fis
        }

        val durationMs: Long
            get() {
                val bytesPerSec = sampleRate.toLong() * channels * (bitsPerSample / 8)
                if (bytesPerSec <= 0L) return 0L
                return dataSize * 1000L / bytesPerSec
            }
    }

    fun open(file: File): OpenWav {
        if (!file.isFile || file.length() < 44L) {
            throw IllegalArgumentException("无效 WAV：${file.name}")
        }
        FileInputStream(file).use { fis ->
            val riff = ByteArray(12)
            readFully(fis, riff)
            require(String(riff, 0, 4) == "RIFF") { "不是 RIFF/WAV" }
            require(String(riff, 8, 4) == "WAVE") { "不是 WAVE" }

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var pos = 12L

            while (true) {
                val hdr = ByteArray(8)
                val n = fis.read(hdr)
                if (n < 8) break
                pos += 8
                val id = String(hdr, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val sizeU = size.toLong() and 0xFFFFFFFFL

                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.coerceAtMost(64))
                        readFully(fis, fmt)
                        if (size > fmt.size) fis.skip(sizeU - fmt.size)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        if (audioFormat != 1) {
                            throw IllegalArgumentException("仅支持 PCM WAV（format=$audioFormat）")
                        }
                        pos += sizeU
                    }
                    "data" -> {
                        // done scanning
                        return OpenWav(
                            sampleRate = sampleRate.takeIf { it > 0 }
                                ?: throw IllegalArgumentException("WAV 缺少 fmt"),
                            channels = channels.coerceAtLeast(1),
                            bitsPerSample = bitsPerSample.takeIf { it == 8 || it == 16 }
                                ?: throw IllegalArgumentException("仅支持 8/16-bit PCM"),
                            dataOffset = pos,
                            dataSize = size,
                            file = file
                        )
                    }
                    else -> {
                        fis.skip(sizeU)
                        pos += sizeU
                    }
                }
                // chunks are word-aligned
                if (sizeU % 2L == 1L) {
                    fis.skip(1)
                    pos += 1
                }
            }
            throw IllegalArgumentException("WAV 无 data 块")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalArgumentException("WAV 截断")
            off += n
        }
    }
}
