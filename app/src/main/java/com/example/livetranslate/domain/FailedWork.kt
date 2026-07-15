package com.example.livetranslate.domain

import com.example.livetranslate.domain.model.UtteranceAudio

/**
 * Pipeline work that exhausted automatic retries and needs user / network recovery.
 */
sealed class FailedWork {
    abstract val id: Long
    abstract val message: String
    abstract val stage: Stage

    enum class Stage { Asr, Llm }

    data class Asr(
        override val id: Long,
        val utt: UtteranceAudio,
        override val message: String
    ) : FailedWork() {
        override val stage: Stage get() = Stage.Asr
    }

    data class Llm(
        override val id: Long,
        val utt: UtteranceAudio,
        val en: String,
        val windowSize: Int,
        val segmentLocalId: Long,
        override val message: String
    ) : FailedWork() {
        override val stage: Stage get() = Stage.Llm
    }
}
