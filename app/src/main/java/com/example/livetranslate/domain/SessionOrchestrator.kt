package com.example.livetranslate.domain

import android.content.Context
import android.net.Uri
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.asr.AsrConfig
import com.example.livetranslate.data.asr.AsrOutputSanitizer
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.audio.FfmpegAudioConverter
import com.example.livetranslate.data.audio.FileAudioSegmenter
import com.example.livetranslate.data.audio.UtteranceDiskQueue
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.network.NetworkErrors
import com.example.livetranslate.data.network.NetworkMonitor
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    val sessionTitle: String? = null,
    /** Connectivity for banner. */
    val networkOnline: Boolean = true,
    /** In-memory ASR queue depth (not yet processing). */
    val asrQueueDepth: Int = 0,
    /** Translate queue depth. */
    val llmQueueDepth: Int = 0,
    /** Disk-backed overflow / offline queue. */
    val diskQueueDepth: Int = 0,
    /** Sentences dropped because queues + disk policy rejected them. */
    val droppedUtterances: Int = 0,
    /** Failed items awaiting manual / auto retry. */
    val failedCount: Int = 0,
    val failedPreview: String? = null,
    /** Save-to-Room failed; [retrySave] available. */
    val saveError: String? = null,
    val canRetrySave: Boolean = false,
    /** Draining pipeline after Stop (waiting for queues). */
    val draining: Boolean = false,
    /** File-import status line (copy / FFmpeg / segment); null when not importing. */
    val importStatus: String? = null
)

/**
 * Pipelined session with weak-network hardening:
 * - bounded memory queues + disk overflow
 * - ASR-first segment commit; LLM-only retry
 * - fail list, offline park, drain-on-stop, save retry
 * - SSE idle / call timeouts (OkHttp client)
 */
class SessionOrchestrator(
    private val scope: CoroutineScope,
    private val audio: AudioCapture,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
    private val network: NetworkMonitor,
    appContext: Context
) {
    private val appContext = appContext.applicationContext
    private val _state = MutableStateFlow(LiveSessionUiState(networkOnline = network.isOnline()))
    val state: StateFlow<LiveSessionUiState> = _state.asStateFlow()

    private var utteranceQueue = newAsrChannel()
    private var translateQueue = newLlmChannel()
    private var asrWorker: Job? = null
    private var llmWorker: Job? = null
    private var collector: Job? = null
    private var timerJob: Job? = null
    private var networkJob: Job? = null
    private var diskPumpJob: Job? = null
    private var fileJob: Job? = null
    private var sessionStartedAt: Long = 0L
    private var recordedElapsedMs: Long = 0L
    private var segmentClockStart: Long = 0L
    private val contextWindow = ArrayDeque<ContextTurn>()
    private val contextLock = Any()

    private val failList = ArrayDeque<FailedWork>()
    private val failLock = Any()
    private val failIdGen = AtomicLong(1)
    private val segmentIdGen = AtomicLong(1)

    private val translationCache = TranslationCache(TranslationCache.DEFAULT_MAX_SIZE)
    private val diskQueue = UtteranceDiskQueue(this.appContext)
    private val ffmpegConverter = FfmpegAudioConverter()
    private val fileSegmenter = FileAudioSegmenter(appContext = this.appContext)

    private val asrInFlight = AtomicInteger(0)
    private val llmInFlight = AtomicInteger(0)
    private val asrQueued = AtomicInteger(0)
    private val llmQueued = AtomicInteger(0)
    private val dropped = AtomicInteger(0)

    private val stopOnce = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    /**
     * User requested pause: keep [SessionPhase.Paused] even while ASR/LLM backlog drains,
     * so [refreshPhase] / workers do not flip UI back to Processing.
     */
    private val userPaused = AtomicBoolean(false)
    /** When true, [stop] must not cancel [fileJob] (file job is driving auto-save). */
    private val suppressFileJobCancel = AtomicBoolean(false)

    @Volatile
    private var sessionTitle: String? = null
    private val titleRequested = AtomicBoolean(false)
    private var titleJob: Job? = null

    /** Held when Room save fails so user can retry. */
    private var pendingSave: PendingSave? = null

    init {
        networkJob = scope.launch {
            network.online.collect { online ->
                _state.update { it.copy(networkOnline = online) }
                if (online) {
                    // Kick disk → memory and auto-retry fail list.
                    pumpDiskIntoMemory()
                    autoRetryFailedIfOnline()
                }
            }
        }
    }

    fun setAudioSource(type: AudioSourceType) {
        if (_state.value.phase != SessionPhase.Idle) return
        audio.sourceType = type
        _state.update { it.copy(audioSource = type) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
    }

    fun clearTranslationCache() {
        translationCache.clear()
        android.util.Log.i(TAG, "translation cache cleared")
    }

    fun translationCacheSize(): Int = translationCache.size

    fun reportCaptureError(message: String) {
        userPaused.set(true)
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
        if (stopOnce.get() && current != SessionPhase.Paused) return
        if (draining.get()) return
        if (_state.value.audioSource == AudioSourceType.File) {
            _state.update {
                it.copy(error = "文件模式请点 Start 后选择音频文件")
            }
            return
        }

        userPaused.set(false)
        if (current == SessionPhase.Idle) {
            stopOnce.set(false)
            pendingSave = null
            sessionStartedAt = System.currentTimeMillis()
            recordedElapsedMs = 0L
            clearContextWindow()
            translationCache.clear()
            clearFailList()
            dropped.set(0)
            resetTitleState()
            audio.sourceType = _state.value.audioSource
            try {
                audio.beginSessionRecording(sessionStartedAt)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "beginSessionRecording failed", e)
            }
            _state.value = LiveSessionUiState(
                phase = SessionPhase.Recording,
                sessionStartedAt = sessionStartedAt,
                recordedElapsedMs = 0L,
                audioSource = audio.sourceType,
                overlayEnabled = _state.value.overlayEnabled,
                networkOnline = network.isOnline(),
                diskQueueDepth = diskQueue.size()
            )
        } else {
            stopOnce.set(false)
            _state.update {
                it.copy(
                    phase = SessionPhase.Recording,
                    error = null,
                    sessionStartedAt = sessionStartedAt,
                    networkOnline = network.isOnline()
                )
            }
        }

        segmentClockStart = System.currentTimeMillis()
        ensureWorkers()
        ensureTimer()
        ensureDiskPump()
        ensureUtteranceCollector()
        try {
            audio.start()
        } catch (e: Exception) {
            if (current == SessionPhase.Idle) {
                audio.discardSessionRecording()
            }
            userPaused.set(false)
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

    /**
     * Offline file import: copy URI → FFmpeg 16k mono WAV → energy VAD → same ASR/LLM queues.
     * Call only from [SessionPhase.Idle]. Does not open the microphone.
     */
    fun startFromFile(uri: Uri) {
        if (_state.value.phase != SessionPhase.Idle) return
        if (draining.get()) return

        fileJob?.cancel()
        fileJob = scope.launch {
            try {
                runFileImport(uri)
            } catch (e: CancellationException) {
                try {
                    audio.discardSessionRecording()
                } catch (_: Exception) {
                }
                _state.update {
                    it.copy(
                        phase = SessionPhase.Idle,
                        importStatus = null,
                        error = "已取消文件导入"
                    )
                }
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "startFromFile failed", e)
                try {
                    audio.discardSessionRecording()
                } catch (_: Exception) {
                }
                stopTimer()
                collector?.cancel()
                collector = null
                stopOnce.set(false)
                _state.update {
                    it.copy(
                        phase = SessionPhase.Idle,
                        importStatus = null,
                        error = e.message ?: "文件导入失败",
                        canRetry = false
                    )
                }
            }
        }
    }

    private suspend fun runFileImport(uri: Uri) {
        stopOnce.set(false)
        userPaused.set(false)
        pendingSave = null
        sessionStartedAt = System.currentTimeMillis()
        recordedElapsedMs = 0L
        clearContextWindow()
        translationCache.clear()
        clearFailList()
        dropped.set(0)
        resetTitleState()
        audio.sourceType = AudioSourceType.File

        _state.value = LiveSessionUiState(
            phase = SessionPhase.Processing,
            sessionStartedAt = sessionStartedAt,
            recordedElapsedMs = 0L,
            audioSource = AudioSourceType.File,
            overlayEnabled = _state.value.overlayEnabled,
            networkOnline = network.isOnline(),
            diskQueueDepth = diskQueue.size(),
            importStatus = "正在复制文件…"
        )

        val workDir = File(appContext.cacheDir, "import_audio").apply { mkdirs() }
        val inputCopy = File(workDir, "src_${sessionStartedAt}${guessExtension(uri)}")
        val outWav = File(workDir, "converted_${sessionStartedAt}.wav")
        copyUriToFile(uri, inputCopy)

        _state.update { it.copy(importStatus = "FFmpeg 转码中…") }
        val converted = ffmpegConverter.convertTo16kMonoWav(
            inputPath = inputCopy.absolutePath,
            outputWav = outWav
        ) { p ->
            _state.update {
                it.copy(importStatus = "FFmpeg 转码 ${(p * 100).toInt()}%")
            }
        }

        // Persist as session archive (history / export)
        val sessionPath = audio.installImportedSessionWav(converted.wavFile, sessionStartedAt)

        ensureWorkers()
        ensureTimer()
        ensureDiskPump()
        // File path feeds utterances directly (no SharedFlow collector)

        val settings = effectiveSettings(settingsRepo.settings.first())
        var uttered = 0
        _state.update { it.copy(importStatus = "Silero VAD 切句中…") }
        fileSegmenter.segment(File(sessionPath), settings) { progress, elapsedMs ->
            recordedElapsedMs = elapsedMs
            _state.update {
                it.copy(
                    recordedElapsedMs = elapsedMs,
                    importStatus = "切句 ${(progress * 100).toInt()}% · 已入队 $uttered 句"
                )
            }
        }.collect { utt ->
            uttered++
            enqueueUtterance(utt)
            _state.update {
                it.copy(
                    lastCutReason = utt.reason,
                    importStatus = "切句中 · 已入队 $uttered 句",
                    phase = SessionPhase.Processing
                )
            }
        }

        // Optional cleanup of raw copy (keep converted session wav)
        runCatching { inputCopy.delete() }
        if (outWav.absolutePath != sessionPath) {
            runCatching { outWav.delete() }
        }

        _state.update {
            it.copy(
                importStatus = "切句完成（$uttered 句），识别/翻译中…",
                phase = SessionPhase.Processing,
                recordedElapsedMs = recordedElapsedMs
            )
        }

        // Wait for ASR/LLM backlog then save (same as drain stop)
        val drained = withTimeoutOrNull(FILE_PIPELINE_TIMEOUT_MS) {
            while (isActive && pipelineBusy()) {
                delay(250)
                publishQueueDepths()
            }
            true
        }
        if (drained != true) {
            android.util.Log.w(TAG, "file pipeline wait timeout; forcing stop drain")
        }
        _state.update { it.copy(importStatus = "正在保存会话…") }
        // Auto-finish: stop() must not cancel this fileJob (we're inside it).
        if (!stopOnce.get()) {
            suppressFileJobCancel.set(true)
            try {
                stop(drain = true)
            } finally {
                suppressFileJobCancel.set(false)
            }
        }
    }

    private fun ensureUtteranceCollector() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            audio.utterances.collect { utt ->
                // File mode already stamps sentence-start offset in FileAudioSegmenter.
                // Live mic/internal: VAD emits at cut (end); convert to sentence-start.
                val stamped = if (audio.sourceType == AudioSourceType.File) {
                    utt
                } else {
                    val endMs = currentRecordedElapsedMs()
                    utt.copy(
                        offsetMs = UtteranceOffsets.startOffsetMs(
                            endElapsedMs = endMs,
                            pcmBytes = utt.pcm.size,
                            sampleRate = utt.sampleRate
                        )
                    )
                }
                enqueueUtterance(stamped)
            }
        }
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
        return if (ext.length in 2..8 && ext.all { it.isLetterOrDigit() || it == '.' }) ext else ".bin"
    }

    fun pause() {
        if (_state.value.audioSource == AudioSourceType.File) {
            // Offline file feed has no mic pause; user can Stop.
            return
        }
        userPaused.set(true)
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

    /**
     * @param drain If true, stop capture but wait for in-memory queues (up to [DRAIN_TIMEOUT_MS])
     *              before cancelling workers. Non-empty disk overflow is kept for the next session.
     */
    fun stop(drain: Boolean = true) {
        if (!stopOnce.compareAndSet(false, true)) return
        if (_state.value.phase == SessionPhase.Idle && pendingSave == null) {
            stopOnce.set(false)
            return
        }
        // Retry save path only
        if (_state.value.phase == SessionPhase.Idle && pendingSave != null) {
            stopOnce.set(false)
            return
        }

        userPaused.set(false)
        // Cancel in-flight FFmpeg / file segmentation unless file job itself is calling stop.
        if (!suppressFileJobCancel.get()) {
            fileJob?.cancel()
            fileJob = null
        }

        accumulateElapsed()
        if (audio.sourceType != AudioSourceType.File) {
            audio.stop(flush = true)
        } else {
            audio.stop(flush = false)
        }
        collector?.cancel()
        collector = null
        stopTimer()

        scope.launch {
            try {
                draining.set(drain)
                _state.update {
                    it.copy(
                        draining = drain,
                        phase = SessionPhase.Processing,
                        importStatus = if (drain) "正在排空队列并保存…" else "正在保存…"
                    )
                }

                if (drain) {
                    // Flush disk → memory while online
                    if (network.isOnline()) {
                        pumpDiskIntoMemory()
                    }
                    val drained = withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                        while (isActive && pipelineBusy()) {
                            delay(200)
                        }
                        true
                    }
                    if (drained != true) {
                        android.util.Log.w(TAG, "drain timeout; cancelling workers")
                    }
                }

                // Park leftover queue items before tearing down workers/channels.
                salvageOpenQueuesToDiskOrFail()

                asrWorker?.cancel()
                asrWorker = null
                llmWorker?.cancel()
                llmWorker = null
                utteranceQueue.close()
                translateQueue.close()
                utteranceQueue = newAsrChannel()
                translateQueue = newLlmChannel()
                asrInFlight.set(0)
                llmInFlight.set(0)
                asrQueued.set(0)
                llmQueued.set(0)
                draining.set(false)

                // Wait briefly for title
                withTimeoutOrNull(TITLE_WAIT_MS) {
                    titleJob?.join()
                }
                val snapshot = _state.value
                val title = sessionTitle?.takeIf { it.isNotBlank() }
                    ?: snapshot.sessionTitle?.takeIf { it.isNotBlank() }
                val started = sessionStartedAt
                val audioPath = try {
                    audio.finishSessionRecording()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "finishSessionRecording failed", e)
                    null
                }

                val segs = snapshot.segments
                if (segs.isNotEmpty() || audioPath != null) {
                    try {
                        history.saveSession(
                            startedAt = started,
                            endedAt = System.currentTimeMillis(),
                            segments = segs,
                            audioPath = audioPath,
                            title = title
                        )
                        pendingSave = null
                        finalizeIdle(snapshot.overlayEnabled, clearFail = true)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "saveSession failed", e)
                        pendingSave = PendingSave(started, segs, audioPath, title, snapshot.overlayEnabled)
                        val failCount = synchronized(failLock) { failList.size }
                        _state.update {
                            it.copy(
                                phase = SessionPhase.Idle,
                                draining = false,
                                saveError = e.message ?: "保存历史失败",
                                canRetrySave = true,
                                error = "会话文稿在内存中，点「重试保存」写入历史",
                                networkOnline = network.isOnline(),
                                asrQueueDepth = 0,
                                llmQueueDepth = 0,
                                diskQueueDepth = diskQueue.size(),
                                failedCount = failCount,
                                sessionStartedAt = started,
                                segments = segs,
                                cumulativeEn = snapshot.cumulativeEn,
                                cumulativeZh = snapshot.cumulativeZh,
                                recordedElapsedMs = recordedElapsedMs,
                                audioSource = audio.sourceType,
                                overlayEnabled = snapshot.overlayEnabled
                            )
                        }
                        sessionStartedAt = started
                    }
                } else {
                    audio.discardSessionRecording()
                    finalizeIdle(snapshot.overlayEnabled, clearFail = true)
                }
            } finally {
                stopOnce.set(false)
            }
        }
    }

    fun retrySave() {
        val p = pendingSave ?: return
        scope.launch {
            try {
                history.saveSession(
                    startedAt = p.startedAt,
                    endedAt = System.currentTimeMillis(),
                    segments = p.segments,
                    audioPath = p.audioPath,
                    title = p.title
                )
                pendingSave = null
                finalizeIdle(p.overlayEnabled, clearFail = true)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saveError = e.message ?: "保存失败",
                        canRetrySave = true,
                        error = "重试保存仍失败：${e.message}"
                    )
                }
            }
        }
    }

    fun retryLastFailed() = retryFailed(limit = 1)

    fun retryAllFailed() = retryFailed(limit = Int.MAX_VALUE)

    fun dismissFailures() {
        clearFailList()
        publishFailState(error = null)
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

    // --- enqueue / disk ---

    private suspend fun enqueueUtterance(utt: UtteranceAudio) {
        // Prefer memory queue; overflow → disk.
        asrQueued.incrementAndGet()
        val result = utteranceQueue.trySend(utt)
        if (result.isSuccess) {
            publishQueueDepths()
            return
        }
        asrQueued.decrementAndGet()
        try {
            diskQueue.enqueue(utt)
            publishQueueDepths()
            _state.update {
                it.copy(error = "处理队列已满，句子已缓存到本地（磁盘 ${diskQueue.size()}）")
            }
        } catch (e: Exception) {
            dropped.incrementAndGet()
            _state.update {
                it.copy(
                    droppedUtterances = dropped.get(),
                    error = "队列与磁盘均失败，丢弃 1 句：${e.message}"
                )
            }
        }
    }

    private fun pumpDiskIntoMemory() {
        if (!network.isOnline()) return
        var n = 0
        while (asrQueued.get() < ASR_QUEUE_CAP) {
            val u = diskQueue.poll() ?: break
            asrQueued.incrementAndGet()
            val ok = utteranceQueue.trySend(u)
            if (!ok.isSuccess) {
                asrQueued.decrementAndGet()
                try {
                    diskQueue.enqueue(u)
                } catch (_: Exception) {
                    dropped.incrementAndGet()
                }
                break
            }
            n++
        }
        if (n > 0) {
            ensureWorkers()
            publishQueueDepths()
            android.util.Log.i(TAG, "pumped $n utterances from disk")
        } else {
            publishQueueDepths()
        }
    }

    private fun ensureDiskPump() {
        if (diskPumpJob?.isActive == true) return
        diskPumpJob = scope.launch {
            while (isActive) {
                delay(1_500)
                val phase = _state.value.phase
                if (phase == SessionPhase.Recording ||
                    phase == SessionPhase.Processing ||
                    phase == SessionPhase.Paused ||
                    draining.get()
                ) {
                    if (network.isOnline()) pumpDiskIntoMemory()
                    else publishQueueDepths()
                }
            }
        }
    }

    private fun retryFailed(limit: Int) {
        if (limit <= 0) return
        ensureWorkers()
        val batch = ArrayList<FailedWork>()
        synchronized(failLock) {
            if (failList.isEmpty()) return
            repeat(minOf(limit, failList.size)) {
                failList.removeFirstOrNull()?.let { batch.add(it) }
            }
        }
        if (batch.isEmpty()) return
        publishFailState(error = null)
        scope.launch {
            for (item in batch) {
                when (item) {
                    is FailedWork.Asr -> {
                        asrQueued.incrementAndGet()
                        if (!utteranceQueue.trySend(item.utt).isSuccess) {
                            asrQueued.decrementAndGet()
                            try {
                                diskQueue.enqueue(item.utt)
                            } catch (_: Exception) {
                                pushFail(item)
                            }
                        }
                    }
                    is FailedWork.Llm -> {
                        llmQueued.incrementAndGet()
                        val job = PendingTranslate(
                            utt = item.utt,
                            en = item.en,
                            windowSize = item.windowSize,
                            segmentLocalId = item.segmentLocalId
                        )
                        if (!translateQueue.trySend(job).isSuccess) {
                            llmQueued.decrementAndGet()
                            pushFail(item)
                        }
                    }
                }
            }
            publishQueueDepths()
        }
    }

    private fun autoRetryFailedIfOnline() {
        if (!network.isOnline()) return
        val n = synchronized(failLock) { failList.size }
        if (n == 0) return
        val phase = _state.value.phase
        if (phase == SessionPhase.Idle && !draining.get()) return
        retryFailed(limit = n)
    }

    // --- workers ---

    private fun ensureWorkers() {
        if (llmWorker?.isActive != true) {
            llmWorker = scope.launch {
                for (job in translateQueue) {
                    llmQueued.decrementAndGet()
                    publishQueueDepths()
                    processTranslateWithRetry(job)
                }
            }
        }
        if (asrWorker?.isActive != true) {
            asrWorker = scope.launch {
                for (utt in utteranceQueue) {
                    asrQueued.decrementAndGet()
                    publishQueueDepths()
                    processAsrWithRetry(utt)
                }
            }
        }
    }

    /**
     * True while workers still have processable work.
     * Disk overflow is only "busy" when online (can be pumped); offline disk is preserved
     * across stop and must not block drain forever or be wiped on finalize.
     */
    private fun pipelineBusy(): Boolean =
        asrInFlight.get() > 0 ||
            llmInFlight.get() > 0 ||
            asrQueued.get() > 0 ||
            llmQueued.get() > 0 ||
            (network.isOnline() && diskQueue.size() > 0)

    private fun refreshPhase() {
        if (draining.get()) {
            _state.update { it.copy(phase = SessionPhase.Processing, draining = true) }
            return
        }
        // User pause wins over backlog so notification/UI stay on Pause while queues drain.
        if (userPaused.get()) {
            _state.update { it.copy(phase = SessionPhase.Paused, draining = false) }
            return
        }
        val phase = when {
            pipelineBusy() -> SessionPhase.Processing
            audio.isRecording -> SessionPhase.Recording
            _state.value.phase == SessionPhase.Idle -> SessionPhase.Idle
            else -> SessionPhase.Paused
        }
        _state.update { it.copy(phase = phase, draining = false) }
    }

    /** Prefer Paused while [userPaused]; used when workers start a job. */
    private fun phaseForWork(): SessionPhase =
        if (userPaused.get()) SessionPhase.Paused else SessionPhase.Processing

    private suspend fun awaitOnline(tag: String) {
        if (network.isOnline()) return
        _state.update {
            it.copy(error = "离线中，等待网络恢复后再$tag…", networkOnline = false)
        }
        while (!network.isOnline()) {
            delay(500)
            network.refresh()
        }
        _state.update { it.copy(networkOnline = true, error = null) }
    }

    private suspend fun processAsrWithRetry(utt: UtteranceAudio) {
        asrInFlight.incrementAndGet()
        _state.update {
            it.copy(
                phase = phaseForWork(),
                partialEn = "",
                lastCutReason = utt.reason,
                error = null
            )
        }
        var attempt = 0
        var lastError: Exception? = null
        val maxAttempts = runCatching {
            settingsRepo.settings.first().maxNetworkAttempts.coerceIn(1, 10)
        }.getOrDefault(MAX_ATTEMPTS)
        try {
            while (attempt < maxAttempts) {
                try {
                    awaitOnline("识别")
                    val settings = effectiveSettings(settingsRepo.settings.first())
                    val en = runAsr(utt, settings)
                    if (en.isBlank()) {
                        // Soft-fail: park for retry instead of silent drop
                        pushFail(
                            FailedWork.Asr(
                                id = failIdGen.getAndIncrement(),
                                utt = utt,
                                message = "ASR 返回空文本（可能噪声/过短）"
                            )
                        )
                        _state.update { it.copy(partialEn = "") }
                        return
                    }
                    // ASR-first: commit EN immediately; LLM can fail independently.
                    val localId = commitSourceOnly(utt, en)
                    if (settings.asrOnlyMode) {
                        // Recognition only — no translation request.
                        finishAsrOnly(localId, en)
                        return
                    }
                    val window = settings.contextWindowSize
                    llmQueued.incrementAndGet()
                    val sent = translateQueue.trySend(
                        PendingTranslate(utt, en, window, localId)
                    )
                    if (!sent.isSuccess) {
                        llmQueued.decrementAndGet()
                        pushFail(
                            FailedWork.Llm(
                                id = failIdGen.getAndIncrement(),
                                utt = utt,
                                en = en,
                                windowSize = window,
                                segmentLocalId = localId,
                                message = "翻译队列已满"
                            )
                        )
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RetryableException) {
                    lastError = e
                    attempt++
                    if (attempt >= maxAttempts) break
                    delay(500L * attempt * attempt)
                } catch (e: Exception) {
                    lastError = e
                    break
                }
            }
            val msg = NetworkErrors.userMessage(lastError ?: Exception("ASR failed"), "ASR")
            // Keep PCM only in fail list (retry) — avoid double-processing via disk.
            pushFail(
                FailedWork.Asr(
                    id = failIdGen.getAndIncrement(),
                    utt = utt,
                    message = msg
                )
            )
        } finally {
            asrInFlight.decrementAndGet()
            publishQueueDepths()
            refreshPhase()
        }
    }

    private suspend fun processTranslateWithRetry(job: PendingTranslate) {
        llmInFlight.incrementAndGet()
        _state.update {
            it.copy(
                phase = phaseForWork(),
                partialZh = "",
                error = null
            )
        }
        var attempt = 0
        var lastError: Exception? = null
        var bestPartial = ""
        val maxAttempts = runCatching {
            settingsRepo.settings.first().maxNetworkAttempts.coerceIn(1, 10)
        }.getOrDefault(MAX_ATTEMPTS)
        try {
            while (attempt < maxAttempts) {
                try {
                    awaitOnline("翻译")
                    val settings = effectiveSettings(settingsRepo.settings.first())
                    bestPartial = ""
                    runTranslate(job, settings) { partial -> bestPartial = partial }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RetryableException) {
                    // Partial adopt: enough Chinese already streamed
                    if (bestPartial.trim().length >= MIN_PARTIAL_ZH) {
                        android.util.Log.i(TAG, "adopting partial ZH len=${bestPartial.length}")
                        finishTranslation(job, job.en, bestPartial.trim(), incomplete = true)
                        return
                    }
                    lastError = e
                    attempt++
                    if (attempt >= maxAttempts) break
                    delay(500L * attempt * attempt)
                } catch (e: Exception) {
                    if (bestPartial.trim().length >= MIN_PARTIAL_ZH) {
                        finishTranslation(job, job.en, bestPartial.trim(), incomplete = true)
                        return
                    }
                    lastError = e
                    break
                }
            }
            val msg = NetworkErrors.userMessage(lastError ?: Exception("Translation failed"), "翻译")
            markSegmentFailed(job.segmentLocalId, msg)
            pushFail(
                FailedWork.Llm(
                    id = failIdGen.getAndIncrement(),
                    utt = job.utt,
                    en = job.en,
                    windowSize = job.windowSize,
                    segmentLocalId = job.segmentLocalId,
                    message = msg
                )
            )
        } finally {
            llmInFlight.decrementAndGet()
            publishQueueDepths()
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

    private suspend fun runTranslate(
        job: PendingTranslate,
        settings: UserSettings,
        onPartial: (String) -> Unit
    ) {
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

        val cachedZh = cacheKey?.let { translationCache.get(it) }
        if (cachedZh != null) {
            _state.update { it.copy(partialZh = cachedZh) }
            finishTranslation(job, en, cachedZh, incomplete = false)
            return
        }

        if (settings.llmApiKey.isBlank()) {
            throw Exception("LLM API Key is empty. Configure it in Settings.")
        }

        var zh = ""
        // Weak-net: shrink context when queues are deep
        val windowSize = if (weakNetworkPressure()) {
            minOf(settings.contextWindowSize, 1)
        } else {
            settings.contextWindowSize
        }
        val ctx = snapshotContextWindow(windowSize.coerceAtLeast(0))
        llm.translateStream(
            en,
            ctx,
            settings.toLlmConfig()
        ).collect { ev ->
            when (ev) {
                is LlmStreamEvent.Delta -> {
                    zh += ev.text
                    val display = LlmClient.stripThinkingArtifacts(zh)
                    onPartial(display)
                    _state.update { it.copy(partialZh = display) }
                }
                is LlmStreamEvent.Completed -> {
                    if (ev.fullText.isNotEmpty()) {
                        zh = LlmClient.stripThinkingArtifacts(ev.fullText)
                        onPartial(zh)
                        _state.update { it.copy(partialZh = zh) }
                    } else {
                        zh = LlmClient.stripThinkingArtifacts(zh)
                    }
                }
                is LlmStreamEvent.Error -> {
                    if (ev.retryable) throw RetryableException(ev.throwable)
                    else throw ev.throwable
                }
            }
        }

        zh = LlmClient.stripThinkingArtifacts(zh)
        if (zh.isBlank()) {
            // Align with offline SoftZh.Incomplete: empty translation is not "done".
            finishTranslation(job, en, "", incomplete = true)
            pushFail(
                FailedWork.Llm(
                    id = failIdGen.getAndIncrement(),
                    utt = job.utt,
                    en = en,
                    windowSize = job.windowSize,
                    segmentLocalId = job.segmentLocalId,
                    message = "翻译结果为空"
                )
            )
            return
        }
        if (cacheKey != null) {
            translationCache.put(cacheKey, zh)
        }
        finishTranslation(job, en, zh, incomplete = false)
    }

    /** Mark segment complete without translation (ASR-only mode). */
    private fun finishAsrOnly(segmentLocalId: Long, en: String) {
        pushContextTurn(ContextTurn(en, ""), maxSize = 8)

        _state.update { st ->
            val segs = st.segments.map { s ->
                if (s.localId == segmentLocalId) {
                    s.copy(
                        translation = "",
                        incomplete = false,
                        timestampMs = System.currentTimeMillis()
                    )
                } else {
                    s
                }
            }
            st.copy(
                partialEn = if (st.partialEn == en) "" else st.partialEn,
                partialZh = "",
                segments = segs,
                error = null
            )
        }
        // Session title uses bilingual sample — skip in ASR-only.
    }

    /** Commit EN immediately (incomplete until LLM fills ZH). */
    private fun commitSourceOnly(utt: UtteranceAudio, en: String): Long {
        val localId = segmentIdGen.getAndIncrement()
        val offset = utt.offsetMs.coerceAtLeast(0L)
        val seg = TranscriptSegment(
            source = en,
            translation = "",
            cutReason = utt.reason,
            incomplete = true,
            timestampMs = System.currentTimeMillis(),
            offsetMs = offset,
            localId = localId
        )
        _state.update {
            it.copy(
                // Commit final EN into cumulative only. Keep partialEn empty so UI
                // buildDisplay(cumulative, partial) does not show the same line twice.
                cumulativeEn = appendBlock(it.cumulativeEn, en),
                partialEn = "",
                segments = it.segments + seg,
                error = null
            )
        }
        return localId
    }

    private fun finishTranslation(
        job: PendingTranslate,
        en: String,
        zh: String,
        incomplete: Boolean
    ) {
        pushContextTurn(ContextTurn(en, zh), maxSize = job.windowSize.coerceAtLeast(0))

        var turnCount = 0
        _state.update { st ->
            val segs = st.segments.map { s ->
                if (s.localId == job.segmentLocalId) {
                    s.copy(
                        translation = zh,
                        incomplete = incomplete,
                        timestampMs = System.currentTimeMillis()
                    )
                } else {
                    s
                }
            }
            turnCount = segs.count { !it.incomplete || it.translation.isNotBlank() }
            val zhBlock = if (zh.isBlank()) st.cumulativeZh else appendBlock(st.cumulativeZh, zh)
            st.copy(
                cumulativeZh = zhBlock,
                partialEn = if (st.partialEn == en) "" else st.partialEn,
                partialZh = "",
                segments = segs,
                error = null
            )
        }
        maybeRequestSessionTitle(turnCount)
    }

    private fun markSegmentFailed(localId: Long, message: String) {
        _state.update { st ->
            st.copy(
                segments = st.segments.map { s ->
                    if (s.localId == localId) {
                        s.copy(
                            translation = s.translation.ifBlank { "" },
                            incomplete = true
                        )
                    } else s
                },
                error = message
            )
        }
    }

    private fun maybeRequestSessionTitle(turnCount: Int) {
        if (titleRequested.get()) return
        titleJob = scope.launch {
            try {
                val settings = settingsRepo.settings.first()
                val threshold = settings.titleTurnThreshold.coerceIn(1, 50)
                if (turnCount < threshold) return@launch
                if (!titleRequested.compareAndSet(false, true)) return@launch
                val segsSnapshot = _state.value.segments
                    .filter { it.source.isNotBlank() }
                    .take(threshold)
                if (segsSnapshot.isEmpty()) {
                    titleRequested.set(false)
                    return@launch
                }
                if (!network.isOnline()) {
                    titleRequested.set(false)
                    return@launch
                }
                if (settings.llmApiKey.isBlank()) {
                    titleRequested.set(false)
                    return@launch
                }
                val title = withTimeout(TITLE_CALL_TIMEOUT_MS) {
                    llm.summarizeSessionTitle(segsSnapshot, settings.toLlmConfig())
                }
                if (title.isNotBlank()) {
                    sessionTitle = title
                    _state.update { it.copy(sessionTitle = title) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "session title failed", e)
            }
        }
    }

    private fun effectiveSettings(base: UserSettings): UserSettings {
        if (!weakNetworkPressure()) return base
        // Prefer lighter payloads under backlog / offline recovery
        return base.copy(contextWindowSize = minOf(base.contextWindowSize, 1))
    }

    private fun weakNetworkPressure(): Boolean {
        if (!network.isOnline()) return true
        return asrQueued.get() + llmQueued.get() + diskQueue.size() >= WEAK_NET_THRESHOLD
    }

    private fun pushFail(item: FailedWork) {
        synchronized(failLock) {
            while (failList.size >= MAX_FAIL_LIST) failList.removeFirst()
            failList.addLast(item)
        }
        publishFailState(error = item.message)
    }

    private fun clearFailList() {
        synchronized(failLock) {
            failList.clear()
        }
    }

    private fun publishFailState(error: String?) {
        val (count, preview, canRetry) = synchronized(failLock) {
            val p = failList.lastOrNull()?.let {
                "${it.stage.name}: ${it.message.take(80)}"
            }
            Triple(failList.size, p, failList.isNotEmpty())
        }
        _state.update {
            it.copy(
                failedCount = count,
                failedPreview = preview,
                canRetry = canRetry,
                error = error ?: it.error
            )
        }
    }

    private fun publishQueueDepths() {
        _state.update {
            it.copy(
                asrQueueDepth = asrQueued.get().coerceAtLeast(0),
                llmQueueDepth = llmQueued.get().coerceAtLeast(0),
                diskQueueDepth = diskQueue.size(),
                droppedUtterances = dropped.get(),
                networkOnline = network.isOnline()
            )
        }
    }

    /**
     * Move remaining in-memory ASR/LLM work off closed channels so stop does not drop it.
     * ASR → disk overflow; LLM → fail list (segments already ASR-committed as incomplete).
     */
    private fun salvageOpenQueuesToDiskOrFail() {
        var asrSalvaged = 0
        while (true) {
            val result = utteranceQueue.tryReceive()
            if (result.isFailure) break
            val utt = result.getOrNull() ?: break
            asrQueued.decrementAndGet()
            try {
                diskQueue.enqueue(utt)
                asrSalvaged++
            } catch (e: Exception) {
                dropped.incrementAndGet()
                android.util.Log.w(TAG, "salvage ASR→disk failed", e)
            }
        }
        var llmSalvaged = 0
        while (true) {
            val result = translateQueue.tryReceive()
            if (result.isFailure) break
            val job = result.getOrNull() ?: break
            llmQueued.decrementAndGet()
            pushFail(
                FailedWork.Llm(
                    id = failIdGen.getAndIncrement(),
                    utt = job.utt,
                    en = job.en,
                    windowSize = job.windowSize,
                    segmentLocalId = job.segmentLocalId,
                    message = "会话结束时翻译未完成"
                )
            )
            llmSalvaged++
        }
        if (asrSalvaged > 0 || llmSalvaged > 0) {
            android.util.Log.i(
                TAG,
                "salvaged on stop: asr→disk=$asrSalvaged llm→fail=$llmSalvaged disk=${diskQueue.size()}"
            )
            publishQueueDepths()
        }
    }

    private fun snapshotContextWindow(takeLast: Int): List<ContextTurn> =
        synchronized(contextLock) {
            if (takeLast <= 0) emptyList()
            else contextWindow.toList().takeLast(takeLast)
        }

    private fun pushContextTurn(turn: ContextTurn, maxSize: Int) {
        synchronized(contextLock) {
            contextWindow.addLast(turn)
            val cap = maxSize.coerceAtLeast(0)
            while (contextWindow.size > cap) {
                contextWindow.removeFirst()
            }
        }
    }

    private fun clearContextWindow() {
        synchronized(contextLock) {
            contextWindow.clear()
        }
    }

    private fun finalizeIdle(overlayEnabled: Boolean, clearFail: Boolean) {
        userPaused.set(false)
        val diskDepth = try {
            diskQueue.size()
        } catch (_: Exception) {
            0
        }
        // Never wipe non-empty disk overflow — next session / pump recovers offline backlog.
        // (Previously clearFail always diskQueue.clear(), contradicting stop KDoc.)
        if (clearFail && diskDepth == 0) {
            clearFailList()
        } else if (clearFail && diskDepth > 0) {
            // Keep fail list too when PCM is parked offline so user can retry after reconnect.
            android.util.Log.i(TAG, "finalizeIdle: keeping disk queue ($diskDepth) and fail list")
        }
        clearContextWindow()
        translationCache.clear()
        resetTitleState()
        sessionStartedAt = 0L
        recordedElapsedMs = 0L
        val (failCount, failPreview, canRetry) = synchronized(failLock) {
            Triple(
                failList.size,
                failList.lastOrNull()?.message,
                failList.isNotEmpty()
            )
        }
        _state.value = LiveSessionUiState(
            phase = SessionPhase.Idle,
            audioSource = audio.sourceType,
            overlayEnabled = overlayEnabled,
            networkOnline = network.isOnline(),
            diskQueueDepth = diskDepth,
            failedCount = failCount,
            canRetry = canRetry,
            failedPreview = failPreview
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

    private fun resetTitleState() {
        titleJob?.cancel()
        titleJob = null
        titleRequested.set(false)
        sessionTitle = null
    }

    private fun appendBlock(prev: String, next: String): String =
        if (prev.isEmpty()) next else prev + "\n" + next

    private fun newAsrChannel() = Channel<UtteranceAudio>(ASR_QUEUE_CAP)
    private fun newLlmChannel() = Channel<PendingTranslate>(LLM_QUEUE_CAP)

    private data class PendingTranslate(
        val utt: UtteranceAudio,
        val en: String,
        val windowSize: Int,
        val segmentLocalId: Long
    )

    private data class PendingSave(
        val startedAt: Long,
        val segments: List<TranscriptSegment>,
        val audioPath: String?,
        val title: String?,
        val overlayEnabled: Boolean
    )

    private class RetryableException(cause: Throwable) : Exception(cause.message, cause)

    companion object {
        private const val TAG = "SessionOrchestrator"
        private const val TITLE_WAIT_MS = 8_000L
        private const val TITLE_CALL_TIMEOUT_MS = 10_000L
        private const val DRAIN_TIMEOUT_MS = 45_000L
        /** Max wait after file VAD for ASR/LLM to finish before force-save. */
        private const val FILE_PIPELINE_TIMEOUT_MS = 30 * 60_000L
        private const val MAX_ATTEMPTS = 3
        private const val ASR_QUEUE_CAP = 12
        private const val LLM_QUEUE_CAP = 12
        private const val MAX_FAIL_LIST = 40
        private const val MIN_PARTIAL_ZH = 4
        private const val WEAK_NET_THRESHOLD = 6
    }
}
