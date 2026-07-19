package com.example.livetranslate.domain.reprocess

import android.content.Context
import com.example.livetranslate.R
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.FileAudioSegmenter
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.audio.WavEncoder
import com.example.livetranslate.data.audio.WavPcmReader
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.llm.LlmThinkingMode
import com.example.livetranslate.data.network.NetworkErrors
import com.example.livetranslate.data.network.NetworkMonitor
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.BatchTranslation
import com.example.livetranslate.domain.PunctuationSegmenter
import com.example.livetranslate.domain.ReprocessTitle
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scheme C′ offline reprocess:
 * history timeline windows → multi-sentence ASR (20/30 + 90s) → punctuation split →
 * batch translate with few-shot ||| → save only if fully successful (offsetMs = 0).
 *
 * Block ASR failure → file-import style VAD on that slice; still fail-closed if fallback fails.
 */
class ReprocessEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
    private val network: NetworkMonitor,
    private val isLiveSessionBusy: () -> Boolean = { false },
    private val fileSegmenter: FileAudioSegmenter = FileAudioSegmenter(appContext = appContext)
) {
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
     * History re-run with optional timeline offsets (sentence starts).
     * Empty / all-zero offsets → VAD windows on the full file.
     */
    fun start(
        audioPath: String,
        baseTitle: String?,
        segmentOffsets: List<Long> = emptyList()
    ) {
        if (isBusy) return
        if (isLiveSessionBusy()) {
            _state.update { it.copy(error = str(R.string.reprocess_busy_live)) }
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
                runPipeline(file, audioPath, baseTitle, segmentOffsets)
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

    private suspend fun runPipeline(
        file: File,
        audioPath: String,
        baseTitle: String?,
        segmentOffsets: List<Long>
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

        val open = try {
            WavPcmReader.open(file)
        } catch (e: Exception) {
            throw Exception(str(R.string.offline_read_fail, e.message ?: ""))
        }
        val durationMs = open.durationMs
        if (durationMs <= 0L) {
            throw Exception(str(R.string.offline_no_pcm))
        }

        checkCancel()
        val windows = buildWindows(file, settings, segmentOffsets, durationMs)
        if (windows.isEmpty()) {
            throw Exception(str(R.string.offline_no_pcm))
        }

        // C′: default 20 / max 30 / 90s (settings offlineVadBatchSize ignored for reprocess)
        val packPolicy = AsrPackPolicy.Default

        val blocks = AsrBlockPacker.pack(windows, packPolicy)
        if (blocks.isEmpty()) {
            throw Exception(str(R.string.offline_no_pcm))
        }

        val blockTexts = ArrayList<String>(blocks.size)
        for (block in blocks) {
            checkCancel()
            _state.update {
                it.copy(
                    asrChunkIndex = block.index + 1,
                    asrChunkTotal = block.total,
                    message = str(R.string.offline_asr_progress, block.index + 1, block.total)
                )
            }
            val text = transcribeBlock(file, open.sampleRate, block, settings)
            if (text.isBlank()) {
                throw Exception(
                    str(R.string.offline_asr_empty) +
                        " (block ${block.index + 1}/${block.total})"
                )
            }
            blockTexts.add(text)
        }

        val fullText = blockTexts.joinToString(" ").trim()
        if (fullText.isBlank()) {
            throw Exception(str(R.string.offline_asr_empty))
        }

        var sources = PunctuationSegmenter.split(fullText)
        if (sources.isEmpty()) {
            sources = listOf(fullText)
        }
        sources = sources.map { it.trim() }.filter { it.isNotEmpty() }
        if (sources.isEmpty()) {
            throw Exception(str(R.string.offline_split_empty))
        }

        checkCancel()
        val segments: List<TranscriptSegment>
        if (settings.asrOnlyMode) {
            segments = sources.map { src ->
                TranscriptSegment(
                    source = src,
                    translation = "",
                    cutReason = CutReason.Silence,
                    incomplete = false,
                    timestampMs = System.currentTimeMillis(),
                    offsetMs = 0L
                )
            }
        } else {
            segments = translateAll(sources, settings)
        }

        checkCancel()
        val title = ReprocessTitle.reTitle(baseTitle)
        _state.update {
            it.copy(message = str(R.string.offline_saving), sessionTitle = title)
        }
        val startedAt = System.currentTimeMillis()
        val id = try {
            history.saveSession(
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                segments = segments,
                audioPath = audioPath,
                title = title
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception(
                str(R.string.offline_save_fail, e.message ?: e.javaClass.simpleName)
            )
        }

        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Idle,
            message = str(R.string.offline_saved, title),
            lastSavedSessionId = id,
            sessionTitle = title
        )
    }

    private suspend fun buildWindows(
        file: File,
        settings: UserSettings,
        segmentOffsets: List<Long>,
        durationMs: Long
    ): List<TimeWindow> {
        if (TimelineWindowBuilder.hasUsableTimeline(segmentOffsets)) {
            _state.update { it.copy(message = str(R.string.offline_read_audio)) }
            val built = TimelineWindowBuilder.build(segmentOffsets, durationMs)
            if (built.isNotEmpty()) return built
        }
        // VAD path (orphan / no timeline)
        _state.update {
            it.copy(message = str(R.string.offline_vad_progress, 0))
        }
        val utts = ArrayList<UtteranceAudio>()
        fileSegmenter.segment(file, settings) { progress, _ ->
            _state.update {
                it.copy(
                    message = str(
                        R.string.offline_vad_progress,
                        (progress * 100).toInt()
                    )
                )
            }
        }.collect { utt ->
            checkCancel()
            utts.add(utt)
        }
        if (utts.isEmpty()) return emptyList()
        return utts.mapIndexed { idx, utt ->
            val start = utt.offsetMs.coerceAtLeast(0L)
            val dur = pcmDurationMs(utt.pcm.size, utt.sampleRate)
            val end = (start + dur).coerceAtMost(durationMs).coerceAtLeast(start + 1)
            TimeWindow(startMs = start, endMs = end, index = idx)
        }
    }

    private suspend fun transcribeBlock(
        file: File,
        sampleRate: Int,
        block: AsrBlock,
        settings: UserSettings
    ): String {
        val pcm = WavPcmReader.readPcmRange(file, block.startMs, block.endMs)
        if (pcm.isEmpty()) {
            throw Exception("empty PCM block ${block.index + 1}")
        }
        return try {
            runAsrWithRetry(pcm, sampleRate, settings, "ASR ${block.index + 1}/${block.total}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "ASR block ${block.index + 1} failed (${e.message}); VAD fallback",
                e
            )
            _state.update {
                it.copy(
                    message = str(
                        R.string.offline_asr_chunk_skip,
                        block.index + 1,
                        block.total,
                        (e.message ?: "fallback").take(80)
                    )
                )
            }
            transcribeBlockViaVadFallback(pcm, sampleRate, block, settings)
        }
    }

    /**
     * File-import style: VAD-cut the block PCM, ASR each piece, join.
     * Any piece failure aborts the whole reprocess (fail-closed).
     */
    private suspend fun transcribeBlockViaVadFallback(
        pcm: ByteArray,
        sampleRate: Int,
        block: AsrBlock,
        settings: UserSettings
    ): String {
        val tmp = File(
            appContext.cacheDir,
            "reprocess_block_${block.index}_${System.currentTimeMillis()}.wav"
        )
        try {
            tmp.writeBytes(WavEncoder.pcm16MonoToWav(pcm, sampleRate))
            val parts = ArrayList<String>()
            var piece = 0
            fileSegmenter.segment(tmp, settings).collect { utt ->
                checkCancel()
                piece++
                val t = runAsrWithRetry(
                    utt.pcm,
                    utt.sampleRate,
                    settings,
                    "ASR fallback ${block.index + 1}.$piece"
                )
                if (t.isBlank()) {
                    throw Exception(
                        "fallback empty ASR block ${block.index + 1} piece $piece"
                    )
                }
                parts.add(t)
            }
            if (parts.isEmpty()) {
                throw Exception(
                    "fallback VAD empty block ${block.index + 1}/${block.total}"
                )
            }
            return parts.joinToString(" ").trim()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private suspend fun runAsrWithRetry(
        pcm: ByteArray,
        sampleRate: Int,
        settings: UserSettings,
        tag: String
    ): String {
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                return runAsr(pcm, sampleRate, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= maxAttempts || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }
        throw Exception(
            NetworkErrors.userMessage(last ?: Exception("ASR failed"), tag)
        )
    }

    private suspend fun runAsr(
        pcm: ByteArray,
        sampleRate: Int,
        settings: UserSettings
    ): String {
        val utt = UtteranceAudio(
            pcm = pcm,
            sampleRate = sampleRate,
            reason = CutReason.MaxDuration,
            offsetMs = 0L
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

    private suspend fun translateAll(
        sources: List<String>,
        settings: UserSettings
    ): List<TranscriptSegment> {
        val batches = BatchTranslation.chunkSources(
            sources,
            BatchTranslation.DEFAULT_BATCH_SIZE
        )
        val window = ArrayDeque<ContextTurn>()
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val out = ArrayList<TranscriptSegment>(sources.size)
        var done = 0
        val total = sources.size
        for ((batchIdx, batch) in batches.withIndex()) {
            checkCancel()
            _state.update {
                it.copy(
                    translateIndex = (done + 1).coerceAtMost(total),
                    translateTotal = total,
                    message = str(
                        R.string.offline_translate_batch_progress,
                        batchIdx + 1,
                        batches.size,
                        done + 1,
                        (done + batch.size).coerceAtMost(total),
                        total
                    )
                )
            }
            val zhList = translateBatchFailClosed(
                batch = batch,
                context = window.toList(),
                settings = settings,
                batchStart = done,
                total = total,
                batchIdx = batchIdx,
                batchTotal = batches.size
            )
            for (i in batch.indices) {
                val src = batch[i]
                val zh = zhList[i].trim()
                if (zh.isEmpty()) {
                    throw Exception(
                        str(R.string.offline_empty_translation) +
                            " (#${done + i + 1})"
                    )
                }
                out.add(
                    TranscriptSegment(
                        source = src,
                        translation = zh,
                        cutReason = CutReason.Silence,
                        incomplete = false,
                        timestampMs = System.currentTimeMillis(),
                        offsetMs = 0L
                    )
                )
                window.addLast(ContextTurn(src, zh))
                while (window.size > windowSize) window.removeFirst()
            }
            done += batch.size
        }
        return out
    }

    private suspend fun translateBatchFailClosed(
        batch: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings,
        batchStart: Int,
        total: Int,
        batchIdx: Int,
        batchTotal: Int
    ): List<String> {
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                val raw = runTranslateBatch(batch, context, settings)
                if (BatchTranslation.isRawBatchFullySuccessful(raw, batch.size)) {
                    return BatchTranslation.parseTranslations(raw, batch.size)
                }
                last = Exception("batch count/empty/dup mismatch")
                attempt++
                if (attempt >= maxAttempts) break
                delay(500L * attempt * attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= maxAttempts || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }

        // Single-sentence fallback (still fail-closed)
        android.util.Log.w(
            TAG,
            "batch ${batchIdx + 1}/$batchTotal falling back to singles: ${last?.message}"
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
        val local = ArrayDeque(context)
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val out = ArrayList<String>(batch.size)
        for ((i, source) in batch.withIndex()) {
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
            val zh = runTranslateOneWithRetry(source, local.toList(), settings)
            if (zh.isBlank()) {
                throw Exception(
                    str(R.string.offline_empty_translation) +
                        " (#${batchStart + i + 1})"
                )
            }
            out.add(zh)
            local.addLast(ContextTurn(source, zh))
            while (local.size > windowSize) local.removeFirst()
        }
        return out
    }

    private suspend fun runTranslateBatch(
        sources: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        val (system, user) = BatchTranslation.buildMessages(
            sources = sources,
            context = context,
            settings = settings,
            requireNonEmptySlots = true
        )
        val config = settings.toLlmConfig().copy(thinking = LlmThinkingMode.Disabled)
        return collectChat(system, user, config)
    }

    private suspend fun runTranslateOneWithRetry(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        val maxAttempts = settings.maxNetworkAttempts.coerceIn(1, 10)
        var attempt = 0
        var last: Exception? = null
        while (attempt < maxAttempts) {
            checkCancel()
            try {
                awaitOnline()
                val zh = runTranslateOne(source, context, settings)
                if (zh.isNotBlank()) return zh
                last = Exception(str(R.string.offline_empty_translation))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            attempt++
            if (attempt >= maxAttempts || (last != null && !isRetryable(last))) break
            delay(500L * attempt * attempt)
        }
        throw Exception(
            NetworkErrors.userMessage(
                last ?: Exception("translate failed"),
                source.take(40)
            )
        )
    }

    private suspend fun runTranslateOne(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        val config = settings.toLlmConfig().copy(thinking = LlmThinkingMode.Disabled)
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

    private fun isRetryable(e: Exception): Boolean {
        if (e is Retryable) return true
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

    private fun pcmDurationMs(pcmBytes: Int, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return pcmBytes / 2L * 1000L / sampleRate
    }

    private class Retryable(cause: Exception) : Exception(cause.message, cause)

    companion object {
        private const val TAG = "ReprocessEngine"
        private const val AWAIT_ONLINE_TIMEOUT_MS = 120_000L
    }
}
