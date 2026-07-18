package com.example.livetranslate

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.livetranslate.ui.navigation.AppNav
import com.example.livetranslate.ui.theme.LiveTranslateTheme
import com.example.livetranslate.util.AppLocale

/**
 * AppCompatActivity is required so [androidx.appcompat.app.AppCompatDelegate]
 * application locales actually recreate UI and refresh string resources.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Belt-and-suspenders: never let an empty app-locale list follow system Chinese
        // while Settings still shows the English default.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppLocale.apply(AppLocale.EN)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LiveTranslateApp
        setContent {
            LiveTranslateTheme {
                AppNav(app.container)
            }
        }
    }
}
