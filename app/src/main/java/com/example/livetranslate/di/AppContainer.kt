package com.example.livetranslate.di

import android.content.Context
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.network.NetworkMonitor
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.domain.OfflineReprocessPipeline
import com.example.livetranslate.domain.SessionController
import com.example.livetranslate.domain.model.SessionPhase
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    private val uncaught = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught in appScope", t)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught)

    val settingsRepository = SettingsRepository(appContext)
    val historyRepository = HistoryRepository(appContext)
    val networkMonitor = NetworkMonitor(appContext).also { it.start() }

    /**
     * Streaming client:
     * - readTimeout = SSE idle gap (no bytes) → fails instead of hanging forever
     * - callTimeout = hard upper bound for one ASR/LLM attempt
     * - writeTimeout = weak-net upload of WAV / base64
     */
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val asrClient = AsrClient(okHttp)
    val llmClient = LlmClient(okHttp)

    val sessionController = SessionController(
        appContext = appContext,
        appScope = appScope,
        asr = asrClient,
        llm = llmClient,
        settingsRepo = settingsRepository,
        history = historyRepository,
        network = networkMonitor
    )

    val reprocessPipeline = OfflineReprocessPipeline(
        appContext = appContext,
        scope = appScope,
        asr = asrClient,
        llm = llmClient,
        settingsRepo = settingsRepository,
        history = historyRepository,
        network = networkMonitor,
        isLiveSessionBusy = {
            val phase = sessionController.state.value.phase
            phase != SessionPhase.Idle
        }
    )

    init {
        appScope.launch {
            settingsRepository.settings.collect {
                sessionController.updateCachedSettings(it)
            }
        }
        sessionController.observeCaptureErrors()
    }

    companion object {
        private const val TAG = "LiveTranslate"
    }
}
