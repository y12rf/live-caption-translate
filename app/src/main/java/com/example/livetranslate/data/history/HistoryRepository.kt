package com.example.livetranslate.data.history

import android.content.Context
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.domain.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow
import java.io.File

class HistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).sessionDao()

    fun observeSessions(): Flow<List<SessionSummary>> = dao.observeSessions()

    /**
     * Empty [query] → all sessions; otherwise match title / EN / ZH (case-insensitive).
     */
    fun observeSessionsSearch(query: String): Flow<List<SessionSummary>> {
        val q = sanitizeSearchQuery(query)
        if (q.isEmpty()) return dao.observeSessions()
        // Strip LIKE wildcards from user input; wrap with % ourselves
        val pattern = "%$q%"
        return dao.searchSessions(pattern)
    }

    suspend fun saveSession(
        startedAt: Long,
        endedAt: Long,
        segments: List<TranscriptSegment>,
        audioPath: String? = null,
        title: String? = null
    ): Long = dao.saveFullSession(startedAt, endedAt, segments, audioPath, title)

    suspend fun getSessionDetail(id: Long): SessionDetail? {
        val session = dao.getSession(id) ?: return null
        val segments = dao.getSegments(id)
        return SessionDetail(session, segments)
    }

    /**
     * Delete one history session and its segments.
     * WAV file is removed only when no other session still references the same path
     * (e.g. shared by `Re` reprocess sessions).
     *
     * @return true if a session row was deleted
     */
    suspend fun deleteSession(id: Long): Boolean {
        val session = dao.getSession(id) ?: return false
        val path = session.audioPath?.trim()?.takeIf { it.isNotEmpty() }
        dao.deleteSegmentsForSession(id)
        dao.deleteSessionById(id)
        if (path != null) {
            val remaining = dao.countSessionsWithAudioPath(path)
            if (remaining == 0) {
                runCatching { File(path).delete() }
            }
        }
        return true
    }

    fun formatMarkdown(detail: SessionDetail): String = HistoryExport.formatMarkdown(detail)

    fun audioFileFor(detail: SessionDetail): File? =
        SessionAudioRecorder.fileForPath(detail.session.audioPath)

    fun audioFileFor(summary: SessionSummary): File? =
        SessionAudioRecorder.fileForPath(summary.audioPath)

    /** Filter segments in a detail view by keyword (EN/ZH). */
    fun filterSegments(detail: SessionDetail, query: String): List<SegmentEntity> {
        val q = query.trim()
        if (q.isEmpty()) return detail.segments
        return detail.segments.filter { seg ->
            seg.source.contains(q, ignoreCase = true) ||
                seg.translation.contains(q, ignoreCase = true)
        }
    }

    companion object {
        /** Drop SQL LIKE meta-chars so user query is literal substring match. */
        fun sanitizeSearchQuery(raw: String): String =
            raw.trim()
                .replace("%", "")
                .replace("_", "")
                .replace("\\", "")
    }
}
