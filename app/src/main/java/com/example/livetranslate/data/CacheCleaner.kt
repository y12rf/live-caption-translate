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

        fun summaryZh(): String = buildString {
            if (translationCacheCleared) append("翻译缓存已清空")
            if (orphanFilesDeleted > 0) {
                if (isNotEmpty()) append("；")
                append("孤立录音 ${orphanFilesDeleted} 个（${formatBytes(orphanBytesFreed)}）")
            }
            if (historySessionsDeleted > 0) {
                if (isNotEmpty()) append("；")
                append("历史 ${historySessionsDeleted} 场（${formatBytes(historyBytesFreed)}）")
            }
            if (isEmpty()) append("没有可清理的内容")
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
