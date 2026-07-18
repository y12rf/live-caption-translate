package com.example.livetranslate.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App UI language: English (default) or Chinese.
 * Stored as [UserSettings.uiLanguage] codes: `en` | `zh`.
 *
 * Important: an empty [AppCompatDelegate.getApplicationLocales] list means “follow the
 * system language”, not English. Product default English must be applied explicitly
 * (see [LiveTranslateApp]).
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
        // Empty list = system locale, not product default — callers should treat empty
        // as “unset” rather than English unless they intend system-follow.
        return normalize(tags.substringBefore(',').ifBlank { EN })
    }
}
