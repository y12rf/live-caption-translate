package com.example.livetranslate.data.settings

/**
 * Soft validation for settings draft. Returns human-readable warnings (not hard blocks).
 * Keys are English; UI may map or display as-is (EN default app).
 */
object SettingsValidation {

    data class Result(
        val warnings: List<String> = emptyList(),
        /** Clamped / normalized copy safe to persist. */
        val sanitized: UserSettings
    )

    fun validate(raw: UserSettings): Result {
        val w = ArrayList<String>()
        var s = raw

        // --- VAD ---
        if (s.silenceMs < 100 || s.silenceMs > 5_000) {
            w += "Silence ms should be 100–5000 (got ${s.silenceMs})"
        }
        if (s.maxUtteranceMs < 500 || s.maxUtteranceMs > 60_000) {
            w += "Max utterance ms should be 500–60000 (got ${s.maxUtteranceMs})"
        }
        if (s.minUtteranceMs < 0 || s.minUtteranceMs > 10_000) {
            w += "Min utterance ms should be 0–10000 (got ${s.minUtteranceMs})"
        }
        if (s.minUtteranceMs > s.maxUtteranceMs) {
            w += "Min utterance ms (${s.minUtteranceMs}) is greater than max (${s.maxUtteranceMs})"
        }
        val mode = SileroVadMode.fromStorage(s.sileroVadMode)
        if (s.sileroVadMode.isNotBlank() &&
            !SileroVadMode.entries.any { it.name.equals(s.sileroVadMode.trim(), ignoreCase = true) }
        ) {
            w += "Silero VAD mode unknown (got ${s.sileroVadMode}); using ${mode.name}"
        }
        if (s.contextWindowSize < 0 || s.contextWindowSize > 20) {
            w += "Context window N should be 0–20 (got ${s.contextWindowSize})"
        }

        // --- Offline pipeline ---
        if (s.offlineVadBatchSize < 1 || s.offlineVadBatchSize > 200) {
            w += "Offline VAD batch size should be 1–200 (got ${s.offlineVadBatchSize})"
        }
        if (s.titleTurnThreshold < 1 || s.titleTurnThreshold > 50) {
            w += "Title turn threshold should be 1–50 (got ${s.titleTurnThreshold})"
        }
        if (s.maxNetworkAttempts < 1 || s.maxNetworkAttempts > 10) {
            w += "Network retry attempts should be 1–10 (got ${s.maxNetworkAttempts})"
        }
        if (s.translationCacheMax < 0 || s.translationCacheMax > 500) {
            w += "Translation cache max should be 0–500 (got ${s.translationCacheMax})"
        }

        // --- Overlay / captions ---
        if (s.overlayMaxWidthDp < 120 || s.overlayMaxWidthDp > 2000) {
            w += "Overlay max width dp should be 120–2000"
        }
        if (s.overlayMaxHeightDp < 60 || s.overlayMaxHeightDp > 800) {
            w += "Overlay max height dp should be 60–800"
        }
        if (s.overlayAlphaPercent < 0 || s.overlayAlphaPercent > 100) {
            w += "Overlay alpha % should be 0–100"
        }
        if (s.overlayFontSizeSp < 10 || s.overlayFontSizeSp > 48) {
            w += "Overlay font size sp should be 10–48"
        }
        if (s.liveFontSizeSp < 10 || s.liveFontSizeSp > 48) {
            w += "Live font size sp should be 10–48"
        }
        val badColor = listOf(
            s.overlayBgColor to "overlay background",
            s.overlayEnTextColor to "source text",
            s.overlayZhTextColor to "translation text"
        ).any { (hex, _) ->
            val t = hex.trim().removePrefix("#")
            t.isEmpty() || (t.length != 3 && t.length != 6 && t.length != 8) ||
                t.any { c -> c !in "0123456789abcdefABCDEF" }
        }
        if (badColor) {
            w += "One or more overlay colors are not valid #RGB / #RRGGBB / #AARRGGBB"
        }

        // --- API ---
        if (s.asrBaseUrl.isBlank()) w += "ASR API URL is empty"
        if (s.llmBaseUrl.isBlank()) w += "LLM API URL is empty"
        if (s.asrApiKey.isBlank()) w += "ASR API key is empty (required to start)"
        if (s.llmApiKey.isBlank() && !s.asrOnlyMode) {
            w += "LLM API key is empty (required unless ASR-only mode)"
        }
        if (s.inputLanguage.isBlank()) w += "Input language is empty"
        if (s.outputLanguage.isBlank() && !s.asrOnlyMode) w += "Output language is empty"

        // Sanitize / clamp for persistence
        s = s.copy(
            silenceMs = s.silenceMs.coerceIn(50, 10_000),
            maxUtteranceMs = s.maxUtteranceMs.coerceIn(300, 120_000),
            minUtteranceMs = s.minUtteranceMs.coerceIn(0, 30_000),
            sileroVadMode = SileroVadMode.fromStorage(s.sileroVadMode).name,
            contextWindowSize = s.contextWindowSize.coerceIn(0, 30),
            offlineVadBatchSize = s.offlineVadBatchSize.coerceIn(1, 200),
            titleTurnThreshold = s.titleTurnThreshold.coerceIn(1, 50),
            maxNetworkAttempts = s.maxNetworkAttempts.coerceIn(1, 10),
            translationCacheMax = s.translationCacheMax.coerceIn(0, 500),
            overlayMaxWidthDp = s.overlayMaxWidthDp.coerceIn(120, 2000),
            overlayMaxHeightDp = s.overlayMaxHeightDp.coerceIn(60, 800),
            overlayAlphaPercent = s.overlayAlphaPercent.coerceIn(0, 100),
            overlayFontSizeSp = s.overlayFontSizeSp.coerceIn(10, 48),
            liveFontSizeSp = s.liveFontSizeSp.coerceIn(10, 48),
            overlayTextMode = OverlayTextMode.fromStorage(s.overlayTextMode).name,
            overlayLayoutMode = OverlayLayoutMode.fromStorage(s.overlayLayoutMode).name,
            overlayBgColor = UserSettings.normalizeColorHex(
                s.overlayBgColor, UserSettings.DEFAULT_OVERLAY_BG
            ),
            overlayEnTextColor = UserSettings.normalizeColorHex(
                s.overlayEnTextColor, UserSettings.DEFAULT_OVERLAY_EN
            ),
            overlayZhTextColor = UserSettings.normalizeColorHex(
                s.overlayZhTextColor, UserSettings.DEFAULT_OVERLAY_ZH
            ),
            uiLanguage = com.example.livetranslate.util.AppLocale.normalize(s.uiLanguage),
            asrBaseUrl = UserSettings.normalizeBaseUrl(s.asrBaseUrl),
            llmBaseUrl = UserSettings.normalizeBaseUrl(s.llmBaseUrl)
        )
        if (s.minUtteranceMs > s.maxUtteranceMs) {
            s = s.copy(minUtteranceMs = s.maxUtteranceMs)
            w += "Min utterance was clamped to max utterance"
        }

        return Result(warnings = w, sanitized = s)
    }
}
