package com.example.livetranslate

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
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

        // Settings default is English, but an empty AppCompat locale list follows the
        // *system* language (often Chinese). Force the product default before any UI.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppLocale.apply(AppLocale.EN)
        }

        container = AppContainer(this)

        // Align AppCompat + DataStore (seed missing key so option and runtime match).
        runBlocking {
            val lang = container.settingsRepository.ensureUiLanguageAndGet()
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
