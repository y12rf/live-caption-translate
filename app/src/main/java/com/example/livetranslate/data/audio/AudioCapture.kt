package com.example.livetranslate.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuous PCM capture.
 *
 * Mic: 16 kHz mono.
 * Internal (API 29+): try 48 kHz / 44.1 kHz stereo (OEM-friendly), then downsample
 * to 16 kHz mono for VAD + ASR.
 */
class AudioCapture(
    private val scope: CoroutineScope,
    private val settings: () -> UserSettings,
    private val appContext: Context
) {
    private val _utterances = MutableSharedFlow<UtteranceAudio>(extraBufferCapacity = 16)
    val utterances: SharedFlow<UtteranceAudio> = _utterances.asSharedFlow()

    private val _captureError = MutableStateFlow<String?>(null)
    val captureError: StateFlow<String?> = _captureError.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var sourceType: AudioSourceType = AudioSourceType.Microphone

    @Volatile
    var mediaProjection: MediaProjection? = null

    private var job: Job? = null
    private var activeVad: EnergyVad? = null

    fun clearError() {
        _captureError.value = null
    }

    fun start() {
        if (running) return
        clearError()
        if (sourceType == AudioSourceType.Internal) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw IllegalStateException("内部录音需要 Android 10+")
            }
            if (mediaProjection == null) {
                throw IllegalStateException("内部录音未获得 MediaProjection 授权")
            }
        }
        running = true
        isRecording = true
        job = scope.launch(Dispatchers.IO) {
            try {
                // Give MediaProjection a brief moment on OEM devices (esp. Android 12–13)
                if (sourceType == AudioSourceType.Internal) {
                    delay(150)
                }
                if (sourceType == AudioSourceType.Internal) {
                    loopInternal()
                } else {
                    loopMicrophone()
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture loop crashed", e)
                _captureError.value = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
                isRecording = false
            }
        }
    }

    fun pause() {
        running = false
        isRecording = false
        job?.cancel()
        job = null
        activeVad?.reset()
        activeVad = null
    }

    fun stop(flush: Boolean = true) {
        running = false
        isRecording = false
        val vad = activeVad
        if (flush && vad != null) {
            try {
                vad.flushStop().emit?.let { emit ->
                    _utterances.tryEmit(
                        UtteranceAudio(emit.pcm, ASR_SAMPLE_RATE, emit.reason)
                    )
                }
            } catch (_: Exception) {
            }
        }
        job?.cancel()
        job = null
        activeVad?.reset()
        activeVad = null
    }

    @SuppressLint("MissingPermission")
    private fun loopMicrophone() {
        val frameSamples = ASR_SAMPLE_RATE * FRAME_MS / 1000
        val vad = createVad(frameSamples)
        activeVad = vad

        val minBuf = AudioRecord.getMinBufferSize(
            ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("AudioRecord buffer invalid: $minBuf")
        val bufferSize = (minBuf * 2).coerceAtLeast(frameSamples * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        try {
            ensureRecording(recorder)
            val frame = ShortArray(frameSamples)
            while (running && scope.isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read != frame.size) continue
                emitVad(vad, frame)
            }
        } finally {
            releaseRecorder(recorder)
            if (activeVad === vad) activeVad = null
        }
    }

    /**
     * Internal playback capture: try several device-friendly formats, always feed VAD at 16 kHz mono.
     */
    @SuppressLint("MissingPermission")
    private fun loopInternal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("内部录音需要 Android 10+")
        }
        val projection = mediaProjection
            ?: throw IllegalStateException("MediaProjection is null")

        val attempts = listOf(
            CaptureFormat(48_000, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureFormat(44_100, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureFormat(48_000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureFormat(44_100, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureFormat(16_000, AudioFormat.CHANNEL_IN_MONO, 1)
        )

        var lastError: Exception? = null
        for (fmt in attempts) {
            try {
                loopInternalWithFormat(projection, fmt)
                return
            } catch (e: Exception) {
                Log.w(TAG, "internal format failed ${fmt.sampleRate}/${fmt.channels}: ${e.message}")
                lastError = e
            }
        }
        throw IllegalStateException(
            "内部录音初始化失败（已尝试 48k/44.1k 立体声与单声道）。" +
                "请确认：①已授权录屏 ②通知栏前台服务在运行 ③目标 App 未禁止被录。" +
                (lastError?.message?.let { " 详情: $it" } ?: ""),
            lastError
        )
    }

    @SuppressLint("MissingPermission")
    private fun loopInternalWithFormat(projection: MediaProjection, fmt: CaptureFormat) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(fmt.sampleRate)
            .setChannelMask(fmt.channelMask)
            .build()

        val frameSamplesCapture = fmt.sampleRate * FRAME_MS / 1000 * fmt.channels
        val minBuf = AudioRecord.getMinBufferSize(
            fmt.sampleRate,
            fmt.channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw IllegalStateException("getMinBufferSize=${minBuf} for ${fmt.sampleRate}")
        }
        val bufferSize = (minBuf * 2).coerceAtLeast(frameSamplesCapture * 4)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("内部录音被拒绝(SecurityException)", e)
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("不支持该采集格式: ${fmt.sampleRate}/${fmt.channels}", e)
        }

        val asrFrameSamples = ASR_SAMPLE_RATE * FRAME_MS / 1000
        val vad = createVad(asrFrameSamples)
        activeVad = vad

        try {
            ensureRecording(recorder)
            Log.i(TAG, "Internal capture OK @ ${fmt.sampleRate}Hz ch=${fmt.channels}")
            val captureBuf = ShortArray(frameSamplesCapture)
            while (running && scope.isActive) {
                val read = recorder.read(captureBuf, 0, captureBuf.size)
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "read error $read")
                    continue
                }
                val monoHi = toMono(captureBuf, read, fmt.channels)
                val mono16k = resampleTo16k(monoHi, fmt.sampleRate)
                // Feed VAD in 20ms chunks at 16 kHz
                var offset = 0
                while (offset + asrFrameSamples <= mono16k.size) {
                    val frame = mono16k.copyOfRange(offset, offset + asrFrameSamples)
                    emitVad(vad, frame)
                    offset += asrFrameSamples
                }
            }
        } finally {
            releaseRecorder(recorder)
            if (activeVad === vad) activeVad = null
        }
    }

    private fun createVad(frameSamples: Int): EnergyVad {
        val s = settings()
        return EnergyVad(
            sampleRate = ASR_SAMPLE_RATE,
            frameSamples = frameSamples,
            energyThreshold = s.energyThreshold,
            silenceMs = s.silenceMs,
            maxUtteranceMs = s.maxUtteranceMs,
            minUtteranceMs = s.minUtteranceMs
        )
    }

    private fun emitVad(vad: EnergyVad, frame: ShortArray) {
        val result = vad.accept(frame)
        result.emit?.let { emit ->
            _utterances.tryEmit(
                UtteranceAudio(emit.pcm, ASR_SAMPLE_RATE, emit.reason)
            )
        }
    }

    private fun ensureRecording(recorder: AudioRecord) {
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord 未初始化 state=${recorder.state}")
        }
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val st = recorder.recordingState
            recorder.release()
            throw IllegalStateException("未能开始录音 recordingState=$st")
        }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        try {
            recorder.stop()
        } catch (_: Exception) {
        }
        try {
            recorder.release()
        } catch (_: Exception) {
        }
    }

    private fun toMono(buf: ShortArray, read: Int, channels: Int): ShortArray {
        if (channels == 1) {
            return if (read == buf.size) buf else buf.copyOf(read)
        }
        val frames = read / 2
        val mono = ShortArray(frames)
        var i = 0
        var j = 0
        while (j < frames) {
            val l = buf[i].toInt()
            val r = buf[i + 1].toInt()
            mono[j] = ((l + r) / 2).toShort()
            i += 2
            j++
        }
        return mono
    }

    /** Simple decimation / nearest-neighbor downsample to 16 kHz. */
    private fun resampleTo16k(input: ShortArray, fromRate: Int): ShortArray {
        if (fromRate == ASR_SAMPLE_RATE) return input
        if (input.isEmpty()) return input
        val ratio = fromRate.toDouble() / ASR_SAMPLE_RATE
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = (i * ratio).toInt().coerceIn(0, input.size - 1)
            out[i] = input[src]
        }
        return out
    }

    private data class CaptureFormat(
        val sampleRate: Int,
        val channelMask: Int,
        val channels: Int
    )

    companion object {
        private const val TAG = "AudioCapture"
        const val ASR_SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20

        /** @deprecated use [ASR_SAMPLE_RATE] */
        const val SAMPLE_RATE = ASR_SAMPLE_RATE
    }
}
