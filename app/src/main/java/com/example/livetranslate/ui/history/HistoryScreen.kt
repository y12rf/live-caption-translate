package com.example.livetranslate.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.R
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.domain.ReprocessPhase
import com.example.livetranslate.util.RecordingShareHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val query by viewModel.listQuery.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    var listMenuSessionId by remember { mutableStateOf<Long?>(null) }
    var listDeleteId by remember { mutableStateOf<Long?>(null) }

    if (listDeleteId != null) {
        AlertDialog(
            onDismissRequest = { listDeleteId = null },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = listDeleteId
                        listDeleteId = null
                        if (id != null) viewModel.deleteSession(id)
                    }
                ) {
                    Text(stringResource(R.string.history_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { listDeleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setListQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.history_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setListQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )
            when {
                sessions.isEmpty() && query.isBlank() -> {
                    Text(
                        stringResource(R.string.history_empty),
                        modifier = Modifier.padding(16.dp)
                    )
                }
                sessions.isEmpty() -> {
                    Text(
                        stringResource(R.string.history_search_empty),
                        modifier = Modifier.padding(16.dp)
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(sessions, key = { it.id }) { s ->
                            val hasAudio = SessionAudioRecorder.fileForPath(s.audioPath) != null
                            ListItem(
                                headlineContent = {
                                    Text(s.previewZh.ifBlank { "(empty)" })
                                },
                                supportingContent = {
                                    val time = fmt.format(Date(s.createdAt))
                                    Text(
                                        if (hasAudio) {
                                            "$time · ${stringResource(R.string.history_has_audio)}"
                                        } else {
                                            time
                                        }
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { listMenuSessionId = s.id }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.history_delete)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = listMenuSessionId == s.id,
                                        onDismissRequest = { listMenuSessionId = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.history_delete)) },
                                            onClick = {
                                                listMenuSessionId = null
                                                listDeleteId = s.id
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDetail(s.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    sessionId: Long,
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit = {}
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val detailQuery by viewModel.detailQuery.collectAsStateWithLifecycle()
    val filtered by viewModel.filteredSegments.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val reprocess by viewModel.reprocessState.collectAsStateWithLifecycle()
    val deletedId by viewModel.deletedSessionId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wallFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) {
        viewModel.loadDetail(sessionId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onLeaveDetail() }
    }

    LaunchedEffect(reprocess.lastSavedSessionId) {
        val id = reprocess.lastSavedSessionId ?: return@LaunchedEffect
        val toastMsg = reprocess.message.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.reprocess_saved)
        viewModel.clearReprocessSaved()
        android.widget.Toast.makeText(
            context,
            toastMsg,
            android.widget.Toast.LENGTH_LONG
        ).show()
        onOpenSession(id)
    }

    LaunchedEffect(deletedId) {
        if (deletedId != null) {
            viewModel.clearDeletedSessionId()
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.history_deleted),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            onBack()
        }
    }

    if (deleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        viewModel.deleteCurrentSession()
                    }
                ) {
                    Text(stringResource(R.string.history_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    fun toastEmpty() {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.export_empty),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun exportSrt(mode: ExportTextMode, nameSuffix: String) {
        val srt = viewModel.prepareSrt(mode)
        if (srt == null) {
            toastEmpty()
            return
        }
        RecordingShareHelper.exportTextToDownloads(
            context = context,
            content = srt,
            baseName = viewModel.exportBaseName(nameSuffix),
            extension = "srt",
            mimeType = "application/x-subrip"
        )
    }

    if (reprocess.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearReprocessError,
            title = { Text(stringResource(R.string.reprocess_error_title)) },
            text = { Text(reprocess.error!!) },
            confirmButton = {
                TextButton(onClick = viewModel::clearReprocessError) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val t = detail?.session?.previewZh?.takeIf { it.isNotBlank() }
                    Text(t ?: "Session", maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val srt = viewModel.prepareSrt(ExportTextMode.Both)
                        if (srt == null) {
                            toastEmpty()
                            return@IconButton
                        }
                        RecordingShareHelper.shareText(
                            context,
                            srt,
                            chooserTitle = context.getString(R.string.share_srt_chooser),
                            mimeType = "application/x-subrip"
                        )
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share SRT")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        val reprocessEnabled = viewModel.canReprocess() &&
                            reprocess.phase == ReprocessPhase.Idle
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reprocess_menu)) },
                            onClick = {
                                menuOpen = false
                                viewModel.startReprocessCurrent()
                            },
                            enabled = reprocessEnabled
                        )
                        if (reprocess.phase == ReprocessPhase.Running ||
                            reprocess.phase == ReprocessPhase.Cancelling
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reprocess_cancel)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.cancelReprocess()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_delete)) },
                            onClick = {
                                menuOpen = false
                                deleteConfirmOpen = true
                            },
                            enabled = reprocess.phase == ReprocessPhase.Idle
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt)) },
                            onClick = {
                                menuOpen = false
                                exportSrt(ExportTextMode.Both, "")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt_zh)) },
                            onClick = {
                                menuOpen = false
                                exportSrt(ExportTextMode.TranslationOnly, "zh")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt_en)) },
                            onClick = {
                                menuOpen = false
                                exportSrt(ExportTextMode.SourceOnly, "en")
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_zh)) },
                            onClick = {
                                menuOpen = false
                                val text = viewModel.preparePlain(ExportTextMode.TranslationOnly)
                                if (text == null) {
                                    toastEmpty()
                                } else {
                                    RecordingShareHelper.copyToClipboard(
                                        context,
                                        text,
                                        label = "translation",
                                        toastOk = context.getString(R.string.copied_zh)
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_en)) },
                            onClick = {
                                menuOpen = false
                                val text = viewModel.preparePlain(ExportTextMode.SourceOnly)
                                if (text == null) {
                                    toastEmpty()
                                } else {
                                    RecordingShareHelper.copyToClipboard(
                                        context,
                                        text,
                                        label = "source",
                                        toastOk = context.getString(R.string.copied_en)
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_audio)) },
                            onClick = {
                                menuOpen = false
                                val audio = viewModel.audioFile()
                                if (audio == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.no_audio),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    RecordingShareHelper.exportAudioToDownloads(context, audio)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_audio)) },
                            onClick = {
                                menuOpen = false
                                val audio = viewModel.audioFile()
                                if (audio == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.no_audio),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    RecordingShareHelper.shareAudio(context, audio)
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        val d = detail
        if (d == null) {
            Text(
                stringResource(R.string.loading),
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            val hasAudio = SessionAudioRecorder.fileForPath(d.session.audioPath) != null
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (reprocess.phase == ReprocessPhase.Running ||
                    reprocess.phase == ReprocessPhase.Cancelling
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            reprocess.message.ifBlank { stringResource(R.string.reprocess_running) },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = viewModel::cancelReprocess) {
                            Text(stringResource(R.string.reprocess_cancel))
                        }
                    }
                    HorizontalDivider()
                }

                OutlinedTextField(
                    value = detailQuery,
                    onValueChange = viewModel::setDetailQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.detail_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (detailQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setDetailQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                wallFmt.format(Date(d.session.createdAt)) +
                                    " → " + wallFmt.format(Date(d.session.endedAt)),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (hasAudio) {
                                Text(
                                    stringResource(R.string.history_has_audio) +
                                        " · ${FileSizeLabel(d.session.audioPath)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (playback.hasTimeline) {
                                Text(
                                    stringResource(R.string.history_tap_segment_hint),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (detailQuery.isNotBlank()) {
                                Text(
                                    "${filtered.size} / ${d.segments.size}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    if (d.segments.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.history_no_segments),
                                modifier = Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.detail_search_empty),
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { seg ->
                            val active = playback.activeSegmentId == seg.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (playback.hasTimeline && playback.prepared) {
                                            Modifier.clickable { viewModel.seekToSegment(seg) }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .background(
                                        if (active) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    "[${HistoryExport.formatOffset(seg.offsetMs)}]  " +
                                        wallFmt.format(Date(seg.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                                Text("${stringResource(R.string.label_source)}: ${seg.source}")
                                Text(
                                    "${stringResource(R.string.label_translation)}: ${seg.translation}"
                                )
                                if (seg.incomplete) {
                                    Text(stringResource(R.string.segment_incomplete))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                if (playback.hasTimeline && playback.prepared) {
                    PlaybackBar(
                        playing = playback.playing,
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onToggle = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackBar(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit
) {
    val dur = durationMs.coerceAtLeast(0)
    val pos = positionMs.coerceIn(0, if (dur > 0) dur else positionMs)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Slider(
            value = if (dur > 0) pos.toFloat() else 0f,
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..(dur.coerceAtLeast(1).toFloat()),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = onToggle) {
                Text(
                    if (playing) stringResource(R.string.playback_pause)
                    else stringResource(R.string.playback_play)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatPlaybackTime(pos)} / ${formatPlaybackTime(dur)}",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

private fun formatPlaybackTime(ms: Int): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0).toLong())
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

@Composable
private fun FileSizeLabel(path: String?): String {
    val f = SessionAudioRecorder.fileForPath(path) ?: return ""
    val kb = f.length() / 1024.0
    return if (kb >= 1024) {
        String.format(Locale.US, "%.1f MB", kb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB", kb)
    }
}
