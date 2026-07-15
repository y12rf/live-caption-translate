package com.example.livetranslate.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.io.File
import kotlin.math.max

/**
 * Continuous PCM capture.
 *
 * Mic: 16 kHz mono.
 * Internal (API 29+): keep MediaProjection alive with a tiny VirtualDisplay,
 * try device-native / 48k / 44.1k stereo then mono, downsample to 16 kHz mono for VAD + ASR.
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
        set(value) {
            // Android 14+: createVirtualDisplay may only run once per MediaProjection.
            // Drop the display when the token is cleared; keep it across pause/retry.
            if (value !== field) {
                releaseProjectionDisplay()
            }
            field = value
        }

    private var job: Job? = null
    private var activeVad: EnergyVad? = null

    /** Continuous session WAV (full lecture), independent of VAD utterances. */
    private val sessionRecorder = SessionAudioRecorder(appContext)

    /**
     * Path to a pre-converted session WAV (file-import mode).
     * When set, [finishSessionRecording] returns this instead of live recorder output.
     */
    @Volatile
    private var importedSessionWavPath: String? = null

    /** Keeps MediaProjection session active on Android 14+ / Pixel (audio-only path). */
    private var projectionDisplay: VirtualDisplay? = null
    private var projectionReader: ImageReader? = null

    fun clearError() {
        _captureError.value = null
    }

    /** Start (or restart) full-session WAV capture. Call when a new Idle→Recording session begins. */
    fun beginSessionRecording(startedAt: Long) {
        importedSessionWavPath = null
        sessionRecorder.begin(startedAt)
    }

    /**
     * File-import: copy converted 16k mono WAV into recordings/ as the session archive.
     * @return absolute path of the installed file
     */
    fun installImportedSessionWav(source: File, startedAt: Long): String {
        sessionRecorder.discard()
        val dir = File(appContext.filesDir, SessionAudioRecorder.RECORDINGS_DIR).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(startedAt))
        val dest = File(dir, "session_${stamp}_import.wav")
        source.copyTo(dest, overwrite = true)
        if (!dest.isFile || dest.length() < 44L) {
            throw IllegalStateException("导入会话 WAV 写入失败")
        }
        importedSessionWavPath = dest.absolutePath
        Log.i(TAG, "installed imported session wav → ${dest.absolutePath}")
        return dest.absolutePath
    }

    /** Finalize WAV; returns absolute path or null if empty / failed. */
    fun finishSessionRecording(): String? {
        val imported = importedSessionWavPath
        if (imported != null) {
            importedSessionWavPath = null
            val f = File(imported)
            return if (f.isFile && f.length() > 44L) imported else null
        }
        return sessionRecorder.finish()
    }

    fun discardSessionRecording() {
        importedSessionWavPath = null
        sessionRecorder.discard()
    }

    fun start() {
        if (running) return
        clearError()
        if (sourceType == AudioSourceType.File) {
            throw IllegalStateException("文件模式请使用 startFromFile，不要直接 start 采集")
        }
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
                // Keep VirtualDisplay for pause/resume on the same MediaProjection token.
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
        // Do not release VirtualDisplay / MediaProjection — resume reuses the same token.
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
        // MediaProjection is stopped by RecordingService; display released via mediaProjection=null.
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
        val bufferSize = (minBuf * 2).coerceAtLeast(frameSamples * 2 * 4)
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
     * Internal playback capture: try device-friendly formats, always feed VAD at 16 kHz mono.
     */
    @SuppressLint("MissingPermission")
    private fun loopInternal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("内部录音需要 Android 10+")
        }
        val projection = mediaProjection
            ?: throw IllegalStateException("MediaProjection is null")

        // Pixel / Android 14–15: keep projection session alive with a 2×2 VirtualDisplay.
        // Same token is valid for both VirtualDisplay + AudioPlaybackCapture.
        ensureProjectionDisplay(projection)

        val attempts = buildInternalFormatAttempts()
        Log.i(TAG, "internal capture attempts: ${attempts.map { "${it.sampleRate}/${it.channels}" }}")

        var lastError: Exception? = null
        for (fmt in attempts) {
            try {
                loopInternalWithFormat(projection, fmt)
                return
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "internal format failed ${fmt.sampleRate}/${fmt.channels}: ${e.message}",
                    e
                )
                lastError = e
                // Brief gap so audio HAL can release a half-open capture client
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                }
            }
        }
        val detail = lastError?.let { err ->
            buildString {
                append(err.message ?: err.javaClass.simpleName)
                err.cause?.message?.let { append(" | cause: ").append(it) }
            }
        }
        throw IllegalStateException(
            "内部录音初始化失败（已尝试设备原生采样率与 48k/44.1k/16k）。" +
                "请确认：①已授权录屏 ②前台服务含 mediaProjection ③目标 App 允许被录。" +
                (detail?.let { " 详情: $it" } ?: ""),
            lastError
        )
    }

    private fun buildInternalFormatAttempts(): List<CaptureFormat> {
        val nativeRate = deviceOutputSampleRate()
        val rates = linkedSetOf<Int>().apply {
            if (nativeRate > 0) add(nativeRate)
            add(48_000)
            add(44_100)
            add(16_000)
        }
        val out = ArrayList<CaptureFormat>(rates.size * 2)
        // Prefer stereo first (Pixel / Tensor mixer is usually stereo), then mono.
        for (rate in rates) {
            out += CaptureFormat(rate, AudioFormat.CHANNEL_IN_STEREO, 2)
        }
        for (rate in rates) {
            out += CaptureFormat(rate, AudioFormat.CHANNEL_IN_MONO, 1)
        }
        return out
    }

    private fun deviceOutputSampleRate(): Int {
        return try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "PROPERTY_OUTPUT_SAMPLE_RATE unavailable", e)
            0
        }
    }

    /**
     * Minimal VirtualDisplay so MediaProjection is considered an active capture session.
     * Frames are discarded (2×2 RGBA); we only need the session for AudioPlaybackCapture.
     */
    private fun ensureProjectionDisplay(projection: MediaProjection) {
        if (projectionDisplay != null) return
        try {
            val density = appContext.resources.displayMetrics.densityDpi
            val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
            // Drain frames so the producer does not stall (IO threads have no Looper).
            val mainHandler = Handler(Looper.getMainLooper())
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.close()
                } catch (_: Exception) {
                }
            }, mainHandler)
            val display = projection.createVirtualDisplay(
                "LiveTranslateAudio",
                2,
                2,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            projectionReader = reader
            projectionDisplay = display
            Log.i(TAG, "VirtualDisplay created for internal audio session")
        } catch (e: Exception) {
            // Some devices allow audio-only without VirtualDisplay; keep going.
            Log.w(TAG, "VirtualDisplay create failed (continue audio-only): ${e.message}", e)
            releaseProjectionDisplay()
        }
    }

    private fun releaseProjectionDisplay() {
        try {
            projectionDisplay?.release()
        } catch (_: Exception) {
        }
        projectionDisplay = null
        try {
            projectionReader?.close()
        } catch (_: Exception) {
        }
        projectionReader = null
    }

    @SuppressLint("MissingPermission")
    private fun loopInternalWithFormat(projection: MediaProjection, fmt: CaptureFormat) {
        // Only usages that the platform allows for playback capture (see AudioPlaybackCapture docs).
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(fmt.sampleRate)
            .setChannelMask(fmt.channelMask)
            .build()

        // frameSamplesCapture = total PCM samples (shorts) in one 20ms multi-channel frame
        val frameSamplesCapture = fmt.sampleRate * FRAME_MS / 1000 * fmt.channels
        val frameBytes = frameSamplesCapture * BYTES_PER_SAMPLE
        val minBuf = AudioRecord.getMinBufferSize(
            fmt.sampleRate,
            fmt.channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Playback-capture HAL may need a larger buffer than mic getMinBufferSize suggests.
        // Always express sizes in **bytes**. Fallback when getMinBufferSize is ERROR_*.
        val bytesPerSec = fmt.sampleRate * fmt.channels * BYTES_PER_SAMPLE
        val bufferSize = max(
            if (minBuf > 0) minBuf * 2 else bytesPerSec / 5,
            max(frameBytes * 4, bytesPerSec / 5)
        ).coerceAtLeast(4096)

        Log.i(
            TAG,
            "try internal AudioRecord rate=${fmt.sampleRate} ch=${fmt.channels} " +
                "minBuf=$minBuf bufferBytes=$bufferSize"
        )

        val builder = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Better attribution / policy routing on API 31+
            builder.setContext(appContext)
        }

        val recorder = try {
            builder.build()
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "内部录音被拒绝(SecurityException): ${e.message}",
                e
            )
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException(
                "AudioRecord 创建失败 ${fmt.sampleRate}Hz/${fmt.channels}ch: " +
                    (e.message ?: "UnsupportedOperationException"),
                e
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "采集参数无效 ${fmt.sampleRate}Hz/${fmt.channels}ch: ${e.message}",
                e
            )
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            val st = recorder.state
            releaseRecorder(recorder)
            throw IllegalStateException(
                "AudioRecord 未初始化 state=$st @ ${fmt.sampleRate}/${fmt.channels}"
            )
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
        // Full-session archive (silence included) for auto-save / share / export
        sessionRecorder.writeFrame(frame)
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
        private const val BYTES_PER_SAMPLE = 2

        /** @deprecated use [ASR_SAMPLE_RATE] */
        const val SAMPLE_RATE = ASR_SAMPLE_RATE
    }
}
