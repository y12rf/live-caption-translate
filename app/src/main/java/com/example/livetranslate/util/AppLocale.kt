package com.example.livetranslate.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App UI language: English (default) or Chinese.
 * Stored as [UserSettings.uiLanguage] codes: `en` | `zh`.
 */
object AppLocale {
    const val EN = "en"
    const val ZH = "zh"

    fun normalize(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            ZH, "zh-cn", "zh-hans", "chinese", "中文" -> ZH
            else -> EN
        }

    /** Apply process-wide UI locales (Compose + resources). Requires AppCompatActivity. */
    fun apply(languageTag: String) {
        val tag = normalize(languageTag)
        val locales = LocaleListCompat.forLanguageTags(tag)
        // Always set — even if equal — so first launch and Save are reliable.
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun currentApplicationTag(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return normalize(tags.substringBefore(',').ifBlank { EN })
    }
}
