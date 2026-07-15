package com.example.livetranslate.domain

import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    val overlayEnabled: Boolean = false,
    /** LLM title after ≥10 turns; null until generated. */
    val sessionTitle: String? = null
)

/**
 * Pipelined session:
 * utterance queue → ASR (serial) → translate queue → LLM (serial).
 *
 * ASR of utterance N+1 can run while LLM still translates N, so the overlay
 * stays closer to live audio instead of waiting on full ASR+LLM per chunk.
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

    private var utteranceQueue = Channel<UtteranceAudio>(Channel.UNLIMITED)
    private var translateQueue = Channel<PendingTranslate>(Channel.UNLIMITED)
    private var asrWorker: Job? = null
    private var llmWorker: Job? = null
    private var collector: Job? = null
    private var timerJob: Job? = null
    private var sessionStartedAt: Long = 0L
    private var recordedElapsedMs: Long = 0L
    private var segmentClockStart: Long = 0L
    private val contextWindow = ArrayDeque<ContextTurn>()
    private var lastFailed: UtteranceAudio? = null

    /**
     * Per-session translation cache (latest 50). Cleared on session start/stop.
     * Only touched from the serial LLM worker so emit order stays FIFO.
     */
    private val translationCache = TranslationCache(TranslationCache.DEFAULT_MAX_SIZE)

    private val asrInFlight = AtomicInteger(0)
    private val llmInFlight = AtomicInteger(0)
    private val translatePending = AtomicInteger(0)

    /** Guards concurrent stop() from UI + notification so history is saved at most once. */
    private val stopOnce = AtomicBoolean(false)

    /** LLM session title (filled once at 10 turns). */
    @Volatile
    private var sessionTitle: String? = null
    private val titleRequested = AtomicBoolean(false)
    private var titleJob: Job? = null

    fun setAudioSource(type: AudioSourceType) {
        if (_state.value.phase != SessionPhase.Idle) return
        audio.sourceType = type
        _state.update { it.copy(audioSource = type) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
    }

    /** Clear in-memory translation cache (settings / user action). */
    fun clearTranslationCache() {
        translationCache.clear()
        android.util.Log.i(TAG, "translation cache cleared")
    }

    fun translationCacheSize(): Int = translationCache.size

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
        // A previous stop() may still be finalizing; ignore start until Idle is restored.
        if (stopOnce.get() && current != SessionPhase.Paused) return

        if (current == SessionPhase.Idle) {
            stopOnce.set(false)
            sessionStartedAt = System.currentTimeMillis()
            recordedElapsedMs = 0L
            contextWindow.clear()
            translationCache.clear()
            resetTitleState()
            audio.sourceType = _state.value.audioSource
            // Auto-save continuous WAV for the whole session
            try {
                audio.beginSessionRecording(sessionStartedAt)
            } catch (e: Exception) {
                // Non-fatal: translation still works without archive
                android.util.Log.w("SessionOrchestrator", "beginSessionRecording failed", e)
            }
            _state.value = LiveSessionUiState(
                phase = SessionPhase.Recording,
                sessionStartedAt = sessionStartedAt,
                recordedElapsedMs = 0L,
                audioSource = audio.sourceType,
                overlayEnabled = _state.value.overlayEnabled
            )
        } else {
            // Resume from Paused — keep stopOnce false so a later stop works.
            stopOnce.set(false)
            _state.update {
                it.copy(
                    phase = SessionPhase.Recording,
                    error = null,
                    sessionStartedAt = sessionStartedAt
                )
            }
        }

        segmentClockStart = System.currentTimeMillis()
        ensureWorkers()
        ensureTimer()
        if (collector?.isActive != true) {
            collector = scope.launch {
                audio.utterances.collect { utt ->
                    // Stamp recording-relative offset at cut time (pauses excluded).
                    utteranceQueue.send(utt.copy(offsetMs = currentRecordedElapsedMs()))
                }
            }
        }
        try {
            audio.start()
        } catch (e: Exception) {
            if (current == SessionPhase.Idle) {
                // New session never started capture — drop empty WAV
                audio.discardSessionRecording()
            }
            _state.update {
                it.copy(
                    phase = SessionPhase.Idle,
                    error = e.message ?: "Failed to start audio"
                )
            }
            stopTimer()
            stopOnce.set(false)
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
        // Idempotent: UI + FGS notification / double-tap must save history at most once.
        if (!stopOnce.compareAndSet(false, true)) return
        if (_state.value.phase == SessionPhase.Idle) {
            stopOnce.set(false)
            return
        }

        accumulateElapsed()
        audio.stop(flush = true)
        collector?.cancel()
        collector = null
        asrWorker?.cancel()
        asrWorker = null
        llmWorker?.cancel()
        llmWorker = null
        // Drop queued work; recreate channels for next session.
        utteranceQueue.close()
        translateQueue.close()
        utteranceQueue = Channel(Channel.UNLIMITED)
        translateQueue = Channel(Channel.UNLIMITED)
        asrInFlight.set(0)
        llmInFlight.set(0)
        translatePending.set(0)
        stopTimer()
        val snapshot = _state.value
        val started = sessionStartedAt
        val audioPath = try {
            audio.finishSessionRecording()
        } catch (e: Exception) {
            android.util.Log.w("SessionOrchestrator", "finishSessionRecording failed", e)
            null
        }
        // Keep transcript on screen until save finishes (export still works); gate blocks re-entry.
        scope.launch {
            try {
                // Wait briefly if title LLM is still running (≥10 turns)
                withTimeoutOrNull(TITLE_WAIT_MS) {
                    titleJob?.join()
                }
                val title = sessionTitle?.takeIf { it.isNotBlank() }
                    ?: snapshot.sessionTitle?.takeIf { it.isNotBlank() }
                // Persist if we have transcript and/or a non-empty session WAV
                if (snapshot.segments.isNotEmpty() || audioPath != null) {
                    try {
                        history.saveSession(
                            startedAt = started,
                            endedAt = System.currentTimeMillis(),
                            segments = snapshot.segments,
                            audioPath = audioPath,
                            title = title
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("SessionOrchestrator", "saveSession failed", e)
                    }
                } else {
                    audio.discardSessionRecording()
                }
            } finally {
                contextWindow.clear()
                translationCache.clear()
                resetTitleState()
                lastFailed = null
                sessionStartedAt = 0L
                recordedElapsedMs = 0L
                _state.value = LiveSessionUiState(
                    phase = SessionPhase.Idle,
                    audioSource = audio.sourceType,
                    overlayEnabled = snapshot.overlayEnabled
                )
                // Allow the next session to stop again.
                stopOnce.set(false)
            }
        }
    }

    fun retryLastFailed() {
        val u = lastFailed ?: return
        lastFailed = null
        _state.update { it.copy(error = null, canRetry = false) }
        ensureWorkers()
        scope.launch {
            utteranceQueue.send(u)
        }
    }

    fun exportMarkdown(): String? {
        val s = _state.value
        if (s.segments.isEmpty()) return null
        return HistoryExport.formatMarkdownFromLive(
            s.sessionStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            s.segments
        )
    }

    fun exportSrt(mode: ExportTextMode = ExportTextMode.Both): String? {
        val s = _state.value
        if (s.segments.isEmpty()) return null
        val srt = HistoryExport.formatSrtFromLive(
            segments = s.segments,
            mode = mode,
            sessionDurationMs = s.recordedElapsedMs
        )
        return srt.ifBlank { null }
    }

    fun exportPlain(mode: ExportTextMode): String? {
        val s = _state.value
        if (s.segments.isEmpty()) return null
        val text = s.segments.mapNotNull { seg ->
            when (mode) {
                ExportTextMode.TranslationOnly ->
                    seg.translation.trim().ifEmpty { null }
                ExportTextMode.SourceOnly ->
                    seg.source.trim().ifEmpty { null }
                ExportTextMode.Both -> {
                    val zh = seg.translation.trim()
                    val en = seg.source.trim()
                    when {
                        zh.isNotEmpty() && en.isNotEmpty() -> "$zh\n$en"
                        zh.isNotEmpty() -> zh
                        en.isNotEmpty() -> en
                        else -> null
                    }
                }
            }
        }.joinToString("\n\n")
        return text.ifBlank { null }
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

    /** Continuous recording clock: excludes paused time. */
    private fun currentRecordedElapsedMs(): Long {
        val base = recordedElapsedMs
        val phase = _state.value.phase
        val extra = if (
            segmentClockStart > 0L &&
            (phase == SessionPhase.Recording || phase == SessionPhase.Processing)
        ) {
            System.currentTimeMillis() - segmentClockStart
        } else {
            0L
        }
        return (base + extra).coerceAtLeast(0L)
    }

    private fun ensureTimer() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive) {
                delay(500)
                val phase = _state.value.phase
                if (phase == SessionPhase.Recording || phase == SessionPhase.Processing) {
                    _state.update {
                        it.copy(recordedElapsedMs = currentRecordedElapsedMs())
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun ensureWorkers() {
        if (llmWorker?.isActive != true) {
            llmWorker = scope.launch {
                for (job in translateQueue) {
                    translatePending.decrementAndGet()
                    processTranslateWithRetry(job)
                }
            }
        }
        if (asrWorker?.isActive != true) {
            asrWorker = scope.launch {
                for (utt in utteranceQueue) {
                    processAsrWithRetry(utt)
                }
            }
        }
    }

    private fun pipelineBusy(): Boolean =
        asrInFlight.get() > 0 || llmInFlight.get() > 0 || translatePending.get() > 0

    private fun refreshPhase() {
        val phase = when {
            pipelineBusy() -> SessionPhase.Processing
            audio.isRecording -> SessionPhase.Recording
            _state.value.phase == SessionPhase.Idle -> SessionPhase.Idle
            else -> SessionPhase.Paused
        }
        _state.update { it.copy(phase = phase) }
    }

    private suspend fun processAsrWithRetry(utt: UtteranceAudio) {
        asrInFlight.incrementAndGet()
        _state.update {
            it.copy(
                phase = SessionPhase.Processing,
                partialEn = "",
                // Keep partialZh if LLM is still streaming the previous sentence.
                lastCutReason = utt.reason,
                error = null,
                canRetry = false
            )
        }
        var attempt = 0
        var lastError: Exception? = null
        try {
            while (attempt < 3) {
                try {
                    val settings = settingsRepo.settings.first()
                    val en = runAsr(utt, settings)
                    if (en.isBlank()) {
                        _state.update { cur ->
                            cur.copy(partialEn = "")
                        }
                        return
                    }
                    // Keep EN visible while translation runs (or next ASR overwrites it).
                    _state.update { it.copy(partialEn = en) }
                    translatePending.incrementAndGet()
                    translateQueue.send(PendingTranslate(utt, en, settings.contextWindowSize))
                    lastFailed = null
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RetryableException) {
                    lastError = e
                    attempt++
                    if (attempt >= 3) break
                    delay(500L * attempt * attempt)
                } catch (e: Exception) {
                    // Non-retryable (auth, bad request, empty key, etc.) — fail once.
                    lastError = e
                    break
                }
            }
            lastFailed = utt
            _state.update {
                it.copy(
                    error = lastError?.message ?: "ASR failed",
                    canRetry = true
                )
            }
        } finally {
            asrInFlight.decrementAndGet()
            refreshPhase()
        }
    }

    private suspend fun processTranslateWithRetry(job: PendingTranslate) {
        llmInFlight.incrementAndGet()
        _state.update {
            it.copy(
                phase = SessionPhase.Processing,
                partialZh = "",
                error = null,
                canRetry = false
            )
        }
        var attempt = 0
        var lastError: Exception? = null
        try {
            while (attempt < 3) {
                try {
                    val settings = settingsRepo.settings.first()
                    runTranslate(job, settings)
                    lastFailed = null
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RetryableException) {
                    lastError = e
                    attempt++
                    if (attempt >= 3) break
                    delay(500L * attempt * attempt)
                } catch (e: Exception) {
                    // Non-retryable — fail once.
                    lastError = e
                    break
                }
            }
            lastFailed = job.utt
            _state.update {
                it.copy(
                    error = lastError?.message ?: "Translation failed",
                    canRetry = true
                )
            }
        } finally {
            llmInFlight.decrementAndGet()
            refreshPhase()
        }
    }

    private suspend fun runAsr(utt: UtteranceAudio, settings: UserSettings): String {
        if (settings.asrApiKey.isBlank()) {
            throw Exception("ASR API Key is empty. Configure it in Settings.")
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
                authStyle = settings.asrAuthStyleEnum(),
                fullUrl = settings.asrFullUrl
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
        return AsrOutputSanitizer.clean(en)
    }

    private suspend fun runTranslate(job: PendingTranslate, settings: UserSettings) {
        val en = job.en
        val sourceLang = settings.inputLanguage.trim()
        val targetLang = settings.outputLanguage.trim()
        val model = settings.llmModel.trim()
        val cacheKey = TranslationCache.Key.of(
            sourceText = en,
            sourceLang = sourceLang,
            targetLang = targetLang,
            model = model
        )

        // Cache hit: still run on the serial LLM worker so segment order is unchanged.
        val cachedZh = cacheKey?.let { translationCache.get(it) }
        if (cachedZh != null) {
            android.util.Log.d(TAG, "translation cache hit lang=$sourceLang→$targetLang")
            _state.update { it.copy(partialZh = cachedZh) }
            commitTranslation(job, en, cachedZh)
            return
        }

        if (settings.llmApiKey.isBlank()) {
            throw Exception("LLM API Key is empty. Configure it in Settings.")
        }

        var zh = ""
        val ctx = contextWindow.toList()
        llm.translateStream(
            en,
            ctx,
            LlmConfig(
                baseUrl = settings.normalizedLlmBaseUrl(),
                apiKey = settings.llmApiKey.trim(),
                model = model,
                targetLanguage = targetLang,
                sourceLanguage = sourceLang,
                systemPrompt = settings.renderLlmSystemPrompt(),
                authStyle = settings.llmAuthStyleEnum(),
                fullUrl = settings.llmFullUrl,
                thinking = settings.llmThinkingMode()
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

        // Store only successful non-blank translations under language-aware key
        if (cacheKey != null && zh.isNotBlank()) {
            translationCache.put(cacheKey, zh)
        }
        commitTranslation(job, en, zh)
    }

    /**
     * Append segment + context window + UI state. Always called from the serial
     * translate worker so cumulative / segments order matches utterance order.
     */
    private fun commitTranslation(job: PendingTranslate, en: String, zh: String) {
        val now = System.currentTimeMillis()
        // Cut-time recording offset (pause-excluded). 0 is valid for the first utterance.
        val offset = job.utt.offsetMs.coerceAtLeast(0L)
        val seg = TranscriptSegment(
            source = en,
            translation = zh,
            cutReason = job.utt.reason,
            incomplete = false,
            timestampMs = now,
            offsetMs = offset
        )
        contextWindow.addLast(ContextTurn(en, zh))
        while (contextWindow.size > job.windowSize.coerceAtLeast(0)) {
            contextWindow.removeFirst()
        }

        var turnCount = 0
        _state.update {
            val segs = it.segments + seg
            turnCount = segs.size
            it.copy(
                cumulativeEn = appendBlock(it.cumulativeEn, en),
                cumulativeZh = appendBlock(it.cumulativeZh, zh),
                // Clear EN partial only if it still shows this sentence (no newer ASR).
                partialEn = if (it.partialEn == en) "" else it.partialEn,
                partialZh = "",
                segments = segs,
                error = null,
                canRetry = false
            )
        }
        maybeRequestSessionTitle(turnCount)
    }

    /**
     * When dialogue reaches 10 turns, call LLM once to produce a short history title.
     * Runs off the translate queue so it does not reorder streaming translations.
     */
    private fun maybeRequestSessionTitle(turnCount: Int) {
        if (turnCount < LlmClient.TITLE_TURN_THRESHOLD) return
        if (!titleRequested.compareAndSet(false, true)) return
        val segsSnapshot = _state.value.segments.take(LlmClient.TITLE_TURN_THRESHOLD)
        if (segsSnapshot.isEmpty()) {
            titleRequested.set(false)
            return
        }
        titleJob = scope.launch {
            try {
                val settings = settingsRepo.settings.first()
                if (settings.llmApiKey.isBlank()) {
                    android.util.Log.w(TAG, "skip title: empty LLM key")
                    return@launch
                }
                val config = LlmConfig(
                    baseUrl = settings.normalizedLlmBaseUrl(),
                    apiKey = settings.llmApiKey.trim(),
                    model = settings.llmModel.trim(),
                    targetLanguage = settings.outputLanguage.trim(),
                    sourceLanguage = settings.inputLanguage.trim(),
                    systemPrompt = settings.renderLlmSystemPrompt(),
                    authStyle = settings.llmAuthStyleEnum(),
                    fullUrl = settings.llmFullUrl,
                    thinking = settings.llmThinkingMode()
                )
                val title = llm.summarizeSessionTitle(segsSnapshot, config)
                if (title.isNotBlank()) {
                    sessionTitle = title
                    _state.update { it.copy(sessionTitle = title) }
                    android.util.Log.i(TAG, "session title: $title")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "session title failed", e)
            }
        }
    }

    private fun resetTitleState() {
        titleJob?.cancel()
        titleJob = null
        titleRequested.set(false)
        sessionTitle = null
    }

    private fun appendBlock(prev: String, next: String): String =
        if (prev.isEmpty()) next else prev + "\n" + next

    private data class PendingTranslate(
        val utt: UtteranceAudio,
        val en: String,
        val windowSize: Int
    )

    private class RetryableException(cause: Throwable) : Exception(cause.message, cause)

    companion object {
        private const val TAG = "SessionOrchestrator"
        /** Max wait for in-flight title LLM when user stops. */
        private const val TITLE_WAIT_MS = 12_000L
    }
}
