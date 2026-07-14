package com.example.livetranslate.domain.model

enum class CutReason { Silence, MaxDuration, StopFlush }

/** Where PCM samples come from. */
enum class AudioSourceType {
    /** Device microphone */
    Microphone,
    /** Other apps' playback (internal audio, API 29+) */
    Internal
}

data class UtteranceAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val reason: CutReason
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UtteranceAudio) return false
        return sampleRate == other.sampleRate &&
            reason == other.reason &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int =
        31 * (31 * pcm.contentHashCode() + sampleRate) + reason.hashCode()
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
    /** ms since session start. */
    val offsetMs: Long = 0L
)
