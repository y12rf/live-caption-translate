package com.example.livetranslate.data.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams 16-bit mono PCM frames into a WAV file while a session is active.
 * Header sizes are patched on [finish]. Thread-safe for capture IO + main stop.
 */
class SessionAudioRecorder(
    private val context: Context,
    private val sampleRate: Int = AudioCapture.ASR_SAMPLE_RATE
) {
    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private var outFile: File? = null
    private var pcmBytes: Long = 0L
    private var loggedCap: Boolean = false
    private val active = AtomicBoolean(false)

    val isActive: Boolean get() = active.get()
    val currentFile: File? get() = synchronized(lock) { outFile }

    fun begin(startedAt: Long = System.currentTimeMillis()): File = synchronized(lock) {
        discardLocked()
        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val name = "session_${fileStamp.format(Date(startedAt))}.wav"
        val file = File(dir, name)
        val access = RandomAccessFile(file, "rw")
        // Placeholder header; sizes rewritten in finish()
        access.write(WavEncoder.buildHeader(dataSize = 0, sampleRate = sampleRate))
        raf = access
        outFile = file
        pcmBytes = 0L
        active.set(true)
        Log.i(TAG, "begin recording → ${file.absolutePath}")
        file
    }

    /** Append one frame of 16-bit mono samples (little-endian on disk). */
    fun writeFrame(samples: ShortArray) {
        if (!active.get() || samples.isEmpty()) return
        synchronized(lock) {
            val access = raf ?: return
            // Classic WAV data size is 32-bit; stop growing past Int.MAX_VALUE payload.
            val add = samples.size * 2L
            if (pcmBytes + add > MAX_PCM_BYTES) {
                if (!loggedCap) {
                    Log.w(TAG, "writeFrame: hit WAV size cap ($MAX_PCM_BYTES); further frames ignored")
                    loggedCap = true
                }
                return
            }
            try {
                val bytes = ByteArray(samples.size * 2)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) bb.putShort(s)
                access.write(bytes)
                pcmBytes += bytes.size
            } catch (e: Exception) {
                Log.e(TAG, "writeFrame failed", e)
            }
        }
    }

    /**
     * Finalize WAV header. Returns absolute path if file has audio; otherwise deletes and returns null.
     */
    fun finish(): String? = synchronized(lock) {
        active.set(false)
        val access = raf
        val file = outFile
        raf = null
        outFile = null
        if (access == null || file == null) return null
        return try {
            if (pcmBytes <= 0L) {
                access.close()
                file.delete()
                Log.i(TAG, "finish: empty recording discarded")
                null
            } else {
                val dataSize = pcmBytes.coerceAtMost(MAX_PCM_BYTES).toInt()
                access.seek(0)
                access.write(
                    WavEncoder.buildHeader(
                        dataSize = dataSize,
                        sampleRate = sampleRate
                    )
                )
                access.close()
                Log.i(TAG, "finish: ${file.absolutePath} pcmBytes=$pcmBytes dataSize=$dataSize")
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "finish failed", e)
            try {
                access.close()
            } catch (_: Exception) {
            }
            runCatching { file.delete() }
            null
        } finally {
            pcmBytes = 0L
            loggedCap = false
        }
    }

    fun discard() = synchronized(lock) { discardLocked() }

    private fun discardLocked() {
        active.set(false)
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        outFile?.let { f ->
            runCatching { f.delete() }
        }
        outFile = null
        pcmBytes = 0L
        loggedCap = false
    }

    companion object {
        private const val TAG = "SessionAudioRecorder"
        const val RECORDINGS_DIR = "recordings"
        /** Classic WAV `data` chunk size is signed 32-bit; stay within range. */
        const val MAX_PCM_BYTES: Long = Int.MAX_VALUE.toLong()
        private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun fileForPath(path: String?): File? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            return if (f.isFile && f.length() > 44L) f else null
        }
    }
}
