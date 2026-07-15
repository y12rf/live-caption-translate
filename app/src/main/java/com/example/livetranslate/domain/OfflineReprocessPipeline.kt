package com.example.livetranslate.domain

import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ReprocessPhase {
    Idle,
    Running,
    Cancelling
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
    /** Audio path currently being processed (for UI lock). */
    val activeAudioPath: String? = null
)

/**
 * Offline full-session reprocess: chunked ASR → punctuation split → sequential LLM → new Room session.
 * Shares WAV path with the source session; does not write on failure or cancel.
 */
class OfflineReprocessPipeline(
    private val scope: CoroutineScope,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
    private val network: NetworkMonitor,
    private val isLiveSessionBusy: () -> Boolean = { false }
) {
    private val _state = MutableStateFlow(ReprocessUiState())
    val state: StateFlow<ReprocessUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    val isBusy: Boolean
        get() = _state.value.phase == ReprocessPhase.Running ||
            _state.value.phase == ReprocessPhase.Cancelling

    /**
     * @param audioPath absolute path to session WAV
     * @param baseTitle original session preview / title for `Re` prefix
     */
    fun start(audioPath: String, baseTitle: String?) {
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
                runPipeline(file, audioPath, baseTitle)
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

    private suspend fun runPipeline(file: File, audioPath: String, baseTitle: String?) {
        val settings = settingsRepo.settings.first()
        validateKeys(settings)
        if (!network.isOnline()) {
            throw Exception("当前离线，无法重跑")
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
        val startedAt = System.currentTimeMillis()

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
        _state.update { it.copy(message = "保存会话…") }
        val endedAt = System.currentTimeMillis()
        val title = ReprocessTitle.reTitle(baseTitle)
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
            lastSavedSessionId = id
        )
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
    }
}
