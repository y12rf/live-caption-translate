package com.example.livetranslate.domain

/**
 * Title helpers for offline reprocess / orphan recovery sessions.
 */
object ReprocessTitle {
    const val PREFIX = "Re"
    const val FALLBACK_BASE = "未命名会话"
    const val ORPHAN_BASE = "未保存录音"

    /** Always prefix [PREFIX]; does not strip existing [PREFIX]. */
    fun reTitle(baseTitle: String?): String {
        val base = baseTitle?.trim().orEmpty().ifBlank { FALLBACK_BASE }
        return PREFIX + base
    }

    fun orphanTitle(): String = reTitle(ORPHAN_BASE)
}
