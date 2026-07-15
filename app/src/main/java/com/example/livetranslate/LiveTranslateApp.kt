package com.example.livetranslate

import android.app.Application
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.util.AppLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LiveTranslateApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Apply saved UI language before first Activity (default en).
        runBlocking {
            val lang = container.settingsRepository.settings.first().uiLanguage
            AppLocale.apply(lang)
        }
        container.appScope.launch {
            var last: String? = null
            container.settingsRepository.settings.collect { s ->
                val lang = AppLocale.normalize(s.uiLanguage)
                if (lang != last) {
                    last = lang
                    AppLocale.apply(lang)
                }
            }
        }
    }
}
