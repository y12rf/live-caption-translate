package com.example.livetranslate.data

import android.content.Context
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.history.AppDatabase
import java.io.File

/**
 * Clears app caches: orphaned session WAVs under files/recordings,
 * and reports how much was freed. History DB is untouched unless
 * [clearAllHistoryAndRecordings] is used.
 */
object CacheCleaner {

    data class Result(
        val orphanFilesDeleted: Int = 0,
        val orphanBytesFreed: Long = 0L,
        val historySessionsDeleted: Int = 0,
        val historyBytesFreed: Long = 0L,
        val translationCacheCleared: Boolean = false
    ) {
        val totalBytesFreed: Long get() = orphanBytesFreed + historyBytesFreed

        fun summaryZh(): String = summary(null)

        /** Localized summary; pass [context] for string resources. */
        fun summary(context: android.content.Context?): String {
            if (context == null) {
                return buildString {
                    if (translationCacheCleared) append("Translation cache cleared")
                    if (orphanFilesDeleted > 0) {
                        if (isNotEmpty()) append("; ")
                        append("Orphan recordings: $orphanFilesDeleted (${formatBytes(orphanBytesFreed)})")
                    }
                    if (historySessionsDeleted > 0) {
                        if (isNotEmpty()) append("; ")
                        append("History: $historySessionsDeleted (${formatBytes(historyBytesFreed)})")
                    }
                    if (isEmpty()) append("Nothing to clean")
                }
            }
            return buildString {
                val sep = context.getString(com.example.livetranslate.R.string.cache_summary_sep)
                if (translationCacheCleared) {
                    append(context.getString(com.example.livetranslate.R.string.cache_summary_translation))
                }
                if (orphanFilesDeleted > 0) {
                    if (isNotEmpty()) append(sep)
                    append(
                        context.getString(
                            com.example.livetranslate.R.string.cache_summary_orphans,
                            orphanFilesDeleted,
                            formatBytes(orphanBytesFreed)
                        )
                    )
                }
                if (historySessionsDeleted > 0) {
                    if (isNotEmpty()) append(sep)
                    append(
                        context.getString(
                            com.example.livetranslate.R.string.cache_summary_history,
                            historySessionsDeleted,
                            formatBytes(historyBytesFreed)
                        )
                    )
                }
                if (isEmpty()) {
                    append(context.getString(com.example.livetranslate.R.string.cache_summary_empty))
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }

    private fun recordingsDir(context: Context): File =
        File(context.filesDir, SessionAudioRecorder.RECORDINGS_DIR)

    /** Paths currently referenced by history sessions. */
    suspend fun referencedAudioPaths(context: Context): Set<String> {
        val dao = AppDatabase.get(context).sessionDao()
        return dao.allAudioPaths().mapNotNull { it?.trim()?.takeIf { p -> p.isNotEmpty() } }.toSet()
    }

    /**
     * Delete WAV files under recordings/ that are not linked to any session.
     */
    suspend fun clearOrphanRecordings(context: Context): Result {
        val app = context.applicationContext
        val dir = recordingsDir(app)
        if (!dir.isDirectory) return Result()
        val keep = referencedAudioPaths(app)
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val path = f.absolutePath
            if (path !in keep) {
                val len = f.length()
                if (f.delete()) {
                    count++
                    bytes += len
                }
            }
        }
        return Result(orphanFilesDeleted = count, orphanBytesFreed = bytes)
    }

    /**
     * Delete all history sessions + all recording files (including referenced).
     */
    suspend fun clearAllHistoryAndRecordings(context: Context): Result {
        val app = context.applicationContext
        val dao = AppDatabase.get(app).sessionDao()
        val paths = dao.allAudioPaths()
        val sessionCount = dao.sessionCount()
        dao.deleteAllSegments()
        dao.deleteAllSessions()

        var bytes = 0L
        var files = 0
        paths.forEach { p ->
            if (p.isNullOrBlank()) return@forEach
            val f = File(p)
            if (f.isFile) {
                val len = f.length()
                if (f.delete()) {
                    files++
                    bytes += len
                }
            }
        }
        // Sweep leftovers in recordings dir
        recordingsDir(app).listFiles()?.forEach { f ->
            if (f.isFile) {
                val len = f.length()
                if (f.delete()) {
                    files++
                    bytes += len
                }
            }
        }
        return Result(
            orphanFilesDeleted = files,
            orphanBytesFreed = bytes,
            historySessionsDeleted = sessionCount,
            historyBytesFreed = 0L // counted in orphanBytes for simplicity
        )
    }
}
