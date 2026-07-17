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
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.llm.LlmThinkingMode
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
            WavChunker.chunkByVad(file, settings, appContext, batchSize) { progress, _ ->
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
        val asrParts = ArrayList<TimedAsrPart>(chunks.size)
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
                    if (outcome.text.isNotBlank()) {
                        asrParts.add(
                            TimedAsrPart(
                                text = outcome.text,
                                startMs = chunk.startMs.coerceAtLeast(0L),
                                durationMs = chunk.durationMs.coerceAtLeast(0L)
                            )
                        )
                    }
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

        val fullText = asrParts.joinToString(" ") { it.text }.trim()
        if (fullText.isBlank()) {
            val hint = lastAsrSkipReason?.let { " ($it)" }.orEmpty()
            throw Exception(str(R.string.offline_asr_empty) + hint)
        }

        // Split per timed ASR chunk so sentence offsets track VAD/chunk startMs.
        val timedSources = expandTimedSentences(asrParts)
        if (timedSources.isEmpty()) {
            throw Exception(str(R.string.offline_split_empty))
        }
        if (timedSources.size == 1 && asrParts.size == 1) {
            // single blob often means punctuation split failed — UI already has fallback copy path
            android.util.Log.w(TAG, "punctuation split produced single sentence")
            _state.update {
                it.copy(message = str(R.string.offline_split_fallback))
            }
        }

        checkCancel()
        val sources = timedSources.map { it.text }
        val offsets = timedSources.map { it.offsetMs }
        val window = ArrayDeque<ContextTurn>()
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val segments = ArrayList<TranscriptSegment>(sources.size)
        val startedAt = startedAtOverride ?: System.currentTimeMillis()
        var translateIncomplete = 0
        val total = sources.size

        if (settings.asrOnlyMode) {
            for ((idx, source) in sources.withIndex()) {
                checkCancel()
                _state.update {
                    it.copy(
                        translateIndex = idx + 1,
                        translateTotal = total,
                        message = str(R.string.offline_asr_progress, idx + 1, total)
                    )
                }
                segments.add(
                    TranscriptSegment(
                        source = source,
                        translation = "",
                        cutReason = CutReason.Silence,
                        incomplete = false,
                        timestampMs = System.currentTimeMillis(),
                        offsetMs = offsets.getOrElse(idx) { 0L }
                    )
                )
            }
        } else {
            // Batch many sources per LLM call (||| delimiter) — fewer round-trips.
            val batches = BatchTranslation.chunkSources(sources, BatchTranslation.DEFAULT_BATCH_SIZE)
            var done = 0
            for ((batchIdx, batch) in batches.withIndex()) {
                checkCancel()
                val batchStart = done
                val batchOffsets = offsets.subList(batchStart, batchStart + batch.size).toList()
                _state.update {
                    it.copy(
                        translateIndex = (batchStart + 1).coerceAtMost(total),
                        translateTotal = total,
                        message = str(
                            R.string.offline_translate_batch_progress,
                            batchIdx + 1,
                            batches.size,
                            batchStart + 1,
                            (batchStart + batch.size).coerceAtMost(total),
                            total
                        )
                    )
                }
                val resolved = resolveBatchTranslations(
                    batch = batch,
                    batchOffsets = batchOffsets,
                    batchIdx = batchIdx,
                    batchTotal = batches.size,
                    batchStart = batchStart,
                    total = total,
                    window = window,
                    windowSize = windowSize,
                    settings = settings
                )
                translateIncomplete += resolved.incompleteCount
                segments.addAll(resolved.segments)
                done += batch.size
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

    private sealed class SoftBatchZh {
        data class Ok(
            val translations: List<String>,
            val countMismatch: Boolean = false
        ) : SoftBatchZh()

        data class Failed(val reason: String) : SoftBatchZh()
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

    private data class BatchResolveResult(
        val segments: List<TranscriptSegment>,
        val incompleteCount: Int
    )

    /**
     * Batch translate → if segment count mismatches or some slots blank, fall back to
     * one-by-one translate so source/translation stay aligned. Never drops sources.
     */
    private suspend fun resolveBatchTranslations(
        batch: List<String>,
        batchOffsets: List<Long>,
        batchIdx: Int,
        batchTotal: Int,
        batchStart: Int,
        total: Int,
        window: ArrayDeque<ContextTurn>,
        windowSize: Int,
        settings: UserSettings
    ): BatchResolveResult {
        val outcome = translateBatchSoft(batch, window.toList(), settings)
        val (zhList, usedSingleFallback) = when (outcome) {
            is SoftBatchZh.Ok -> {
                if (outcome.countMismatch) {
                    // Misaligned batch is unsafe (later sentences shift) → discard and re-do 1:1.
                    android.util.Log.w(
                        TAG,
                        "batch ${batchIdx + 1}: count mismatch (expected ${batch.size}); " +
                            "fallback to single-sentence translate"
                    )
                    _state.update {
                        it.copy(
                            message = str(
                                R.string.offline_translate_fallback_single,
                                batchIdx + 1,
                                batchTotal
                            )
                        )
                    }
                    translateSinglesFallback(
                        batch, window.toList(), settings, batchStart, total
                    ) to true
                } else {
                    // Count matches: re-translate only blank slots (keep alignment).
                    val filled = outcome.translations.toMutableList()
                    val local = ArrayDeque(window)
                    val wSize = settings.contextWindowSize.coerceAtLeast(0)
                    var anySingle = false
                    for (i in batch.indices) {
                        if (filled[i].isNotBlank()) {
                            local.addLast(ContextTurn(batch[i], filled[i]))
                            while (local.size > wSize) local.removeFirst()
                            continue
                        }
                        checkCancel()
                        _state.update {
                            it.copy(
                                translateIndex = (batchStart + i + 1).coerceAtMost(total),
                                translateTotal = total,
                                message = str(
                                    R.string.offline_translate_progress,
                                    batchStart + i + 1,
                                    total
                                )
                            )
                        }
                        when (val one = translateOneSoft(batch[i], local.toList(), settings)) {
                            is SoftZh.Ok -> {
                                filled[i] = one.text
                                anySingle = true
                                local.addLast(ContextTurn(batch[i], one.text))
                                while (local.size > wSize) local.removeFirst()
                            }
                            is SoftZh.Incomplete -> { /* leave blank */ }
                        }
                    }
                    filled to anySingle
                }
            }
            is SoftBatchZh.Failed -> {
                android.util.Log.w(
                    TAG,
                    "batch ${batchIdx + 1}/${batchTotal} failed (${outcome.reason}); " +
                        "fallback to single-sentence translate"
                )
                _state.update {
                    it.copy(
                        message = str(
                            R.string.offline_translate_fallback_single,
                            batchIdx + 1,
                            batchTotal
                        )
                    )
                }
                translateSinglesFallback(
                    batch, window.toList(), settings, batchStart, total
                ) to true
            }
        }
        if (usedSingleFallback) {
            android.util.Log.i(TAG, "batch ${batchIdx + 1}: single fallback done")
        }

        var incompleteCount = 0
        val segs = ArrayList<TranscriptSegment>(batch.size)
        for (i in batch.indices) {
            val source = batch[i]
            val zh = zhList.getOrElse(i) { "" }.trim()
            val incomplete = zh.isBlank()
            if (incomplete) {
                incompleteCount++
                android.util.Log.w(
                    TAG,
                    "batch ${batchIdx + 1}: empty translation for sentence ${batchStart + i + 1}"
                )
            }
            segs.add(
                TranscriptSegment(
                    source = source,
                    translation = zh,
                    cutReason = CutReason.Silence,
                    incomplete = incomplete,
                    timestampMs = System.currentTimeMillis(),
                    offsetMs = batchOffsets.getOrElse(i) { 0L }
                )
            )
            if (!incomplete) {
                window.addLast(ContextTurn(source, zh))
                while (window.size > windowSize) window.removeFirst()
            }
        }
        return BatchResolveResult(segs, incompleteCount)
    }

    /** One LLM call per source; returns list same size as [sources] (blank on soft fail). */
    private suspend fun translateSinglesFallback(
        sources: List<String>,
        contextSeed: List<ContextTurn>,
        settings: UserSettings,
        batchStart: Int,
        total: Int
    ): List<String> {
        val localWindow = ArrayDeque(contextSeed)
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val out = ArrayList<String>(sources.size)
        for ((i, source) in sources.withIndex()) {
            checkCancel()
            _state.update {
                it.copy(
                    translateIndex = (batchStart + i + 1).coerceAtMost(total),
                    translateTotal = total,
                    message = str(
                        R.string.offline_translate_progress,
                        batchStart + i + 1,
                        total
                    )
                )
            }
            when (val one = translateOneSoft(source, localWindow.toList(), settings)) {
                is SoftZh.Ok -> {
                    out.add(one.text)
                    localWindow.addLast(ContextTurn(source, one.text))
                    while (localWindow.size > windowSize) localWindow.removeFirst()
                }
                is SoftZh.Incomplete -> out.add("")
            }
        }
        return out
    }

    /**
     * Batch translate with retries; on exhaustion returns [SoftBatchZh.Failed]
     * so the caller can fall back to single-sentence translate.
     */
    private suspend fun translateBatchSoft(
        sources: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings
    ): SoftBatchZh {
        require(sources.isNotEmpty())
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                val raw = runTranslateBatch(sources, context, settings)
                val parts = BatchTranslation.parseTranslations(raw, sources.size)
                val nonBlank = parts.count { it.isNotBlank() }
                if (nonBlank == 0) {
                    return SoftBatchZh.Failed(str(R.string.offline_empty_translation))
                }
                return SoftBatchZh.Ok(
                    translations = parts,
                    countMismatch = BatchTranslation.hadCountMismatch(raw, sources.size)
                )
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
            "batch×${sources.size}"
        )
        return SoftBatchZh.Failed(reason)
    }

    /**
     * Single-utterance translate with retries (settings prompts). Soft-fail → empty ZH.
     */
    private suspend fun translateOneSoft(
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
                val zh = runTranslateOne(source, context, settings)
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
        val ok = withTimeoutOrNull(AWAIT_ONLINE_TIMEOUT_MS) {
            while (!network.isOnline()) {
                checkCancel()
                delay(500)
                network.refresh()
            }
            true
        }
        if (ok != true) {
            throw Exception(str(R.string.offline_offline))
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

    private suspend fun runTranslateBatch(
        sources: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        val (system, user) = BatchTranslation.buildMessages(sources, context, settings)
        // Offline batch prioritizes throughput: disable chain-of-thought overhead.
        val config = settings.toLlmConfig().copy(
            thinking = LlmThinkingMode.Disabled
        )
        return collectChat(system, user, config)
    }

    private suspend fun runTranslateOne(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        val config = settings.toLlmConfig().copy(
            thinking = LlmThinkingMode.Disabled
        )
        var zh = ""
        llm.translateStream(source, context, config).collect { ev ->
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
        return LlmClient.stripThinkingArtifacts(zh).trim()
    }

    private suspend fun collectChat(
        system: String,
        user: String,
        config: LlmConfig
    ): String {
        var zh = ""
        llm.chatStream(system, user, config).collect { ev ->
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
        return LlmClient.stripThinkingArtifacts(zh).trim()
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

    private data class TimedAsrPart(
        val text: String,
        val startMs: Long,
        val durationMs: Long
    )

    private data class TimedSource(
        val text: String,
        val offsetMs: Long
    )

    /**
     * Split each timed ASR chunk into sentences and assign offsets from chunk startMs,
     * distributing evenly across the chunk duration when multiple sentences are produced.
     */
    private fun expandTimedSentences(parts: List<TimedAsrPart>): List<TimedSource> {
        val out = ArrayList<TimedSource>()
        for (p in parts) {
            var sents = PunctuationSegmenter.split(p.text)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sents.isEmpty()) {
                val t = p.text.trim()
                if (t.isNotEmpty()) sents = listOf(t)
            }
            if (sents.isEmpty()) continue
            val n = sents.size
            for ((i, s) in sents.withIndex()) {
                val offset = p.startMs + if (n <= 1 || p.durationMs <= 0L) {
                    0L
                } else {
                    p.durationMs * i / n
                }
                out.add(TimedSource(s, offset.coerceAtLeast(0L)))
            }
        }
        return out
    }

    companion object {
        private const val TAG = "OfflineReprocess"
        private const val TITLE_CALL_TIMEOUT_MS = 20_000L
        private const val AWAIT_ONLINE_TIMEOUT_MS = 120_000L
    }
}
