package com.example.livetranslate.domain

import com.example.livetranslate.domain.model.UtteranceAudio

/**
 * Pipeline work that exhausted automatic retries and needs user / network recovery.
 *
 * [Asr] prefers [diskId] (see [com.example.livetranslate.data.audio.ParkedPcmStore]) so
 * weak-net fail lists do not hold multi-second PCM arrays in RAM. [utt] is a lightweight
 * metadata stub when parked (empty pcm).
 */
sealed class FailedWork {
    abstract val id: Long
    abstract val message: String
    abstract val stage: Stage

    enum class Stage { Asr, Llm }

    data class Asr(
        override val id: Long,
        val utt: UtteranceAudio,
        override val message: String,
        /** When non-null, full PCM is on disk; resolve via ParkedPcmStore before retry. */
        val diskId: String? = null
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
