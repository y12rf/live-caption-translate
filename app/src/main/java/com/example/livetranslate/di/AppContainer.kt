package com.example.livetranslate.di

import android.content.Context
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.domain.SessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(appContext)
    val historyRepository = HistoryRepository(appContext)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val asrClient = AsrClient(okHttp)
    val llmClient = LlmClient(okHttp)

    val sessionController = SessionController(
        appContext = appContext,
        appScope = appScope,
        asr = asrClient,
        llm = llmClient,
        settingsRepo = settingsRepository,
        history = historyRepository
    )

    init {
        appScope.launch {
            settingsRepository.settings.collect {
                sessionController.updateCachedSettings(it)
            }
        }
    }
}
