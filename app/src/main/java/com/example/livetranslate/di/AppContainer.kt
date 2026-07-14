package com.example.livetranslate.di

import android.content.Context
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)
    val historyRepository = HistoryRepository(appContext)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 0 = no read timeout so long-lived SSE streams are not cut mid-flight
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val asrClient = AsrClient(okHttp)
    val llmClient = LlmClient(okHttp)
}
