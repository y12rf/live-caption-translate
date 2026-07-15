package com.example.livetranslate.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.R
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.util.RecordingShareHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
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
                        "No sessions yet",
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
    onBack: () -> Unit
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val detailQuery by viewModel.detailQuery.collectAsStateWithLifecycle()
    val filtered by viewModel.filteredSegments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wallFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.loadDetail(sessionId)
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
                            chooserTitle = "分享 SRT",
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
            Text("Loading...", modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            val hasAudio = SessionAudioRecorder.fileForPath(d.session.audioPath) != null
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
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
                    modifier = Modifier
                        .fillMaxSize()
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
                                "(无文稿段落，仅有录音)",
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
                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                Text(
                                    "[${HistoryExport.formatOffset(seg.offsetMs)}]  " +
                                        wallFmt.format(Date(seg.createdAt)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text("EN: ${seg.source}")
                                Text("ZH: ${seg.translation}")
                                if (seg.incomplete) Text("(incomplete)")
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
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
