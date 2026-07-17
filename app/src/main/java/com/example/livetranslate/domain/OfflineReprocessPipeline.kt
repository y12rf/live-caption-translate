package com.example.livetranslate.domain

import android.content.Context
import android.net.Uri
import com.example.livetranslate.R
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.FfmpegAudioConverter
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.audio.WavChunker
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.network.NetworkErrors
import com.example.livetranslate.data.network.NetworkMonitor
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.AsrStreamEvent
import com.example.livetranslate.domain.model.ContextTurn
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.LlmStreamEvent
import com.example.livetranslate.domain.model.TranscriptSegment
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class ReprocessPhase {
    Idle,
    Running,
    Cancelling
}

/**
 * How to build the history session title after offline ASR/translate.
 */
enum class OfflineTitlePolicy {
    /** History re-run / orphan: always `Re` + base (no LLM). */
    RePrefix,

    /**
     * File import: LLM summarize when ≥ [LlmClient.TITLE_TURN_THRESHOLD] turns,
     * else translation-preview fallback (same spirit as live Stop).
     */
    LlmThenPreview
}

data class ReprocessUiState(
    val phase: ReprocessPhase = ReprocessPhase.Idle,
    val asrChunkIndex: Int = 0,
    val asrChunkTotal: Int = 0,
    val translateIndex: Int = 0,
    val translateTotal: Int = 0,
    val message: String = "",
    val error: String? = null,
    /** Last successfully saved session id. */
    val lastSavedSessionId: Long? = null,
    /** Audio path currently being processed (for UI lock / orphan exclude). */
    val activeAudioPath: String? = null,
    /** Optional generated title for UI. */
    val sessionTitle: String? = null
)

/**
 * Offline full-session path: chunked ASR → punctuation split → sequential LLM → Room.
 * Used by history re-run, orphan recovery (and legacy file import).
 *
 * **Fault tolerance**: after per-item retries, ASR/LLM failures soft-skip so partial
 * results are still saved (mirrors live ASR-first / incomplete segments). Only aborts
 * when there is no usable text at all, or on cancel / fatal I/O.
 */
class OfflineReprocessPipeline(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
    private val network: NetworkMonitor,
    private val isLiveSessionBusy: () -> Boolean = { false }
) {
    private val ffmpeg = FfmpegAudioConverter()
    private val _state = MutableStateFlow(ReprocessUiState())
    val state: StateFlow<ReprocessUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    val isBusy: Boolean
        get() = _state.value.phase == ReprocessPhase.Running ||
            _state.value.phase == ReprocessPhase.Cancelling

    private fun str(resId: Int): String = appContext.getString(resId)

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    /**
     * History re-run or orphan recovery on an existing WAV.
     * Title: [OfflineTitlePolicy.RePrefix].
     */
    fun start(audioPath: String, baseTitle: String?) {
        startInternal(
            audioPath = audioPath,
            baseTitle = baseTitle,
            titlePolicy = OfflineTitlePolicy.RePrefix
        )
    }

    /**
     * Home file source: copy URI → FFmpeg 16k mono WAV → same offline ASR/translate path.
     * Title: [OfflineTitlePolicy.LlmThenPreview] (does not forget LLM session title).
     */
    fun startFromUri(uri: Uri) {
        if (isBusy) return
        if (isLiveSessionBusy()) {
            _state.update {
                it.copy(error = str(R.string.reprocess_busy_import))
            }
            return
        }
        cancelRequested.set(false)
        job?.cancel()
        job = scope.launch {
            try {
                runFromUri(uri)
            } catch (e: CancellationException) {
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = str(R.string.import_cancelled)
                )
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "startFromUri failed", e)
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = e.message ?: str(R.string.import_failed)
                )
            }
        }
    }

    fun cancel() {
        if (!isBusy) return
        cancelRequested.set(true)
        _state.update {
            it.copy(phase = ReprocessPhase.Cancelling, message = str(R.string.offline_cancelling))
        }
        job?.cancel()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearLastSaved() {
        _state.update { it.copy(lastSavedSessionId = null) }
    }

    private fun startInternal(
        audioPath: String,
        baseTitle: String?,
        titlePolicy: OfflineTitlePolicy
    ) {
        if (isBusy) return
        if (isLiveSessionBusy()) {
            _state.update {
                it.copy(error = str(R.string.reprocess_busy_live))
            }
            return
        }
        val file = SessionAudioRecorder.fileForPath(audioPath)
        if (file == null) {
            _state.update { it.copy(error = str(R.string.reprocess_bad_audio)) }
            return
        }

        cancelRequested.set(false)
        job?.cancel()
        job = scope.launch {
            try {
                runPipeline(file, audioPath, baseTitle, titlePolicy)
            } catch (e: CancellationException) {
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = str(R.string.reprocess_cancelled)
                )
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "reprocess failed", e)
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = e.message ?: str(R.string.reprocess_failed)
                )
            }
        }
    }

    private suspend fun runFromUri(uri: Uri) {
        val settings = settingsRepo.settings.first()
        validateKeys(settings)
        if (!network.isOnline()) {
            throw Exception(str(R.string.offline_offline))
        }

        val startedAt = System.currentTimeMillis()
        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Running,
            message = str(R.string.offline_copying)
        )

        val workDir = File(appContext.cacheDir, "import_audio").apply { mkdirs() }
        val inputCopy = File(workDir, "src_${startedAt}${guessExtension(uri)}")
        val outWav = File(workDir, "converted_${startedAt}.wav")
        checkCancel()
        copyUriToFile(uri, inputCopy)

        checkCancel()
        _state.update { it.copy(message = str(R.string.offline_ffmpeg)) }
        val converted = ffmpeg.convertTo16kMonoWav(
            inputPath = inputCopy.absolutePath,
            outputWav = outWav
        ) { p ->
            _state.update {
                it.copy(message = str(R.string.offline_ffmpeg_pct, (p * 100).toInt()))
            }
        }

        checkCancel()
        _state.update { it.copy(message = str(R.string.offline_install_wav)) }
        val sessionPath = installSessionWav(converted.wavFile, startedAt)
        runCatching { inputCopy.delete() }
        if (outWav.absolutePath != sessionPath) {
            runCatching { outWav.delete() }
        }

        val file = File(sessionPath)
        runPipeline(
            file = file,
            audioPath = sessionPath,
            baseTitle = null,
            titlePolicy = OfflineTitlePolicy.LlmThenPreview,
            startedAtOverride = startedAt
        )
    }

    private suspend fun runPipeline(
        file: File,
        audioPath: String,
        baseTitle: String?,
        titlePolicy: OfflineTitlePolicy,
        startedAtOverride: Long? = null
    ) {
        val settings = settingsRepo.settings.first()
        validateKeys(settings)
        if (!network.isOnline()) {
            throw Exception(str(R.string.offline_offline))
        }

        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Running,
            message = str(R.string.offline_read_audio),
            activeAudioPath = audioPath
        )

        // VAD cut sentences, pack every offlineVadBatchSize into one ASR upload.
        val batchSize = settings.offlineVadBatchSize.coerceIn(1, 200)
        val chunks = try {
            WavChunker.chunkByVad(file, settings, batchSize) { progress, _ ->
                _state.update {
                    it.copy(
                        message = str(
                            R.string.offline_vad_progress,
                            (progress * 100).toInt()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            throw Exception(str(R.string.offline_read_fail, e.message ?: ""))
        }
        if (chunks.isEmpty()) {
            throw Exception(str(R.string.offline_no_pcm))
        }

        checkCancel()
        val asrParts = ArrayList<String>(chunks.size)
        var asrSkipped = 0
        var lastAsrSkipReason: String? = null
        for (chunk in chunks) {
            checkCancel()
            _state.update {
                it.copy(
                    asrChunkIndex = chunk.index + 1,
                    asrChunkTotal = chunk.total,
                    message = str(R.string.offline_asr_progress, chunk.index + 1, chunk.total)
                )
            }
            when (val outcome = transcribeSoft(chunk, settings)) {
                is SoftAsr.Ok -> {
                    if (outcome.text.isNotBlank()) asrParts.add(outcome.text)
                    // blank ASR (noise) is soft-skipped without counting as failure
                }
                is SoftAsr.Skip -> {
                    asrSkipped++
                    lastAsrSkipReason = outcome.reason
                    android.util.Log.w(
                        TAG,
                        "ASR chunk ${chunk.index + 1}/${chunk.total} skipped: ${outcome.reason}"
                    )
                    _state.update {
                        it.copy(
                            message = str(
                                R.string.offline_asr_chunk_skip,
                                chunk.index + 1,
                                chunk.total,
                                outcome.reason.take(80)
                            )
                        )
                    }
                }
            }
        }

        val fullText = asrParts.joinToString(" ").trim()
        if (fullText.isBlank()) {
            val hint = lastAsrSkipReason?.let { " ($it)" }.orEmpty()
            throw Exception(str(R.string.offline_asr_empty) + hint)
        }

        // Fallback: no punctuation breaks → treat whole transcript as one segment
        var sentences = PunctuationSegmenter.split(fullText)
        if (sentences.isEmpty()) {
            sentences = listOf(fullText)
            android.util.Log.w(TAG, "punctuation split empty; using full text as one sentence")
            _state.update {
                it.copy(message = str(R.string.offline_split_fallback))
            }
        }

        checkCancel()
        val window = ArrayDeque<ContextTurn>()
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val segments = ArrayList<TranscriptSegment>(sentences.size)
        val startedAt = startedAtOverride ?: System.currentTimeMillis()
        var translateIncomplete = 0

        for ((idx, sentence) in sentences.withIndex()) {
            checkCancel()
            val source = sentence.trim()
            if (source.isEmpty()) continue

            val (zh, incomplete) = if (settings.asrOnlyMode) {
                _state.update {
                    it.copy(
                        translateIndex = idx + 1,
                        translateTotal = sentences.size,
                        message = str(R.string.offline_asr_progress, idx + 1, sentences.size)
                    )
                }
                "" to false
            } else {
                _state.update {
                    it.copy(
                        translateIndex = idx + 1,
                        translateTotal = sentences.size,
                        message = str(
                            R.string.offline_translate_progress,
                            idx + 1,
                            sentences.size
                        )
                    )
                }
                when (val outcome = translateSoft(source, window.toList(), settings)) {
                    is SoftZh.Ok -> outcome.text to false
                    is SoftZh.Incomplete -> {
                        translateIncomplete++
                        android.util.Log.w(
                            TAG,
                            "translate soft-fail sentence ${idx + 1}: ${outcome.reason}"
                        )
                        _state.update {
                            it.copy(
                                message = str(
                                    R.string.offline_translate_skip,
                                    idx + 1,
                                    outcome.reason.take(80)
                                )
                            )
                        }
                        "" to true
                    }
                }
            }
            segments.add(
                TranscriptSegment(
                    source = source,
                    translation = zh,
                    cutReason = CutReason.Silence,
                    incomplete = incomplete,
                    timestampMs = System.currentTimeMillis(),
                    // Prefer chunk-level offset when single-sentence; reprocess is
                    // punctuation-based so offsets stay 0 (design).
                    offsetMs = 0L
                )
            )
            // Only feed successful pairs into context (avoid poisoning glossary/context)
            if (!incomplete && zh.isNotBlank()) {
                window.addLast(ContextTurn(source, zh))
                while (window.size > windowSize) window.removeFirst()
            }
        }

        if (segments.isEmpty()) {
            throw Exception(str(R.string.offline_split_empty))
        }

        checkCancel()
        val title = resolveTitle(segments, settings, baseTitle, titlePolicy)
        _state.update {
            it.copy(message = str(R.string.offline_saving), sessionTitle = title)
        }
        val endedAt = System.currentTimeMillis()
        val id = try {
            history.saveSession(
                startedAt = startedAt,
                endedAt = endedAt,
                segments = segments,
                audioPath = audioPath,
                title = title
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveSession failed", e)
            throw Exception(
                str(R.string.offline_save_fail, e.message ?: e.javaClass.simpleName)
            )
        }

        val savedMsg = if (asrSkipped > 0 || translateIncomplete > 0) {
            str(
                R.string.offline_saved_partial,
                title,
                asrSkipped,
                translateIncomplete
            )
        } else {
            str(R.string.offline_saved, title)
        }
        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Idle,
            message = savedMsg,
            lastSavedSessionId = id,
            sessionTitle = title
        )
    }

    private suspend fun resolveTitle(
        segments: List<TranscriptSegment>,
        settings: UserSettings,
        baseTitle: String?,
        policy: OfflineTitlePolicy
    ): String {
        return when (policy) {
            OfflineTitlePolicy.RePrefix -> ReprocessTitle.reTitle(baseTitle)
            OfflineTitlePolicy.LlmThenPreview -> {
                val llmTitle = maybeLlmTitle(segments, settings)
                llmTitle?.takeIf { it.isNotBlank() }
                    ?: previewFromSegments(segments)
            }
        }
    }

    /**
     * Same threshold as live [SessionOrchestrator.maybeRequestSessionTitle].
     * Failure is non-fatal — fall back to preview.
     */
    private suspend fun maybeLlmTitle(
        segments: List<TranscriptSegment>,
        settings: UserSettings
    ): String? {
        val titleThreshold = settings.titleTurnThreshold.coerceIn(1, 50)
        if (segments.size < titleThreshold) return null
        if (settings.llmApiKey.isBlank()) return null
        if (!network.isOnline()) return null
        checkCancel()
        _state.update { it.copy(message = str(R.string.offline_title_gen)) }
        return try {
            withTimeoutOrNull(TITLE_CALL_TIMEOUT_MS) {
                llm.summarizeSessionTitle(
                    segments = segments.take(titleThreshold),
                    config = settings.toLlmConfig()
                )
            }?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "session title failed", e)
            null
        }
    }

    private fun previewFromSegments(segments: List<TranscriptSegment>): String {
        val zh = segments.joinToString(" ") { it.translation }.trim().take(80)
        if (zh.isNotBlank()) return zh
        val en = segments.joinToString(" ") { it.source }.trim().take(80)
        return en.ifBlank { str(R.string.offline_file_title_fallback) }
    }

    private fun installSessionWav(source: File, startedAt: Long): String {
        val dir = File(appContext.filesDir, SessionAudioRecorder.RECORDINGS_DIR).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(startedAt))
        val dest = File(dir, "session_${stamp}_import.wav")
        source.copyTo(dest, overwrite = true)
        if (!dest.isFile || dest.length() < 44L) {
            throw IllegalStateException(str(R.string.import_wav_fail))
        }
        return dest.absolutePath
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException(str(R.string.import_uri_fail))
        if (!dest.isFile || dest.length() < 64L) {
            throw IllegalStateException(str(R.string.import_file_too_small))
        }
    }

    private fun guessExtension(uri: Uri): String {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return ".bin"
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ".bin"
        val ext = name.substring(dot).lowercase()
        return if (ext.length in 2..8 && ext.all { it.isLetterOrDigit() || it == '.' }) {
            ext
        } else {
            ".bin"
        }
    }

    private fun validateKeys(settings: UserSettings) {
        if (settings.asrApiKey.isBlank()) {
            throw Exception(str(R.string.asr_key_empty))
        }
        if (!settings.asrOnlyMode && settings.llmApiKey.isBlank()) {
            throw Exception(str(R.string.llm_key_empty))
        }
    }

    private fun checkCancel() {
        if (cancelRequested.get() || !scope.isActive) {
            throw CancellationException("cancel")
        }
    }

    private sealed class SoftAsr {
        data class Ok(val text: String) : SoftAsr()
        data class Skip(val reason: String) : SoftAsr()
    }

    private sealed class SoftZh {
        data class Ok(val text: String) : SoftZh()
        data class Incomplete(val reason: String) : SoftZh()
    }

    /**
     * ASR with retries; on exhaustion returns [SoftAsr.Skip] instead of aborting the job.
     */
    private suspend fun transcribeSoft(
        chunk: WavChunker.PcmChunk,
        settings: UserSettings
    ): SoftAsr {
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                return SoftAsr.Ok(runAsr(chunk, settings))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= maxAttempts || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }
        val reason = NetworkErrors.userMessage(
            last ?: Exception("ASR failed"),
            "ASR ${chunk.index + 1}/${chunk.total}"
        )
        return SoftAsr.Skip(reason)
    }

    /**
     * Translate with retries; on exhaustion returns [SoftZh.Incomplete] (empty ZH)
     * so the session can still be saved with source text.
     */
    private suspend fun translateSoft(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): SoftZh {
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                val zh = runTranslate(source, context, settings)
                return if (zh.isBlank()) {
                    SoftZh.Incomplete(str(R.string.offline_empty_translation))
                } else {
                    SoftZh.Ok(zh)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= maxAttempts || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }
        val reason = NetworkErrors.userMessage(
            last ?: Exception("translate failed"),
            source.take(40)
        )
        return SoftZh.Incomplete(reason)
    }

    private suspend fun awaitOnline() {
        if (network.isOnline()) return
        while (!network.isOnline()) {
            checkCancel()
            delay(500)
            network.refresh()
        }
    }

    private suspend fun runAsr(chunk: WavChunker.PcmChunk, settings: UserSettings): String {
        val utt = UtteranceAudio(
            pcm = chunk.pcm,
            sampleRate = chunk.sampleRate,
            reason = CutReason.MaxDuration,
            offsetMs = chunk.startMs
        )
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
                is AsrStreamEvent.Delta -> en = AsrOutputSanitizer.clean(ev.text)
                is AsrStreamEvent.Completed -> en = AsrOutputSanitizer.clean(ev.fullText)
                is AsrStreamEvent.Error -> {
                    val ex = Exception(ev.throwable.message ?: "ASR error", ev.throwable)
                    if (ev.retryable) throw Retryable(ex) else throw ex
                }
            }
        }
        return AsrOutputSanitizer.clean(en)
    }

    private suspend fun runTranslate(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        var zh = ""
        llm.translateStream(
            source,
            context,
            settings.toLlmConfig()
        ).collect { ev ->
            when (ev) {
                is LlmStreamEvent.Delta -> zh += ev.text
                is LlmStreamEvent.Completed -> {
                    if (ev.fullText.isNotEmpty()) {
                        zh = LlmClient.stripThinkingArtifacts(ev.fullText)
                    }
                }
                is LlmStreamEvent.Error -> {
                    val ex = Exception(ev.throwable.message ?: "LLM error", ev.throwable)
                    if (ev.retryable) throw Retryable(ex) else throw ex
                }
            }
        }
        zh = LlmClient.stripThinkingArtifacts(zh)
        // Blank handled by translateSoft → Incomplete (do not abort whole job)
        return zh.trim()
    }

    private fun isRetryable(e: Exception): Boolean {
        if (e is Retryable) return true
        // HTTP status embedded in messages from AsrClient / LlmClient
        val msg = e.message.orEmpty()
        val httpCode = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
            .find(msg)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (httpCode != null) {
            return NetworkErrors.isRetryableHttp(httpCode)
        }
        return NetworkErrors.isRetryableThrowable(e)
    }

    private class Retryable(cause: Exception) : Exception(cause.message, cause)

    companion object {
        private const val TAG = "OfflineReprocess"
        private const val TITLE_CALL_TIMEOUT_MS = 20_000L
    }
}
