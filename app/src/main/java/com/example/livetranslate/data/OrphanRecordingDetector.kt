package com.example.livetranslate.data

import android.content.Context
import com.example.livetranslate.data.audio.SessionAudioRecorder
import java.io.File

/**
 * Finds session WAV files under recordings/ that are not referenced by Room.
 */
object OrphanRecordingDetector {

    data class Orphan(
        val file: File,
        val path: String,
        val lastModified: Long,
        val lengthBytes: Long
    )

    /**
     * Orphans newest-first. Skips tiny / unreadable files still listed so user can discard.
     */
    suspend fun findOrphans(context: Context): List<Orphan> {
        val app = context.applicationContext
        val dir = File(app.filesDir, SessionAudioRecorder.RECORDINGS_DIR)
        if (!dir.isDirectory) return emptyList()
        val keep = CacheCleaner.referencedAudioPaths(app)
        val out = ArrayList<Orphan>()
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (!f.name.endsWith(".wav", ignoreCase = true)) return@forEach
            val path = f.absolutePath
            if (path in keep) return@forEach
            out.add(
                Orphan(
                    file = f,
                    path = path,
                    lastModified = f.lastModified(),
                    lengthBytes = f.length()
                )
            )
        }
        return out.sortedByDescending { it.lastModified }
    }

    fun isPlayable(file: File): Boolean =
        SessionAudioRecorder.fileForPath(file.absolutePath) != null

    fun delete(path: String): Boolean {
        val f = File(path)
        return !f.exists() || f.delete()
    }
}
