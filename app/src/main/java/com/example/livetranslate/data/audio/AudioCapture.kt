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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuous PCM capture at 16 kHz mono 16-bit.
 *
 * - [AudioSourceType.Microphone]: VOICE_RECOGNITION mic
 * - [AudioSourceType.Internal]: AudioPlaybackCapture (API 29+), needs [MediaProjection]
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

    /** Required for [AudioSourceType.Internal]. Set before [start]. */
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
                loop()
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
                        UtteranceAudio(emit.pcm, SAMPLE_RATE, emit.reason)
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
    private fun loop() {
        val s = settings()
        val frameMs = 20
        val frameSamples = SAMPLE_RATE * frameMs / 1000
        val vad = EnergyVad(
            sampleRate = SAMPLE_RATE,
            frameSamples = frameSamples,
            energyThreshold = s.energyThreshold,
            silenceMs = s.silenceMs,
            maxUtteranceMs = s.maxUtteranceMs,
            minUtteranceMs = s.minUtteranceMs
        )
        activeVad = vad

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord buffer size invalid: $minBuf")
        }

        val bufferSize = (minBuf * 2).coerceAtLeast(frameSamples * 4)
        val recorder = createRecorder(bufferSize)

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "AudioRecord 初始化失败 (state=${recorder.state})，内部录音可能不被系统允许"
                )
            }
            recorder.startRecording()
            val recState = recorder.recordingState
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord 未能进入录音状态: $recState")
            }
            val frame = ShortArray(frameSamples)
            while (running && scope.isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error: $read")
                    continue
                }
                if (read != frame.size) continue
                val result = vad.accept(frame)
                result.emit?.let { emit ->
                    _utterances.tryEmit(
                        UtteranceAudio(emit.pcm, SAMPLE_RATE, emit.reason)
                    )
                }
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            if (activeVad === vad) activeVad = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(bufferSize: Int): AudioRecord {
        return if (sourceType == AudioSourceType.Internal &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val projection = mediaProjection
                ?: throw IllegalStateException("MediaProjection is null")
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (e: SecurityException) {
                throw IllegalStateException(
                    "内部录音被系统拒绝（SecurityException）。请确认已授权录屏且目标 App 未禁止被录。",
                    e
                )
            } catch (e: UnsupportedOperationException) {
                throw IllegalStateException(
                    "此设备不支持 AudioPlaybackCapture: ${e.message}",
                    e
                )
            }
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
    }
}
