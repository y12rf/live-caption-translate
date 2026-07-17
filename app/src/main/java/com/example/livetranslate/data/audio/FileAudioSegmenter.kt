package com.example.livetranslate.data.audio

import android.content.Context
import com.example.livetranslate.data.settings.SileroVadMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline VAD segmentation over a 16 kHz (or resampled) PCM WAV.
 * Emits [UtteranceAudio] with [UtteranceAudio.offsetMs] at the start of each cut.
 *
 * Production path uses Silero ([SileroSpeechClassifier]); unit tests may inject
 * [EnergySpeechClassifier] via [classifierFactory].
 */
class FileAudioSegmenter(
    private val appContext: Context? = null,
    private val frameSamples: Int = SileroSpeechClassifier.FRAME_SAMPLES,
    private val targetSampleRate: Int = AudioCapture.ASR_SAMPLE_RATE,
    /**
     * Optional classifier factory for tests. When null, builds Silero from [appContext].
     */
    private val classifierFactory: ((UserSettings) -> SpeechClassifier)? = null
) {

    /**
     * @param onProgress progress 0..1 over PCM bytes, elapsedMs into file
     */
    fun segment(
        wavFile: File,
        settings: UserSettings,
        onProgress: (progress: Float, elapsedMs: Long) -> Unit = { _, _ -> }
    ): Flow<UtteranceAudio> = flow {
        require(frameSamples > 0)
        val open = WavPcmReader.open(wavFile)
        val classifier = classifierFactory?.invoke(settings)
            ?: run {
                val ctx = appContext
                    ?: throw IllegalStateException("FileAudioSegmenter needs Context for Silero VAD")
                SileroSpeechClassifier(
                    context = ctx,
                    mode = SileroVadMode.fromStorage(settings.sileroVadMode),
                    silenceDurationMs = settings.silenceMs
                )
            }

        // Silero applies silenceDuration hangover; packer cuts on first non-speech after that.
        // Test injectors (EnergySpeechClassifier) need postSpeechQuietMs = settings.silenceMs.
        val postQuietMs = if (classifierFactory != null) settings.silenceMs else 0
        val vad = EnergyVad(
            sampleRate = targetSampleRate,
            frameSamples = frameSamples,
            classifier = classifier,
            postSpeechQuietMs = postQuietMs,
            maxUtteranceMs = settings.maxUtteranceMs,
            minUtteranceMs = settings.minUtteranceMs
        )

        try {
            open.openPcmStream().use { raw ->
                val totalBytes = open.dataSize.coerceAtLeast(1)
                var bytesRead = 0
                var sampleIndex = 0L // at target rate

                // Read source frames aligned to VAD frame duration at source rate
                val frameMs = frameSamples * 1000.0 / targetSampleRate
                val sourceFrameSamples = (open.sampleRate * frameMs / 1000.0).toInt().coerceAtLeast(1)
                val sourceFrameBytes = sourceFrameSamples * open.channels * (open.bitsPerSample / 8)
                val buf = ByteArray(sourceFrameBytes.coerceAtLeast(4))

                while (true) {
                    val n = readFullyOrShort(raw, buf, sourceFrameBytes)
                    if (n <= 0) break
                    bytesRead += n
                    val monoSrc = decodeFrameToMono16(buf, n, open)
                    val mono16k = resampleTo16k(monoSrc, open.sampleRate, targetSampleRate)
                    // Pad/truncate to exact frame length for EnergyVad
                    val frame = normalizeFrame(mono16k, frameSamples)

                    val result = vad.accept(frame)
                    sampleIndex += frameSamples
                    val endMs = sampleIndex * 1000L / targetSampleRate
                    result.emit?.let { emitUtt ->
                        val durMs = emitUtt.pcm.size / 2 * 1000L / targetSampleRate
                        val startMs = (endMs - durMs).coerceAtLeast(0L)
                        emit(
                            UtteranceAudio(
                                pcm = emitUtt.pcm,
                                sampleRate = targetSampleRate,
                                reason = emitUtt.reason,
                                offsetMs = startMs
                            )
                        )
                    }
                    onProgress(
                        (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f),
                        endMs
                    )
                }

                // Flush trailing speech
                val flushEndMs = sampleIndex * 1000L / targetSampleRate
                vad.flushStop().emit?.let { emitUtt ->
                    val durMs = emitUtt.pcm.size / 2 * 1000L / targetSampleRate
                    emit(
                        UtteranceAudio(
                            pcm = emitUtt.pcm,
                            sampleRate = targetSampleRate,
                            reason = emitUtt.reason,
                            offsetMs = (flushEndMs - durMs).coerceAtLeast(0L)
                        )
                    )
                }
                onProgress(1f, flushEndMs)
            }
        } finally {
            vad.close()
        }
    }

    private fun normalizeFrame(samples: ShortArray, frameSamples: Int): ShortArray {
        if (samples.size == frameSamples) return samples
        val out = ShortArray(frameSamples)
        val copy = minOf(samples.size, frameSamples)
        System.arraycopy(samples, 0, out, 0, copy)
        return out
    }

    private fun decodeFrameToMono16(buf: ByteArray, length: Int, open: WavPcmReader.OpenWav): ShortArray {
        val bytesPerSample = open.bitsPerSample / 8
        val frameCount = length / (bytesPerSample * open.channels)
        if (frameCount <= 0) return ShortArray(0)
        val mono = ShortArray(frameCount)
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var acc = 0
            for (c in 0 until open.channels) {
                val s = when (open.bitsPerSample) {
                    16 -> bb.short.toInt()
                    8 -> (bb.get().toInt() and 0xFF) - 128 shl 8
                    else -> 0
                }
                acc += s
            }
            mono[i] = (acc / open.channels).toShort()
        }
        return mono
    }

    private fun resampleTo16k(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = (i * ratio).toInt().coerceIn(0, input.size - 1)
            out[i] = input[src]
        }
        return out
    }

    private fun readFullyOrShort(input: InputStream, buf: ByteArray, want: Int): Int {
        var off = 0
        while (off < want) {
            val n = input.read(buf, off, want - off)
            if (n < 0) return if (off == 0) -1 else off
            off += n
        }
        return off
    }
}
