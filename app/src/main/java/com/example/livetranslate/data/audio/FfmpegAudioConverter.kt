package com.example.livetranslate.data.audio

import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Converts arbitrary audio/video files to 16 kHz mono 16-bit PCM WAV
 * via FFmpeg (ffmpeg-kit-audio), matching [AudioCapture.ASR_SAMPLE_RATE].
 */
class FfmpegAudioConverter {

    data class ConvertResult(
        val wavFile: File,
        /** Best-effort duration from ffprobe (ms); 0 if unknown. */
        val durationMs: Long
    )

    /**
     * @param onProgress 0f..1f while FFmpeg runs (time / duration); may be sparse.
     */
    suspend fun convertTo16kMonoWav(
        inputPath: String,
        outputWav: File,
        onProgress: (Float) -> Unit = {}
    ): ConvertResult = withContext(Dispatchers.IO) {
        outputWav.parentFile?.mkdirs()
        if (outputWav.exists()) outputWav.delete()

        val durationMs = probeDurationMs(inputPath)
        Log.i(TAG, "convert in=$inputPath durationMs=$durationMs → ${outputWav.absolutePath}")

        // pcm_s16le mono 16k WAV; strip video if present
        val cmd = buildString {
            append("-y -i ").append(quote(inputPath))
            append(" -vn -ac 1 -ar ").append(AudioCapture.ASR_SAMPLE_RATE)
            append(" -c:a pcm_s16le ")
            append(quote(outputWav.absolutePath))
        }

        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(
                cmd,
                { completed ->
                    if (!cont.isActive) return@executeAsync
                    val code = completed.returnCode
                    if (ReturnCode.isSuccess(code) && outputWav.isFile && outputWav.length() > 44L) {
                        onProgress(1f)
                        cont.resume(ConvertResult(outputWav, durationMs))
                    } else {
                        val logs = completed.allLogsAsString?.takeLast(800).orEmpty()
                        val msg = buildString {
                            append("FFmpeg 转码失败")
                            code?.let { append(" (code=${it.value})") }
                            if (logs.isNotBlank()) append(": ").append(logs.trim())
                        }
                        Log.e(TAG, msg)
                        cont.resumeWithException(IllegalStateException(msg))
                    }
                },
                { log ->
                    val level = log.level
                    if (level == com.antonkarpenko.ffmpegkit.Level.AV_LOG_ERROR ||
                        level == com.antonkarpenko.ffmpegkit.Level.AV_LOG_FATAL ||
                        level == com.antonkarpenko.ffmpegkit.Level.AV_LOG_PANIC
                    ) {
                        Log.w(TAG, log.message.orEmpty())
                    }
                },
                { stats: Statistics ->
                    if (durationMs > 0L) {
                        // getTime() is milliseconds of processed media
                        val p = (stats.time / durationMs.toDouble()).toFloat().coerceIn(0f, 0.99f)
                        onProgress(p)
                    }
                }
            )
            cont.invokeOnCancellation {
                try {
                    FFmpegKit.cancel(session.sessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "cancel ffmpeg failed", e)
                }
            }
        }
    }

    fun probeDurationMs(inputPath: String): Long {
        return try {
            val session = FFprobeKit.getMediaInformation(inputPath)
            val info = session.mediaInformation ?: return 0L
            val sec = info.duration?.toDoubleOrNull() ?: return 0L
            (sec * 1000.0).toLong().coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.w(TAG, "probe duration failed", e)
            0L
        }
    }

    companion object {
        private const val TAG = "FfmpegAudioConverter"

        /** Quote path for FFmpeg command string (spaces / Unicode). */
        internal fun quote(path: String): String {
            val escaped = path.replace("'", "'\\''")
            return "'$escaped'"
        }
    }
}
