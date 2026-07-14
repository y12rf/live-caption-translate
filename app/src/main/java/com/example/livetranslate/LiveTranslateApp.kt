package com.example.livetranslate

import android.app.Application
import com.example.livetranslate.di.AppContainer

class LiveTranslateApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
