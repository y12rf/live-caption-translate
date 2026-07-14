package com.example.livetranslate.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Continuous [AudioRecord] capture at 16 kHz mono PCM 16-bit.
 *
 * Audio is sliced into fixed frames (~20 ms). Each frame is fed to [EnergyVad],
 * which emits complete utterances on silence or max-duration force cut.
 */
class AudioCapture(
    private val scope: CoroutineScope,
    private val settings: () -> UserSettings
) {
    private val _utterances = MutableSharedFlow<UtteranceAudio>(extraBufferCapacity = 16)
    val utterances: SharedFlow<UtteranceAudio> = _utterances.asSharedFlow()

    @Volatile
    private var running = false

    @Volatile
    var isRecording: Boolean = false
        private set

    private var job: Job? = null
    private var activeVad: EnergyVad? = null

    fun start() {
        if (running) return
        running = true
        isRecording = true
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun pause() {
        running = false
        isRecording = false
        job?.cancel()
        job = null
        activeVad?.reset()
        activeVad = null
        // Spec: do not flush on pause
    }

    fun stop(flush: Boolean = true) {
        running = false
        isRecording = false
        val vad = activeVad
        if (flush && vad != null) {
            vad.flushStop().emit?.let { emit ->
                _utterances.tryEmit(
                    UtteranceAudio(emit.pcm, SAMPLE_RATE, emit.reason)
                )
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
            running = false
            isRecording = false
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(frameSamples * 4)
        )

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return
            }
            recorder.startRecording()
            val frame = ShortArray(frameSamples)
            while (running && scope.isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read != frame.size) continue
                val result = vad.accept(frame)
                result.emit?.let { emit ->
                    // Complete utterance ready for serial ASR → LLM pipeline.
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
            recorder.release()
            if (activeVad === vad) activeVad = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
