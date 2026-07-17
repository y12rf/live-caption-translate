package com.example.livetranslate.data.settings

/**
 * Silero VAD confidence mode (maps to library [com.konovalov.vad.silero.config.Mode]).
 * Higher = fewer false speech detections (stricter score threshold).
 */
enum class SileroVadMode {
    NORMAL,
    AGGRESSIVE,
    VERY_AGGRESSIVE;

    companion object {
        fun fromStorage(raw: String?): SileroVadMode {
            if (raw.isNullOrBlank()) return NORMAL
            return entries.find { it.name.equals(raw.trim(), ignoreCase = true) } ?: NORMAL
        }
    }
}
