package com.example.livetranslate.domain

import android.content.Context
import android.net.Uri
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.FfmpegAudioConverter
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.audio.WavChunker
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.llm.LlmConfig
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
 * Used by history re-run, orphan recovery, and home **file** import.
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
                it.copy(error = "请先结束当前实时会话，再导入文件")
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
                    error = "已取消文件导入"
                )
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "startFromUri failed", e)
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = e.message ?: "文件导入失败"
                )
            }
        }
    }

    fun cancel() {
        if (!isBusy) return
        cancelRequested.set(true)
        _state.update {
            it.copy(phase = ReprocessPhase.Cancelling, message = "正在取消…")
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
                it.copy(error = "请先结束当前实时会话，再事后重跑")
            }
            return
        }
        val file = SessionAudioRecorder.fileForPath(audioPath)
        if (file == null) {
            _state.update { it.copy(error = "录音文件无效或已损坏") }
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
                    error = "已取消重跑"
                )
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "reprocess failed", e)
                _state.value = ReprocessUiState(
                    phase = ReprocessPhase.Idle,
                    error = e.message ?: "重跑失败"
                )
            }
        }
    }

    private suspend fun runFromUri(uri: Uri) {
        val settings = settingsRepo.settings.first()
        validateKeys(settings)
        if (!network.isOnline()) {
            throw Exception("当前离线，无法导入文件")
        }

        val startedAt = System.currentTimeMillis()
        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Running,
            message = "正在复制文件…"
        )

        val workDir = File(appContext.cacheDir, "import_audio").apply { mkdirs() }
        val inputCopy = File(workDir, "src_${startedAt}${guessExtension(uri)}")
        val outWav = File(workDir, "converted_${startedAt}.wav")
        checkCancel()
        copyUriToFile(uri, inputCopy)

        checkCancel()
        _state.update { it.copy(message = "FFmpeg 转码中…") }
        val converted = ffmpeg.convertTo16kMonoWav(
            inputPath = inputCopy.absolutePath,
            outputWav = outWav
        ) { p ->
            _state.update {
                it.copy(message = "FFmpeg 转码 ${(p * 100).toInt()}%")
            }
        }

        checkCancel()
        _state.update { it.copy(message = "安装会话录音…") }
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
            throw Exception("当前离线，无法处理")
        }

        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Running,
            message = "读取录音…",
            activeAudioPath = audioPath
        )

        val chunks = try {
            WavChunker.chunkPcm(file)
        } catch (e: Exception) {
            throw Exception("无法读取录音：${e.message}")
        }
        if (chunks.isEmpty()) {
            throw Exception("录音无有效音频数据")
        }

        checkCancel()
        val asrParts = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            checkCancel()
            _state.update {
                it.copy(
                    asrChunkIndex = chunk.index + 1,
                    asrChunkTotal = chunk.total,
                    message = "识别 ${chunk.index + 1}/${chunk.total}"
                )
            }
            val text = transcribeWithRetry(chunk, settings)
            if (text.isNotBlank()) asrParts.add(text)
        }

        val fullText = asrParts.joinToString(" ").trim()
        if (fullText.isBlank()) {
            throw Exception("ASR 未识别到有效文本")
        }

        val sentences = PunctuationSegmenter.split(fullText)
        if (sentences.isEmpty()) {
            throw Exception("标点切句后无有效句子")
        }

        checkCancel()
        val window = ArrayDeque<ContextTurn>()
        val windowSize = settings.contextWindowSize.coerceAtLeast(0)
        val segments = ArrayList<TranscriptSegment>(sentences.size)
        val startedAt = startedAtOverride ?: System.currentTimeMillis()

        for ((idx, sentence) in sentences.withIndex()) {
            checkCancel()
            _state.update {
                it.copy(
                    translateIndex = idx + 1,
                    translateTotal = sentences.size,
                    message = "翻译 ${idx + 1}/${sentences.size}"
                )
            }
            val zh = translateWithRetry(sentence, window.toList(), settings)
            segments.add(
                TranscriptSegment(
                    source = sentence,
                    translation = zh,
                    cutReason = CutReason.Silence,
                    incomplete = false,
                    timestampMs = System.currentTimeMillis(),
                    offsetMs = 0L
                )
            )
            window.addLast(ContextTurn(sentence, zh))
            while (window.size > windowSize) window.removeFirst()
        }

        checkCancel()
        val title = resolveTitle(segments, settings, baseTitle, titlePolicy)
        _state.update {
            it.copy(message = "保存会话…", sessionTitle = title)
        }
        val endedAt = System.currentTimeMillis()
        val id = history.saveSession(
            startedAt = startedAt,
            endedAt = endedAt,
            segments = segments,
            audioPath = audioPath,
            title = title
        )

        _state.value = ReprocessUiState(
            phase = ReprocessPhase.Idle,
            message = "已保存：$title",
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
        if (segments.size < LlmClient.TITLE_TURN_THRESHOLD) return null
        if (settings.llmApiKey.isBlank()) return null
        if (!network.isOnline()) return null
        checkCancel()
        _state.update { it.copy(message = "生成会话标题…") }
        return try {
            withTimeoutOrNull(TITLE_CALL_TIMEOUT_MS) {
                llm.summarizeSessionTitle(
                    segments = segments,
                    config = LlmConfig(
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
        return en.ifBlank { "文件导入" }
    }

    private fun installSessionWav(source: File, startedAt: Long): String {
        val dir = File(appContext.filesDir, SessionAudioRecorder.RECORDINGS_DIR).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(startedAt))
        val dest = File(dir, "session_${stamp}_import.wav")
        source.copyTo(dest, overwrite = true)
        if (!dest.isFile || dest.length() < 44L) {
            throw IllegalStateException("导入会话 WAV 写入失败")
        }
        return dest.absolutePath
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取所选文件")
        if (!dest.isFile || dest.length() < 64L) {
            throw IllegalStateException("文件过小或复制失败")
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
            throw Exception("ASR API Key is empty. Configure it in Settings.")
        }
        if (settings.llmApiKey.isBlank()) {
            throw Exception("LLM API Key is empty. Configure it in Settings.")
        }
    }

    private fun checkCancel() {
        if (cancelRequested.get() || !scope.isActive) {
            throw CancellationException("cancel")
        }
    }

    private suspend fun transcribeWithRetry(
        chunk: WavChunker.PcmChunk,
        settings: UserSettings
    ): String {
        var attempt = 0
        var last: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            checkCancel()
            try {
                awaitOnline()
                return runAsr(chunk, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }
        throw Exception("ASR 失败（块 ${chunk.index + 1}/${chunk.total}）：${last?.message}")
    }

    private suspend fun translateWithRetry(
        source: String,
        context: List<ContextTurn>,
        settings: UserSettings
    ): String {
        var attempt = 0
        var last: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            checkCancel()
            try {
                awaitOnline()
                return runTranslate(source, context, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                attempt++
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) break
                delay(500L * attempt * attempt)
            }
        }
        throw Exception("翻译失败：「${source.take(40)}」${last?.message}")
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
            LlmConfig(
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
        ).collect { ev ->
            when (ev) {
                is LlmStreamEvent.Delta -> zh += ev.text
                is LlmStreamEvent.Completed -> if (ev.fullText.isNotEmpty()) zh = ev.fullText
                is LlmStreamEvent.Error -> {
                    val ex = Exception(ev.throwable.message ?: "LLM error", ev.throwable)
                    if (ev.retryable) throw Retryable(ex) else throw ex
                }
            }
        }
        if (zh.isBlank()) throw Exception("译文为空")
        return zh.trim()
    }

    private fun isRetryable(e: Exception): Boolean {
        if (e is Retryable) return true
        val msg = e.message.orEmpty()
        return msg.contains("timeout", ignoreCase = true) ||
            msg.contains("HTTP 429") ||
            msg.contains("HTTP 5") ||
            msg.contains("HTTP 408")
    }

    private class Retryable(cause: Exception) : Exception(cause.message, cause)

    companion object {
        private const val TAG = "OfflineReprocess"
        private const val MAX_ATTEMPTS = 3
        private const val TITLE_CALL_TIMEOUT_MS = 20_000L
    }
}
