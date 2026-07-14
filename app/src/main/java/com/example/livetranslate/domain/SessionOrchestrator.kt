package com.example.livetranslate.domain

import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.domain.model.AsrStreamEvent
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.ContextTurn
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.LlmStreamEvent
import com.example.livetranslate.domain.model.SessionPhase
import com.example.livetranslate.domain.model.TranscriptSegment
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveSessionUiState(
    val phase: SessionPhase = SessionPhase.Idle,
    val cumulativeEn: String = "",
    val cumulativeZh: String = "",
    val partialEn: String = "",
    val partialZh: String = "",
    val lastCutReason: CutReason? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val segments: List<TranscriptSegment> = emptyList(),
    /** Wall-clock session start; 0 if idle. */
    val sessionStartedAt: Long = 0L,
    /** Accumulated recording time in ms (pauses excluded). */
    val recordedElapsedMs: Long = 0L,
    val audioSource: AudioSourceType = AudioSourceType.Microphone,
    val overlayEnabled: Boolean = false
)

/**
 * Serial pipeline: utterance queue → ASR stream → LLM stream → UI state.
 */
class SessionOrchestrator(
    private val scope: CoroutineScope,
    private val audio: AudioCapture,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository
) {
    private val _state = MutableStateFlow(LiveSessionUiState())
    val state: StateFlow<LiveSessionUiState> = _state.asStateFlow()

    private val queue = Channel<UtteranceAudio>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var collector: Job? = null
    private var timerJob: Job? = null
    private var sessionStartedAt: Long = 0L
    private var recordedElapsedMs: Long = 0L
    private var segmentClockStart: Long = 0L
    private val contextWindow = ArrayDeque<ContextTurn>()
    private var lastFailed: UtteranceAudio? = null
    private var processJob: Job? = null

    fun setAudioSource(type: AudioSourceType) {
        if (_state.value.phase != SessionPhase.Idle) return
        audio.sourceType = type
        _state.update { it.copy(audioSource = type) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
    }

    /** Surface background capture failures (internal audio) to UI without crashing. */
    fun reportCaptureError(message: String) {
        audio.pause()
        stopTimer()
        _state.update {
            it.copy(
                phase = SessionPhase.Paused,
                error = message,
                canRetry = false
            )
        }
    }

    fun start() {
        val current = _state.value.phase
        if (current == SessionPhase.Recording || current == SessionPhase.Processing) return

        if (current == SessionPhase.Idle) {
            sessionStartedAt = System.currentTimeMillis()
            recordedElapsedMs = 0L
            contextWindow.clear()
            audio.sourceType = _state.value.audioSource
            _state.value = LiveSessionUiState(
                phase = SessionPhase.Recording,
                sessionStartedAt = sessionStartedAt,
                recordedElapsedMs = 0L,
                audioSource = audio.sourceType,
                overlayEnabled = _state.value.overlayEnabled
            )
        } else {
            _state.update {
                it.copy(
                    phase = SessionPhase.Recording,
                    error = null,
                    sessionStartedAt = sessionStartedAt
                )
            }
        }

        segmentClockStart = System.currentTimeMillis()
        ensureWorker()
        ensureTimer()
        if (collector?.isActive != true) {
            collector = scope.launch {
                audio.utterances.collect { utt ->
                    queue.send(utt)
                }
            }
        }
        try {
            audio.start()
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    phase = SessionPhase.Idle,
                    error = e.message ?: "Failed to start audio"
                )
            }
            stopTimer()
        }
    }

    fun pause() {
        accumulateElapsed()
        audio.pause()
        stopTimer()
        _state.update {
            it.copy(
                phase = SessionPhase.Paused,
                recordedElapsedMs = recordedElapsedMs
            )
        }
    }

    fun stop() {
        accumulateElapsed()
        audio.stop(flush = true)
        collector?.cancel()
        collector = null
        processJob?.cancel()
        processJob = null
        stopTimer()
        val snapshot = _state.value
        val started = sessionStartedAt
        scope.launch {
            if (snapshot.segments.isNotEmpty()) {
                history.saveSession(
                    started,
                    System.currentTimeMillis(),
                    snapshot.segments
                )
            }
            contextWindow.clear()
            lastFailed = null
            sessionStartedAt = 0L
            recordedElapsedMs = 0L
            _state.value = LiveSessionUiState(
                phase = SessionPhase.Idle,
                audioSource = audio.sourceType,
                overlayEnabled = snapshot.overlayEnabled
            )
        }
    }

    fun retryLastFailed() {
        val u = lastFailed ?: return
        lastFailed = null
        _state.update { it.copy(error = null, canRetry = false) }
        scope.launch { processUtterance(u) }
    }

    fun exportMarkdown(): String? {
        val s = _state.value
        if (s.segments.isEmpty()) return null
        return HistoryExport.formatMarkdownFromLive(
            s.sessionStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            s.segments
        )
    }

    private fun accumulateElapsed() {
        if (segmentClockStart > 0L &&
            (_state.value.phase == SessionPhase.Recording ||
                _state.value.phase == SessionPhase.Processing)
        ) {
            recordedElapsedMs += (System.currentTimeMillis() - segmentClockStart)
            segmentClockStart = 0L
        }
    }

    private fun ensureTimer() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive) {
                delay(500)
                val phase = _state.value.phase
                if (phase == SessionPhase.Recording || phase == SessionPhase.Processing) {
                    val base = recordedElapsedMs
                    val extra = if (segmentClockStart > 0) {
                        System.currentTimeMillis() - segmentClockStart
                    } else 0L
                    _state.update {
                        it.copy(recordedElapsedMs = base + extra)
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (utt in queue) {
                processJob = launch { processUtterance(utt) }
                processJob?.join()
            }
        }
    }

    private suspend fun processUtterance(utt: UtteranceAudio) {
        val settings = settingsRepo.settings.first()
        _state.update {
            it.copy(
                phase = SessionPhase.Processing,
                partialEn = "",
                partialZh = "",
                lastCutReason = utt.reason,
                error = null,
                canRetry = false
            )
        }

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            try {
                runPipeline(utt, settings.contextWindowSize)
                lastFailed = null
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt >= 3) break
                delay(500L * attempt * attempt)
            }
        }
        lastFailed = utt
        _state.update {
            it.copy(
                error = lastError?.message ?: "Processing failed",
                canRetry = true,
                phase = if (audio.isRecording) SessionPhase.Recording else SessionPhase.Paused
            )
        }
    }

    private suspend fun runPipeline(utt: UtteranceAudio, windowSize: Int) {
        val settings = settingsRepo.settings.first()
        if (settings.asrApiKey.isBlank()) {
            throw Exception("ASR API Key is empty. Configure it in Settings.")
        }
        if (settings.llmApiKey.isBlank()) {
            throw Exception("LLM API Key is empty. Configure it in Settings.")
        }

        var en = ""
        asr.transcribeStream(
            utt,
            AsrConfig(
                baseUrl = settings.normalizedAsrBaseUrl(),
                apiKey = settings.asrApiKey.trim(),
                model = settings.asrModel.trim(),
                language = settings.inputLanguage.trim(),
                apiStyle = settings.asrApiStyleEnum(),
                authStyle = settings.asrAuthStyleEnum()
            )
        ).collect { ev ->
            when (ev) {
                is AsrStreamEvent.Delta -> {
                    en = AsrOutputSanitizer.clean(ev.text)
                    _state.update { it.copy(partialEn = en) }
                }
                is AsrStreamEvent.Completed ->
                    en = AsrOutputSanitizer.clean(ev.fullText)
                is AsrStreamEvent.Error -> {
                    if (ev.retryable) throw RetryableException(ev.throwable)
                    else throw ev.throwable
                }
            }
        }
        en = AsrOutputSanitizer.clean(en)

        if (en.isBlank()) {
            _state.update {
                it.copy(
                    partialEn = "",
                    phase = if (audio.isRecording) SessionPhase.Recording else SessionPhase.Paused
                )
            }
            return
        }

        var zh = ""
        val ctx = contextWindow.toList()
        llm.translateStream(
            en,
            ctx,
            LlmConfig(
                baseUrl = settings.normalizedLlmBaseUrl(),
                apiKey = settings.llmApiKey.trim(),
                model = settings.llmModel.trim(),
                targetLanguage = settings.outputLanguage.trim(),
                sourceLanguage = settings.inputLanguage.trim(),
                systemPrompt = settings.renderLlmSystemPrompt(),
                authStyle = settings.llmAuthStyleEnum()
            )
        ).collect { ev ->
            when (ev) {
                is LlmStreamEvent.Delta -> {
                    zh += ev.text
                    _state.update { it.copy(partialZh = zh) }
                }
                is LlmStreamEvent.Completed -> {
                    if (ev.fullText.isNotEmpty()) zh = ev.fullText
                }
                is LlmStreamEvent.Error -> {
                    if (ev.retryable) throw RetryableException(ev.throwable)
                    else throw ev.throwable
                }
            }
        }

        val now = System.currentTimeMillis()
        val offset = if (sessionStartedAt > 0) now - sessionStartedAt else recordedElapsedMs
        val seg = TranscriptSegment(
            source = en,
            translation = zh,
            cutReason = utt.reason,
            incomplete = false,
            timestampMs = now,
            offsetMs = offset
        )
        contextWindow.addLast(ContextTurn(en, zh))
        while (contextWindow.size > windowSize.coerceAtLeast(0)) {
            contextWindow.removeFirst()
        }

        _state.update {
            it.copy(
                cumulativeEn = appendBlock(it.cumulativeEn, en),
                cumulativeZh = appendBlock(it.cumulativeZh, zh),
                partialEn = "",
                partialZh = "",
                segments = it.segments + seg,
                phase = when {
                    audio.isRecording -> SessionPhase.Recording
                    else -> SessionPhase.Paused
                },
                error = null,
                canRetry = false
            )
        }
    }

    private fun appendBlock(prev: String, next: String): String =
        if (prev.isEmpty()) next else prev + "\n" + next

    private class RetryableException(cause: Throwable) : Exception(cause.message, cause)
}
