package com.example.livetranslate.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.R
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.SegmentEntity
import com.example.livetranslate.data.history.TimelineItem
import com.example.livetranslate.domain.ReprocessPhase
import com.example.livetranslate.util.RecordingShareHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var listEditTitle by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var listSearchOpen by remember { mutableStateOf(false) }
    val listSearchFocus = remember { FocusRequester() }

    listEditTitle?.let { (id, current) ->
        TitleEditDialog(
            initial = current,
            onDismiss = { listEditTitle = null },
            onSave = { title ->
                viewModel.updateSessionTitle(title, sessionId = id)
                listEditTitle = null
            }
        )
    }

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

    LaunchedEffect(listSearchOpen) {
        if (listSearchOpen) {
            listSearchFocus.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (listSearchOpen) {
                        TopBarSearchField(
                            value = query,
                            onValueChange = viewModel::setListQuery,
                            placeholder = stringResource(R.string.history_search_hint),
                            focusRequester = listSearchFocus
                        )
                    } else {
                        Text(stringResource(R.string.history))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (listSearchOpen) {
                                listSearchOpen = false
                                viewModel.setListQuery("")
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (listSearchOpen) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setListQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    } else {
                        IconButton(onClick = { listSearchOpen = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.history_search_action)
                            )
                        }
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
                                    .combinedClickable(
                                        onClick = { onOpenDetail(s.id) },
                                        onLongClick = {
                                            listEditTitle = s.id to s.previewZh
                                        }
                                    )
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val timeline by viewModel.timelineItems.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val reprocess by viewModel.reprocessState.collectAsStateWithLifecycle()
    val deletedId by viewModel.deletedSessionId.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val translatingIds by viewModel.translatingIds.collectAsStateWithLifecycle()
    val segmentActionError by viewModel.segmentActionError.collectAsStateWithLifecycle()
    val segmentActionMessage by viewModel.segmentActionMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val wallFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var deleteSegmentsConfirm by remember { mutableStateOf<List<Long>?>(null) }
    var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var editingTitle by remember { mutableStateOf(false) }
    var detailSearchOpen by remember { mutableStateOf(false) }
    var playbackPanelOpen by remember { mutableStateOf(false) }
    val detailSearchFocus = remember { FocusRequester() }
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

    editingSegment?.let { seg ->
        SegmentEditDialog(
            segment = seg,
            onDismiss = { editingSegment = null },
            onSave = { source, translation ->
                viewModel.updateSegmentText(seg.id, source, translation)
                editingSegment = null
            }
        )
    }

    if (editingTitle) {
        val currentTitle = detail?.session?.previewZh.orEmpty()
        TitleEditDialog(
            initial = currentTitle,
            onDismiss = { editingTitle = false },
            onSave = { title ->
                viewModel.updateSessionTitle(title)
                editingTitle = false
            }
        )
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

    deleteSegmentsConfirm?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleteSegmentsConfirm = null },
            title = { Text(stringResource(R.string.history_segment_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.history_segment_delete_confirm_body, ids.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSegmentsConfirm = null
                        viewModel.deleteSegments(ids)
                    }
                ) {
                    Text(stringResource(R.string.history_segment_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSegmentsConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    LaunchedEffect(segmentActionError) {
        val err = segmentActionError ?: return@LaunchedEffect
        val msg = when (err) {
            "llm_key" -> context.getString(R.string.history_segment_llm_key_missing)
            "asr_only" -> context.getString(R.string.history_segment_asr_only)
            "retranslate_failed" -> context.getString(R.string.history_segment_retranslate_failed)
            else -> err
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        viewModel.clearSegmentActionError()
    }

    LaunchedEffect(segmentActionMessage) {
        val raw = segmentActionMessage ?: return@LaunchedEffect
        val msg = when {
            raw.startsWith("deleted:") -> {
                val n = raw.removePrefix("deleted:").toIntOrNull() ?: 0
                context.getString(R.string.history_segment_deleted, n)
            }
            raw.startsWith("retranslated:") -> {
                val parts = raw.removePrefix("retranslated:").split(":")
                val ok = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val fail = parts.getOrNull(1)?.toIntOrNull() ?: 0
                context.getString(R.string.history_segment_retranslated, ok, fail)
            }
            else -> raw
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.clearSegmentActionMessage()
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

    val canPlay = playback.hasTimeline && playback.prepared

    // Keep seek panel open while playing so progress stays visible.
    LaunchedEffect(playback.playing) {
        if (playback.playing) playbackPanelOpen = true
    }

    LaunchedEffect(detailSearchOpen) {
        if (detailSearchOpen) detailSearchFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selectionMode -> {
                            Text(stringResource(R.string.history_selection_count, selectedIds.size))
                        }
                        detailSearchOpen -> {
                            TopBarSearchField(
                                value = detailQuery,
                                onValueChange = viewModel::setDetailQuery,
                                placeholder = stringResource(R.string.detail_search_hint),
                                focusRequester = detailSearchFocus
                            )
                        }
                        else -> {
                            val t = detail?.session?.previewZh?.takeIf { it.isNotBlank() }
                            Text(
                                t ?: "Session",
                                maxLines = 1,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { editingTitle = true }
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                selectionMode -> viewModel.clearSelection()
                                detailSearchOpen -> {
                                    detailSearchOpen = false
                                    viewModel.setDetailQuery("")
                                }
                                else -> onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = viewModel::selectAllVisible) {
                            Text(stringResource(R.string.history_select_all))
                        }
                        IconButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    deleteSegmentsConfirm = selectedIds.toList()
                                }
                            },
                            enabled = selectedIds.isNotEmpty() && translatingIds.isEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.history_segment_delete)
                            )
                        }
                        IconButton(
                            onClick = viewModel::retranslateSelected,
                            enabled = selectedIds.isNotEmpty() && translatingIds.isEmpty()
                        ) {
                            Icon(
                                Icons.Default.Translate,
                                contentDescription = stringResource(
                                    R.string.history_segment_retranslate
                                )
                            )
                        }
                        TextButton(onClick = viewModel::clearSelection) {
                            Text(stringResource(R.string.history_selection_cancel))
                        }
                    } else if (detailSearchOpen) {
                        if (detailQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setDetailQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    } else {
                        TextButton(onClick = { viewModel.enterSelectionMode() }) {
                            Text(stringResource(R.string.history_multi_select))
                        }
                        IconButton(onClick = { detailSearchOpen = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.history_search_action)
                            )
                        }
                    }
                    if (!selectionMode && canPlay) {
                        IconButton(
                            onClick = {
                                // First tap expands panel; also toggles play/pause.
                                if (!playbackPanelOpen) playbackPanelOpen = true
                                viewModel.togglePlayPause()
                            }
                        ) {
                            Icon(
                                if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(
                                    if (playback.playing) R.string.playback_pause
                                    else R.string.playback_play
                                )
                            )
                        }
                    }
                    if (!selectionMode) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (canPlay) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (playbackPanelOpen) {
                                            stringResource(R.string.history_playback_hide)
                                        } else {
                                            stringResource(R.string.history_playback_expand)
                                        }
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    playbackPanelOpen = !playbackPanelOpen
                                }
                            )
                            HorizontalDivider()
                        }
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
                            text = { Text(stringResource(R.string.share_srt)) },
                            onClick = {
                                menuOpen = false
                                val srt = viewModel.prepareSrt(ExportTextMode.Both)
                                if (srt == null) {
                                    toastEmpty()
                                } else {
                                    RecordingShareHelper.shareText(
                                        context,
                                        srt,
                                        chooserTitle = context.getString(R.string.share_srt_chooser),
                                        mimeType = "application/x-subrip"
                                    )
                                }
                            }
                        )
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

                if (canPlay && playbackPanelOpen) {
                    PlaybackBar(
                        playing = playback.playing,
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onToggle = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        compact = true
                    )
                    HorizontalDivider()
                }

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
                        items(
                            timeline,
                            key = { item ->
                                when (item) {
                                    is TimelineItem.Segment -> "seg-${item.segment.id}"
                                    is TimelineItem.Silence -> item.key
                                }
                            }
                        ) { item ->
                            when (item) {
                                is TimelineItem.Silence -> {
                                    // ≥1s silence: blank row (no ellipsis)
                                    SilenceBlankRow(
                                        startMs = item.startMs,
                                        durationMs = item.durationMs
                                    )
                                    HorizontalDivider()
                                }
                                is TimelineItem.Segment -> {
                                    val seg = item.segment
                                    TimelineSegmentRow(
                                        seg = seg,
                                        active = playback.activeSegmentId == seg.id,
                                        selected = seg.id in selectedIds,
                                        selectionMode = selectionMode,
                                        translating = seg.id in translatingIds,
                                        canSeek = playback.hasTimeline && playback.prepared,
                                        wallFmt = wallFmt,
                                        onToggleSelect = { viewModel.toggleSegmentSelected(seg.id) },
                                        onSwipeSelect = { viewModel.swipeSelect(seg.id) },
                                        onSwipeDeselect = { viewModel.swipeDeselect(seg.id) },
                                        onClick = {
                                            if (selectionMode) {
                                                viewModel.toggleSegmentSelected(seg.id)
                                            } else {
                                                val text = formatSegmentClipboard(seg)
                                                if (text.isNotBlank()) {
                                                    clipboard.setText(AnnotatedString(text))
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.history_segment_copied
                                                        ),
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectionMode) {
                                                editingSegment = seg
                                            } else {
                                                viewModel.toggleSegmentSelected(seg.id)
                                            }
                                        },
                                        onSeek = { viewModel.seekToSegment(seg) },
                                        onDelete = {
                                            deleteSegmentsConfirm = listOf(seg.id)
                                        },
                                        onRetranslate = {
                                            viewModel.retranslateSegments(listOf(seg.id))
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

/**
 * Blank timeline row for silence gaps ≥1s.
 * No ellipsis / placeholder text — pure empty space so silence reads as blank.
 */
@Composable
private fun SilenceBlankRow(
    @Suppress("UNUSED_PARAMETER") startMs: Long,
    durationMs: Long
) {
    // Height scales slightly with long silence but stays compact.
    val h = when {
        durationMs >= 5_000L -> 36.dp
        durationMs >= 2_000L -> 28.dp
        else -> 20.dp
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(h)
            .padding(horizontal = 4.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineSegmentRow(
    seg: SegmentEntity,
    active: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    translating: Boolean,
    canSeek: Boolean,
    wallFmt: SimpleDateFormat,
    onToggleSelect: () -> Unit,
    onSwipeSelect: () -> Unit,
    onSwipeDeselect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSeek: () -> Unit,
    onDelete: () -> Unit,
    onRetranslate: () -> Unit
) {
    var menuOpen by remember(seg.id) { mutableStateOf(false) }
    // Swipe only active in multi-select: right = select, left = deselect.
    // Outside multi-select, no horizontal handler (scroll free; enter via toolbar「多选」).
    val swipeThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectionMode) {
                    Modifier.pointerInput(seg.id, swipeThresholdPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var horizontalLock: Boolean? = null
                            val touchSlop = viewConfiguration.touchSlop
                            val pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    if (horizontalLock == true) {
                                        when {
                                            totalX >= swipeThresholdPx -> onSwipeSelect()
                                            totalX <= -swipeThresholdPx -> onSwipeDeselect()
                                        }
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y
                                when (horizontalLock) {
                                    null -> {
                                        val dist = abs(totalX) + abs(totalY)
                                        if (dist >= touchSlop * 2f) {
                                            if (abs(totalX) >= abs(totalY) * 2.5f) {
                                                horizontalLock = true
                                                change.consume()
                                            } else {
                                                break
                                            }
                                        }
                                    }
                                    true -> change.consume()
                                    false -> break
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            IconButton(onClick = onToggleSelect) {
                Icon(
                    if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            Text(
                "[${HistoryExport.formatOffset(seg.offsetMs)}]  " +
                    wallFmt.format(Date(seg.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
            // Empty source/translation: blank (never "…")
            val src = seg.source
            val zh = seg.translation
            Text("${stringResource(R.string.label_source)}: $src")
            Text("${stringResource(R.string.label_translation)}: $zh")
            if (seg.incomplete) {
                Text(stringResource(R.string.segment_incomplete))
            }
            if (translating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 4.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.history_segment_retranslating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (!selectionMode) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.history_segment_menu)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_segment_retranslate)) },
                    onClick = {
                        menuOpen = false
                        onRetranslate()
                    },
                    enabled = !translating && seg.source.isNotBlank()
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_segment_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    enabled = !translating
                )
            }
            if (canSeek) {
                IconButton(onClick = onSeek) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.history_seek_segment)
                    )
                }
            }
        }
    }
}

/** Clipboard body: translation then source (blank lines omitted). */
private fun formatSegmentClipboard(seg: SegmentEntity): String {
    val zh = seg.translation.trim()
    val en = seg.source.trim()
    return when {
        zh.isNotEmpty() && en.isNotEmpty() -> "$zh\n$en"
        zh.isNotEmpty() -> zh
        else -> en
    }
}

@Composable
private fun SegmentEditDialog(
    segment: SegmentEntity,
    onDismiss: () -> Unit,
    onSave: (source: String, translation: String) -> Unit
) {
    var source by remember(segment.id) { mutableStateOf(segment.source) }
    var translation by remember(segment.id) { mutableStateOf(segment.translation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_edit_segment_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    label = { Text(stringResource(R.string.label_source)) },
                    minLines = 2,
                    maxLines = 6
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_translation)) },
                    minLines = 2,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(source, translation) }) {
                Text(stringResource(R.string.history_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun TitleEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_edit_title_dialog)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.history_edit_title_dialog)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title) },
                enabled = title.trim().isNotEmpty()
            ) {
                Text(stringResource(R.string.history_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun TopBarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        placeholder = { Text(placeholder) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun PlaybackBar(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    compact: Boolean = false
) {
    val dur = durationMs.coerceAtLeast(0)
    val pos = positionMs.coerceIn(0, if (dur > 0) dur else positionMs)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(
                horizontal = 12.dp,
                vertical = if (compact) 4.dp else 8.dp
            )
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
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.playback_pause else R.string.playback_play
                    )
                )
            }
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
