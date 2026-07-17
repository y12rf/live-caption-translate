package com.example.livetranslate.data.audio

import android.content.Context
import android.util.Log
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Random-access park for failed ASR PCM (not part of the FIFO overflow queue).
 * Fail-list entries keep only [id]; [take] loads and deletes the entry.
 */
class ParkedPcmStore(private val root: File) {
    private val lock = Any()

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
    )

    init {
        root.mkdirs()
    }

    fun park(utt: UtteranceAudio): String = synchronized(lock) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val dir = File(root, id).apply { mkdirs() }
        try {
            FileOutputStream(File(dir, META)).use { fos ->
                DataOutputStream(fos).use { out ->
                    out.writeInt(utt.sampleRate)
                    out.writeInt(utt.reason.ordinal)
                    out.writeLong(utt.offsetMs)
                    out.writeInt(utt.pcm.size)
                }
            }
            FileOutputStream(File(dir, PCM)).use { it.write(utt.pcm) }
            id
        } catch (e: Exception) {
            dir.deleteRecursively()
            Log.e(TAG, "park failed", e)
            throw e
        }
    }

    fun take(id: String): UtteranceAudio? = synchronized(lock) {
        if (id.isBlank()) return null
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        return try {
            val utt = readDir(dir)
            dir.deleteRecursively()
            utt
        } catch (e: Exception) {
            Log.e(TAG, "take corrupt $id", e)
            dir.deleteRecursively()
            null
        }
    }

    fun clear() = synchronized(lock) {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun readDir(dir: File): UtteranceAudio {
        val meta = File(dir, META)
        val pcmFile = File(dir, PCM)
        DataInputStream(FileInputStream(meta)).use { inn ->
            val sampleRate = inn.readInt()
            val reasonOrd = inn.readInt()
            val offsetMs = inn.readLong()
            val pcmLen = inn.readInt()
            val pcm = ByteArray(pcmLen.coerceAtLeast(0))
            FileInputStream(pcmFile).use { fis ->
                var off = 0
                while (off < pcm.size) {
                    val n = fis.read(pcm, off, pcm.size - off)
                    if (n < 0) break
                    off += n
                }
            }
            val reason = CutReason.entries.getOrElse(reasonOrd) { CutReason.Silence }
            return UtteranceAudio(pcm, sampleRate, reason, offsetMs)
        }
    }

    companion object {
        private const val TAG = "ParkedPcmStore"
        const val DIR = "failed_asr_pcm"
        private const val META = "meta.bin"
        private const val PCM = "audio.pcm"
    }
}
