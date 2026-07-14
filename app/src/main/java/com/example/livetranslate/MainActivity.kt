package com.example.livetranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.livetranslate.ui.navigation.AppNav
import com.example.livetranslate.ui.theme.LiveTranslateTheme

class MainActivity : ComponentActivity() {
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
