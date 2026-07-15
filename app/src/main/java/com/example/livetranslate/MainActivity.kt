package com.example.livetranslate

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.livetranslate.ui.navigation.AppNav
import com.example.livetranslate.ui.theme.LiveTranslateTheme

/**
 * AppCompatActivity is required so [androidx.appcompat.app.AppCompatDelegate]
 * application locales actually recreate UI and refresh string resources.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
