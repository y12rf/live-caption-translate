package com.example.livetranslate.data.history

import android.content.Context
import com.example.livetranslate.domain.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

class HistoryRepository(context: Context) {
    private val dao = AppDatabase.get(context).sessionDao()

    fun observeSessions(): Flow<List<SessionSummary>> = dao.observeSessions()

    suspend fun saveSession(
        startedAt: Long,
        endedAt: Long,
        segments: List<TranscriptSegment>
    ): Long = dao.saveFullSession(startedAt, endedAt, segments)

    suspend fun getSessionDetail(id: Long): SessionDetail? {
        val session = dao.getSession(id) ?: return null
        val segments = dao.getSegments(id)
        return SessionDetail(session, segments)
    }

    fun formatMarkdown(detail: SessionDetail): String = HistoryExport.formatMarkdown(detail)
}
