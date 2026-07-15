package com.example.livetranslate.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM 16-bit little-endian mono into a WAV container
 * so OpenAI-compatible multipart ASR endpoints accept the upload.
 */
object WavEncoder {
    const val HEADER_SIZE = 44

    fun buildHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }

    fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = buildHeader(pcm.size, sampleRate)
        return header + pcm
    }
}
