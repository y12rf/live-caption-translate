package com.example.livetranslate.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val endedAt: Long,
    val previewZh: String
)

@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val source: String,
    val translation: String,
    val cutReason: String?,
    val incomplete: Boolean,
    val createdAt: Long
)

data class SessionSummary(
    val id: Long,
    val createdAt: Long,
    val endedAt: Long,
    val previewZh: String
)

data class SessionDetail(
    val session: SessionEntity,
    val segments: List<SegmentEntity>
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query(
        """
        SELECT id, createdAt, endedAt, previewZh FROM sessions
        ORDER BY createdAt DESC
        """
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSegments(sessionId: Long): List<SegmentEntity>

    @Transaction
    suspend fun saveFullSession(
        createdAt: Long,
        endedAt: Long,
        segments: List<TranscriptSegment>
    ): Long {
        val preview = segments.joinToString(" ") { it.translation }.take(80)
        val sessionId = insertSession(
            SessionEntity(createdAt = createdAt, endedAt = endedAt, previewZh = preview)
        )
        val now = System.currentTimeMillis()
        insertSegments(
            segments.map { seg ->
                SegmentEntity(
                    sessionId = sessionId,
                    source = seg.source,
                    translation = seg.translation,
                    cutReason = seg.cutReason?.name,
                    incomplete = seg.incomplete,
                    createdAt = now
                )
            }
        )
        return sessionId
    }
}

object HistoryExport {
    fun formatMarkdown(detail: SessionDetail): String = buildString {
        appendLine("# Live Translate Session")
        appendLine()
        appendLine("- Started: ${detail.session.createdAt}")
        appendLine("- Ended: ${detail.session.endedAt}")
        appendLine()
        detail.segments.forEachIndexed { index, seg ->
            appendLine("## ${index + 1}")
            appendLine("**EN:** ${seg.source}")
            appendLine()
            appendLine("**ZH:** ${seg.translation}")
            if (seg.incomplete) appendLine()
            if (seg.incomplete) appendLine("_(incomplete)_")
            appendLine()
        }
    }

    fun cutReasonOf(name: String?): CutReason? =
        name?.let { runCatching { CutReason.valueOf(it) }.getOrNull() }
}
