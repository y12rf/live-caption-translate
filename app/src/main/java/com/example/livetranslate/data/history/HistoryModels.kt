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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val endedAt: Long,
    val previewZh: String,
    /** Absolute path to full-session WAV under app filesDir, or null. */
    val audioPath: String? = null
)

@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val source: String,
    val translation: String,
    val cutReason: String?,
    val incomplete: Boolean,
    val createdAt: Long,
    val offsetMs: Long = 0L
)

data class SessionSummary(
    val id: Long,
    val createdAt: Long,
    val endedAt: Long,
    val previewZh: String,
    val audioPath: String? = null
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
        SELECT id, createdAt, endedAt, previewZh, audioPath FROM sessions
        ORDER BY createdAt DESC
        """
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSegments(sessionId: Long): List<SegmentEntity>

    @Query("SELECT audioPath FROM sessions")
    suspend fun allAudioPaths(): List<String?>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun sessionCount(): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE audioPath = :path")
    suspend fun countSessionsWithAudioPath(path: String): Int

    @Query("DELETE FROM segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)

    @Query("DELETE FROM segments")
    suspend fun deleteAllSegments()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM segments WHERE id = :id LIMIT 1")
    suspend fun getSegment(id: Long): SegmentEntity?

    @Query(
        """
        UPDATE segments
        SET source = :source, translation = :translation, incomplete = :incomplete
        WHERE id = :id
        """
    )
    suspend fun updateSegmentText(
        id: Long,
        source: String,
        translation: String,
        incomplete: Boolean
    ): Int

    @Query("UPDATE sessions SET previewZh = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String): Int

    /**
     * Search sessions by title or any segment EN/ZH text.
     * [pattern] should already include SQL wildcards, e.g. `%foo%`.
     */
    @Query(
        """
        SELECT DISTINCT s.id, s.createdAt, s.endedAt, s.previewZh, s.audioPath
        FROM sessions s
        LEFT JOIN segments g ON g.sessionId = s.id
        WHERE s.previewZh LIKE :pattern COLLATE NOCASE
           OR g.source LIKE :pattern COLLATE NOCASE
           OR g.translation LIKE :pattern COLLATE NOCASE
        ORDER BY s.createdAt DESC
        """
    )
    fun searchSessions(pattern: String): Flow<List<SessionSummary>>

    @Transaction
    suspend fun saveFullSession(
        createdAt: Long,
        endedAt: Long,
        segments: List<TranscriptSegment>,
        audioPath: String? = null,
        /** LLM-generated or manual title; falls back to translation preview. */
        title: String? = null
    ): Long {
        val preview = title?.trim()?.take(80)?.ifBlank { null }
            ?: segments.joinToString(" ") { it.translation }.take(80)
                .ifBlank { if (audioPath != null) "(录音)" else "" }
        val sessionId = insertSession(
            SessionEntity(
                createdAt = createdAt,
                endedAt = endedAt,
                previewZh = preview,
                audioPath = audioPath
            )
        )
        if (segments.isNotEmpty()) {
            insertSegments(
                segments.map { seg ->
                    SegmentEntity(
                        sessionId = sessionId,
                        source = seg.source,
                        translation = seg.translation,
                        cutReason = seg.cutReason?.name,
                        incomplete = seg.incomplete,
                        createdAt = seg.timestampMs,
                        offsetMs = seg.offsetMs
                    )
                }
            )
        }
        return sessionId
    }
}

/** Which text lines go into SRT / clipboard export. */
enum class ExportTextMode {
    /** Translation + source (bilingual cue body). Default. */
    Both,
    /** Translation only. */
    TranslationOnly,
    /** Source / original only. */
    SourceOnly
}

object HistoryExport {
    private val wallFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private const val DEFAULT_CUE_MS = 3_000L
    private const val MIN_CUE_MS = 800L

    fun formatOffset(offsetMs: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(offsetMs.coerceAtLeast(0))
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** SRT timestamp: HH:MM:SS,mmm */
    fun formatSrtTimestamp(offsetMs: Long): String {
        val ms = offsetMs.coerceAtLeast(0L)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val milli = ms % 1_000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    fun formatSrt(detail: SessionDetail, mode: ExportTextMode = ExportTextMode.Both): String {
        if (detail.segments.isEmpty()) return ""
        val sessionDur = (detail.session.endedAt - detail.session.createdAt).coerceAtLeast(0L)
        return formatSrtFromCues(
            offsets = detail.segments.map { it.offsetMs },
            sources = detail.segments.map { it.source },
            translations = detail.segments.map { it.translation },
            mode = mode,
            sessionDurationMs = sessionDur
        )
    }

    fun formatSrtFromLive(
        segments: List<TranscriptSegment>,
        mode: ExportTextMode = ExportTextMode.Both,
        sessionDurationMs: Long = 0L
    ): String {
        if (segments.isEmpty()) return ""
        return formatSrtFromCues(
            offsets = segments.map { it.offsetMs },
            sources = segments.map { it.source },
            translations = segments.map { it.translation },
            mode = mode,
            sessionDurationMs = sessionDurationMs
        )
    }

    private fun formatSrtFromCues(
        offsets: List<Long>,
        sources: List<String>,
        translations: List<String>,
        mode: ExportTextMode,
        sessionDurationMs: Long
    ): String = buildString {
        var index = 0
        for (i in offsets.indices) {
            val body = cueBody(sources[i], translations[i], mode) ?: continue
            val start = offsets[i].coerceAtLeast(0L)
            val end = cueEndMs(i, offsets, sessionDurationMs)
            if (end <= start) continue
            index++
            append(index)
            append('\n')
            append(formatSrtTimestamp(start))
            append(" --> ")
            append(formatSrtTimestamp(end))
            append('\n')
            append(body)
            append("\n\n")
        }
    }.trimEnd() + if (offsets.isNotEmpty()) "\n" else ""

    private fun cueBody(source: String, translation: String, mode: ExportTextMode): String? {
        val src = source.trim()
        val zh = translation.trim()
        return when (mode) {
            ExportTextMode.Both -> {
                when {
                    zh.isNotEmpty() && src.isNotEmpty() -> "$zh\n$src"
                    zh.isNotEmpty() -> zh
                    src.isNotEmpty() -> src
                    else -> null
                }
            }
            ExportTextMode.TranslationOnly -> zh.ifEmpty { null }
            ExportTextMode.SourceOnly -> src.ifEmpty { null }
        }
    }

    private fun cueEndMs(index: Int, offsets: List<Long>, sessionDurationMs: Long): Long {
        val start = offsets[index].coerceAtLeast(0L)
        val next = offsets.getOrNull(index + 1)
        val rawEnd = when {
            next != null && next > start -> next
            sessionDurationMs > start -> sessionDurationMs
            else -> start + DEFAULT_CUE_MS
        }
        return rawEnd.coerceAtLeast(start + MIN_CUE_MS)
    }

    /** Plain text: one segment per line (for clipboard). */
    fun formatPlainText(detail: SessionDetail, mode: ExportTextMode): String {
        if (detail.segments.isEmpty()) return ""
        return detail.segments.mapNotNull { seg ->
            when (mode) {
                ExportTextMode.TranslationOnly -> seg.translation.trim().ifEmpty { null }
                ExportTextMode.SourceOnly -> seg.source.trim().ifEmpty { null }
                ExportTextMode.Both -> {
                    val zh = seg.translation.trim()
                    val en = seg.source.trim()
                    when {
                        zh.isNotEmpty() && en.isNotEmpty() -> "$zh\n$en"
                        zh.isNotEmpty() -> zh
                        en.isNotEmpty() -> en
                        else -> null
                    }
                }
            }
        }.joinToString("\n\n")
    }

    fun formatMarkdown(detail: SessionDetail): String = buildString {
        appendLine("# Live Translate Session")
        appendLine()
        appendLine("- Started: ${wallFmt.format(Date(detail.session.createdAt))}")
        appendLine("- Ended: ${wallFmt.format(Date(detail.session.endedAt))}")
        appendLine()
        detail.segments.forEachIndexed { index, seg ->
            val wall = wallFmt.format(Date(seg.createdAt))
            val rel = formatOffset(seg.offsetMs)
            appendLine("## ${index + 1}  [$rel]  ($wall)")
            appendLine("**EN:** ${seg.source}")
            appendLine()
            appendLine("**ZH:** ${seg.translation}")
            if (seg.incomplete) {
                appendLine()
                appendLine("_(incomplete)_")
            }
            appendLine()
        }
    }

    fun formatMarkdownFromLive(
        startedAt: Long,
        segments: List<TranscriptSegment>
    ): String = buildString {
        appendLine("# Live Translate Session")
        appendLine()
        appendLine("- Started: ${wallFmt.format(Date(startedAt))}")
        appendLine("- Exported: ${wallFmt.format(Date())}")
        appendLine()
        segments.forEachIndexed { index, seg ->
            val wall = wallFmt.format(Date(seg.timestampMs))
            val rel = formatOffset(seg.offsetMs)
            appendLine("## ${index + 1}  [$rel]  ($wall)")
            appendLine("**EN:** ${seg.source}")
            appendLine()
            appendLine("**ZH:** ${seg.translation}")
            appendLine()
        }
    }

    fun cutReasonOf(name: String?): CutReason? =
        name?.let { runCatching { CutReason.valueOf(it) }.getOrNull() }
}
