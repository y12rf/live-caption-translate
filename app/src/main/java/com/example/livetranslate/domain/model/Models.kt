package com.example.livetranslate.domain.model

enum class CutReason { Silence, MaxDuration, StopFlush }

/** Where PCM samples come from. */
enum class AudioSourceType {
    /** Device microphone */
    Microphone,
    /** Other apps' playback (internal audio, API 29+) */
    Internal,
    /** User-picked file → FFmpeg → offline VAD → same ASR/LLM pipeline */
    File
}

data class UtteranceAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val reason: CutReason,
    /**
     * Start offset into continuous session recording (pause excluded), ms.
     * Sentence head — not cut/end time. Used for SRT cue start and history seek.
     */
    val offsetMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UtteranceAudio) return false
        return sampleRate == other.sampleRate &&
            reason == other.reason &&
            offsetMs == other.offsetMs &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var h = pcm.contentHashCode()
        h = 31 * h + sampleRate
        h = 31 * h + reason.hashCode()
        h = 31 * h + offsetMs.hashCode()
        return h
    }
}

data class ContextTurn(val source: String, val translation: String)

sealed class AsrStreamEvent {
    data class Delta(val text: String) : AsrStreamEvent()
    data class Completed(val fullText: String) : AsrStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : AsrStreamEvent()
}

sealed class LlmStreamEvent {
    data class Delta(val text: String) : LlmStreamEvent()
    data class Completed(val fullText: String) : LlmStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : LlmStreamEvent()
}

enum class SessionPhase { Idle, Recording, Paused, Processing }

data class TranscriptSegment(
    val source: String,
    val translation: String,
    val cutReason: CutReason?,
    val incomplete: Boolean = false,
    /** Absolute wall-clock ms when the segment was finalized. */
    val timestampMs: Long = System.currentTimeMillis(),
    /**
     * Sentence-start ms into continuous recording (pauses excluded), for SRT / seek.
     */
    val offsetMs: Long = 0L,
    /**
     * In-session id so ASR-first commit can be updated when LLM finishes
     * (or marked failed without reordering).
     */
    val localId: Long = 0L
)
