package com.example.livetranslate.domain

import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.domain.model.AsrStreamEvent
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
    val segments: List<TranscriptSegment> = emptyList()
)

/**
 * Serial pipeline: utterance queue → ASR stream → LLM stream → UI state.
 * One utterance at a time keeps bilingual text ordered and context window correct.
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
    private var sessionStartedAt: Long = 0L
    private val contextWindow = ArrayDeque<ContextTurn>()
    private var lastFailed: UtteranceAudio? = null
    private var processJob: Job? = null

    fun start() {
        val current = _state.value.phase
        if (current == SessionPhase.Recording || current == SessionPhase.Processing) return

        if (current == SessionPhase.Idle) {
            // New session
            sessionStartedAt = System.currentTimeMillis()
            contextWindow.clear()
            _state.value = LiveSessionUiState(phase = SessionPhase.Recording)
        } else {
            // Resume from Paused — keep cumulative text
            _state.update { it.copy(phase = SessionPhase.Recording, error = null) }
        }

        ensureWorker()
        if (collector?.isActive != true) {
            collector = scope.launch {
                audio.utterances.collect { utt ->
                    queue.send(utt)
                }
            }
        }
        audio.start()
    }

    fun pause() {
        audio.pause()
        _state.update {
            it.copy(
                phase = if (it.phase == SessionPhase.Processing) {
                    SessionPhase.Processing
                } else {
                    SessionPhase.Paused
                }
            )
        }
        // If not processing, mark paused; when processing ends, restore phase based on isRecording
        if (_state.value.phase != SessionPhase.Processing) {
            _state.update { it.copy(phase = SessionPhase.Paused) }
        }
    }

    fun stop() {
        audio.stop(flush = true)
        collector?.cancel()
        collector = null
        processJob?.cancel()
        processJob = null
        val snapshot = _state.value
        scope.launch {
            if (snapshot.segments.isNotEmpty()) {
                history.saveSession(
                    sessionStartedAt,
                    System.currentTimeMillis(),
                    snapshot.segments
                )
            }
            contextWindow.clear()
            lastFailed = null
            _state.value = LiveSessionUiState(phase = SessionPhase.Idle)
        }
    }

    fun retryLastFailed() {
        val u = lastFailed ?: return
        lastFailed = null
        _state.update { it.copy(error = null, canRetry = false) }
        scope.launch { processUtterance(u) }
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
                val retryable = e is RetryableException || attempt < 3
                if (!retryable || attempt >= 3) break
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
                apiKey = settings.asrApiKey,
                model = settings.asrModel,
                language = settings.inputLanguage
            )
        ).collect { ev ->
            when (ev) {
                is AsrStreamEvent.Delta -> {
                    // Delta carries merged display text
                    en = ev.text
                    _state.update { it.copy(partialEn = en) }
                }
                is AsrStreamEvent.Completed -> en = ev.fullText
                is AsrStreamEvent.Error -> {
                    if (ev.retryable) throw RetryableException(ev.throwable)
                    else throw ev.throwable
                }
            }
        }

        if (en.isBlank()) {
            // Skip empty ASR; return to recording phase
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
                apiKey = settings.llmApiKey,
                model = settings.llmModel,
                targetLanguage = settings.outputLanguage,
                sourceLanguage = settings.inputLanguage,
                systemPrompt = settings.renderLlmSystemPrompt()
            )
        ).collect { ev ->
            when (ev) {
                is LlmStreamEvent.Delta -> {
                    // Piece-only append
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

        val seg = TranscriptSegment(en, zh, utt.reason, incomplete = false)
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
