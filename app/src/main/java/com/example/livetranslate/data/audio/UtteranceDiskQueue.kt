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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk-backed queue for utterances that cannot be processed immediately
 * (offline, bounded-memory queue overflow, or permanent ASR failure for later retry).
 *
 * Item directory names are **monotonically ordered** so lexicographic min == FIFO head:
 * `{20-digit-seq}_{8-hex}` e.g. `00000000000000000003_a1b2c3d4`.
 * Legacy UUID-only folders (pre-fix) still load; they sort after sequenced items by mtime.
 *
 * Binary layout per item directory:
 * - meta.bin: sampleRate(int), reasonOrdinal(int), offsetMs(long), pcmLength(int)
 * - audio.pcm: raw PCM bytes
 */
class UtteranceDiskQueue(private val root: File) {
    private val lock = Any()
    private val approxCount = AtomicInteger(0)
    /** Next enqueue sequence; survives process restarts by scanning existing dirs. */
    private val nextSeq = AtomicLong(0)

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
    )

    init {
        root.mkdirs()
        val ids = listEntryNamesLocked()
        approxCount.set(ids.size)
        nextSeq.set(maxExistingSeq(ids) + 1)
    }

    fun size(): Int = synchronized(lock) { listEntryNamesLocked().size }

    fun enqueue(utt: UtteranceAudio): String = synchronized(lock) {
        val seq = nextSeq.getAndIncrement()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val id = formatId(seq, suffix)
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
            approxCount.incrementAndGet()
            Log.i(TAG, "enqueued id=$id bytes=${utt.pcm.size} size=${approxCount.get()}")
            id
        } catch (e: Exception) {
            dir.deleteRecursively()
            Log.e(TAG, "enqueue failed", e)
            throw e
        }
    }

    /** FIFO poll; returns null if empty. */
    fun poll(): UtteranceAudio? = synchronized(lock) {
        val id = listIdsFifoLocked().firstOrNull() ?: return null
        val dir = File(root, id)
        return try {
            val utt = readDir(dir)
            dir.deleteRecursively()
            approxCount.decrementAndGet()
            utt
        } catch (e: Exception) {
            Log.e(TAG, "poll corrupt $id", e)
            dir.deleteRecursively()
            approxCount.decrementAndGet()
            null
        }
    }

    fun clear() = synchronized(lock) {
        root.listFiles()?.forEach { it.deleteRecursively() }
        approxCount.set(0)
        // Keep nextSeq high enough that new ids never collide with deleted ones on same volume
        // (not required for correctness of empty queue, but avoids reuse if partial delete fails).
    }

    /** Valid item directory names in FIFO order (oldest first). */
    private fun listIdsFifoLocked(): List<String> {
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && File(it, META).isFile && File(it, PCM).isFile }
            ?: return emptyList()
        return dirs
            .sortedWith(
                compareBy<File> { parseSeq(it.name) ?: Long.MAX_VALUE }
                    .thenBy { it.lastModified() }
                    .thenBy { it.name }
            )
            .map { it.name }
    }

    private fun listEntryNamesLocked(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it, META).isFile && File(it, PCM).isFile }
            ?.map { it.name }
            ?: emptyList()

    private fun readDir(dir: File): UtteranceAudio {
        val meta = File(dir, META)
        val pcmFile = File(dir, PCM)
        DataInputStream(FileInputStream(meta)).use { inn ->
            val sampleRate = inn.readInt()
            val reasonOrd = inn.readInt()
            val offsetMs = inn.readLong()
            val pcmLen = inn.readInt()
            val pcm = ByteArray(pcmLen)
            FileInputStream(pcmFile).use { fis ->
                var off = 0
                while (off < pcmLen) {
                    val n = fis.read(pcm, off, pcmLen - off)
                    if (n < 0) break
                    off += n
                }
            }
            val reason = CutReason.entries.getOrElse(reasonOrd) { CutReason.Silence }
            return UtteranceAudio(pcm, sampleRate, reason, offsetMs)
        }
    }

    companion object {
        private const val TAG = "UtteranceDiskQueue"
        const val DIR = "pending_utterances"
        private const val META = "meta.bin"
        private const val PCM = "audio.pcm"
        /** Zero-padded width so string sort == numeric sort for sequenced ids. */
        private const val SEQ_WIDTH = 20

        internal fun formatId(seq: Long, suffix: String): String =
            seq.toString().padStart(SEQ_WIDTH, '0') + "_" + suffix

        /** Leading decimal sequence, or null for legacy UUID-only names. */
        internal fun parseSeq(name: String): Long? {
            val underscore = name.indexOf('_')
            if (underscore <= 0) return null
            val head = name.substring(0, underscore)
            if (head.any { !it.isDigit() }) return null
            return head.toLongOrNull()
        }

        private fun maxExistingSeq(ids: List<String>): Long =
            ids.mapNotNull { parseSeq(it) }.maxOrNull() ?: -1L
    }
}
