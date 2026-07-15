package com.example.livetranslate.ui.live

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.domain.ReprocessPhase
import com.example.livetranslate.R
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import com.example.livetranslate.service.RecordingService
import com.example.livetranslate.service.SubtitleOverlayService
import com.example.livetranslate.util.RecordingShareHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslateScreen(
    viewModel: LiveTranslateViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val orphan by viewModel.orphanPrompt.collectAsStateWithLifecycle()
    val reprocess by viewModel.reprocessState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val enScroll = rememberScrollState()
    val zhScroll = rememberScrollState()
    var pendingStartSource by remember { mutableStateOf(AudioSourceType.Microphone) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var stopDialogOpen by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkOrphans()
    }

    LaunchedEffect(reprocess.lastSavedSessionId) {
        val id = reprocess.lastSavedSessionId ?: return@LaunchedEffect
        viewModel.clearReprocessSaved()
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.reprocess_saved_id, id),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    if (orphan != null) {
        AlertDialog(
            onDismissRequest = { /* require explicit action */ },
            title = { Text(stringResource(R.string.orphan_title)) },
            text = {
                Text(
                    stringResource(R.string.orphan_message) + "\n\n${orphan!!.label}"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::orphanReprocess) {
                    Text(stringResource(R.string.orphan_reprocess))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::orphanDiscard) {
                        Text(stringResource(R.string.orphan_discard))
                    }
                    TextButton(onClick = viewModel::orphanLater) {
                        Text(stringResource(R.string.orphan_later))
                    }
                }
            }
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

    fun toastExportEmpty() {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.export_empty),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            beginCapture(
                context,
                viewModel,
                AudioSourceType.Internal,
                result.resultCode,
                result.data
            )
        } else {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.projection_denied),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun launchInternalProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.file_pick_cancelled),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return@rememberLauncherForActivityResult
        }
        try {
            // Persist read permission across process restarts when possible
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers do not support persistable grants; open stream still works now.
        }
        viewModel.startFromFile(uri)
        // File import uses offline Re pipeline (no live subtitle stream).
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.file_import_started),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioOk) return@rememberLauncherForActivityResult
        // After mic permission, Internal still needs MediaProjection dialog.
        when (pendingStartSource) {
            AudioSourceType.Internal -> launchInternalProjection()
            AudioSourceType.Microphone ->
                beginCapture(context, viewModel, AudioSourceType.Microphone, null, null)
            AudioSourceType.File ->
                filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
        }
    }

    fun requestStart(source: AudioSourceType) {
        pendingStartSource = source
        if (source == AudioSourceType.File) {
            // SAF picker — no RECORD_AUDIO required
            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (source == AudioSourceType.Internal) {
            launchInternalProjection()
        } else {
            beginCapture(context, viewModel, source, null, null)
        }
    }

    LaunchedEffect(state.cumulativeEn, state.partialEn) {
        enScroll.animateScrollTo(enScroll.maxValue)
    }
    LaunchedEffect(state.cumulativeZh, state.partialZh) {
        zhScroll.animateScrollTo(zhScroll.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                actions = {
                    IconButton(onClick = {
                        val srt = viewModel.exportSrt(ExportTextMode.Both)
                        if (srt == null) {
                            toastExportEmpty()
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
                    IconButton(onClick = { exportMenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Export")
                    }
                    DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt)) },
                            onClick = {
                                exportMenuOpen = false
                                val srt = viewModel.exportSrt(ExportTextMode.Both)
                                if (srt == null) toastExportEmpty()
                                else RecordingShareHelper.exportTextToDownloads(
                                    context, srt, "live_${System.currentTimeMillis()}", "srt"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt_zh)) },
                            onClick = {
                                exportMenuOpen = false
                                val srt = viewModel.exportSrt(ExportTextMode.TranslationOnly)
                                if (srt == null) toastExportEmpty()
                                else RecordingShareHelper.exportTextToDownloads(
                                    context, srt, "live_${System.currentTimeMillis()}_zh", "srt"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_srt_en)) },
                            onClick = {
                                exportMenuOpen = false
                                val srt = viewModel.exportSrt(ExportTextMode.SourceOnly)
                                if (srt == null) toastExportEmpty()
                                else RecordingShareHelper.exportTextToDownloads(
                                    context, srt, "live_${System.currentTimeMillis()}_en", "srt"
                                )
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_zh)) },
                            onClick = {
                                exportMenuOpen = false
                                val t = viewModel.exportPlain(ExportTextMode.TranslationOnly)
                                if (t == null) toastExportEmpty()
                                else RecordingShareHelper.copyToClipboard(
                                    context, t, "translation",
                                    context.getString(R.string.copied_zh)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_en)) },
                            onClick = {
                                exportMenuOpen = false
                                val t = viewModel.exportPlain(ExportTextMode.SourceOnly)
                                if (t == null) toastExportEmpty()
                                else RecordingShareHelper.copyToClipboard(
                                    context, t, "source",
                                    context.getString(R.string.copied_en)
                                )
                            }
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Phase: ${state.phase.name} · ${HistoryExport.formatOffset(state.recordedElapsedMs)}" +
                    (state.lastCutReason?.let { " · cut=${it.name}" } ?: "") +
                    (state.sessionTitle?.let { " · $it" } ?: "") +
                    if (state.draining) " · draining" else "",
                style = MaterialTheme.typography.labelMedium
            )
            state.importStatus?.let { status ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (reprocess.phase == ReprocessPhase.Running ||
                reprocess.phase == ReprocessPhase.Cancelling
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = reprocess.message.ifBlank {
                        stringResource(R.string.reprocess_running)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                reprocess.sessionTitle?.let { t ->
                    Text(
                        text = stringResource(R.string.session_title_prefix, t),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Network / backlog banner
            val backlog = state.asrQueueDepth + state.llmQueueDepth + state.diskQueueDepth
            if (!state.networkOnline || backlog > 0 || state.failedCount > 0 || state.droppedUtterances > 0) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (!state.networkOnline) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .padding(8.dp)
                ) {
                    if (!state.networkOnline) {
                        Text(
                            stringResource(R.string.net_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (backlog > 0 || state.draining) {
                        Text(
                            stringResource(
                                R.string.net_backlog,
                                state.asrQueueDepth,
                                state.llmQueueDepth,
                                state.diskQueueDepth
                            ) + if (state.draining) {
                                " · " + stringResource(R.string.session_draining)
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.droppedUtterances > 0) {
                        Text(
                            stringResource(R.string.dropped_utt, state.droppedUtterances),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (state.failedCount > 0) {
                        Text(
                            stringResource(
                                R.string.net_failed,
                                state.failedCount,
                                state.failedPreview.orEmpty()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::retry) {
                                Text(stringResource(R.string.retry_one))
                            }
                            TextButton(onClick = viewModel::retryAllFailed) {
                                Text(stringResource(R.string.retry_all))
                            }
                            TextButton(onClick = viewModel::dismissFailures) {
                                Text(stringResource(R.string.dismiss_fail))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.source_label), style = MaterialTheme.typography.labelLarge)
                FilterChip(
                    selected = state.audioSource == AudioSourceType.Microphone,
                    onClick = {
                        if (state.phase == SessionPhase.Idle) {
                            viewModel.setAudioSource(AudioSourceType.Microphone)
                        }
                    },
                    label = { Text(stringResource(R.string.source_mic)) },
                    enabled = state.phase == SessionPhase.Idle
                )
                FilterChip(
                    selected = state.audioSource == AudioSourceType.Internal,
                    onClick = {
                        if (state.phase == SessionPhase.Idle) {
                            viewModel.setAudioSource(AudioSourceType.Internal)
                        }
                    },
                    label = { Text(stringResource(R.string.source_internal)) },
                    enabled = state.phase == SessionPhase.Idle &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                )
                FilterChip(
                    selected = state.audioSource == AudioSourceType.File,
                    onClick = {
                        if (state.phase == SessionPhase.Idle) {
                            viewModel.setAudioSource(AudioSourceType.File)
                        }
                    },
                    label = { Text(stringResource(R.string.source_file)) },
                    enabled = state.phase == SessionPhase.Idle
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.overlay_enable))
                Switch(
                    checked = state.overlayEnabled,
                    onCheckedChange = { on ->
                        if (on) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                !Settings.canDrawOverlays(context)
                            ) {
                                val i = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(i)
                            } else {
                                viewModel.setOverlayEnabled(true)
                                SubtitleOverlayService.start(context)
                            }
                        } else {
                            viewModel.setOverlayEnabled(false)
                            SubtitleOverlayService.stop(context)
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.live_panel_source), fontWeight = FontWeight.Bold)
            Text(
                text = buildDisplay(state.cumulativeEn, state.partialEn),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(enScroll)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.live_panel_translation), fontWeight = FontWeight.Bold)
            Text(
                text = buildDisplay(state.cumulativeZh, state.partialZh),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(zhScroll)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )

            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
            if (state.canRetrySave) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.saveError ?: "保存失败",
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = viewModel::retrySave) {
                    Text(stringResource(R.string.retry_save))
                }
            }

            if (stopDialogOpen) {
                val pending =
                    state.asrQueueDepth + state.llmQueueDepth + state.diskQueueDepth + state.failedCount
                AlertDialog(
                    onDismissRequest = { stopDialogOpen = false },
                    title = { Text(stringResource(R.string.stop_title)) },
                    text = {
                        Text(
                            stringResource(R.string.stop_message) +
                                if (pending > 0) {
                                    stringResource(R.string.stop_pending_extra, pending)
                                } else {
                                    ""
                                }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            stopDialogOpen = false
                            if (state.audioSource == AudioSourceType.File) {
                                viewModel.stop(drain = true)
                            } else {
                                RecordingService.stop(context, drain = true)
                            }
                            SubtitleOverlayService.stop(context)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.session_draining),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Text(stringResource(R.string.stop_drain))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            stopDialogOpen = false
                            if (state.audioSource == AudioSourceType.File) {
                                viewModel.stop(drain = false)
                            } else {
                                RecordingService.stop(context, drain = false)
                            }
                            SubtitleOverlayService.stop(context)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.session_saved_with_audio),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Text(stringResource(R.string.stop_now))
                        }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val reprocessBusy = reprocess.phase == ReprocessPhase.Running ||
                    reprocess.phase == ReprocessPhase.Cancelling
                val canStart = (state.phase == SessionPhase.Idle ||
                    state.phase == SessionPhase.Paused) && !reprocessBusy
                Button(
                    onClick = {
                        if (state.phase == SessionPhase.Paused) {
                            // Reuse existing FGS + MediaProjection; never re-run START without token.
                            try {
                                RecordingService.resume(context)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    e.message ?: context.getString(R.string.resume_failed),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            requestStart(state.audioSource)
                        }
                    },
                    enabled = canStart && !state.draining,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            state.phase == SessionPhase.Paused ->
                                stringResource(R.string.resume)
                            state.audioSource == AudioSourceType.File ->
                                stringResource(R.string.start_file)
                            else -> stringResource(R.string.start)
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        viewModel.pause()
                    },
                    enabled = state.audioSource != AudioSourceType.File &&
                        (state.phase == SessionPhase.Recording ||
                            state.phase == SessionPhase.Processing) &&
                        !state.draining &&
                        !reprocessBusy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.pause)) }
                OutlinedButton(
                    onClick = {
                        if (reprocessBusy || state.audioSource == AudioSourceType.File) {
                            // Offline file / reprocess: cancel without drain dialog
                            viewModel.stop(drain = false)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.reprocess_cancel),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            stopDialogOpen = true
                        }
                    },
                    enabled = (state.phase != SessionPhase.Idle || reprocessBusy) &&
                        !state.draining,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.stop)) }
            }
        }
    }
}

private fun beginCapture(
    context: android.content.Context,
    viewModel: LiveTranslateViewModel,
    source: AudioSourceType,
    resultCode: Int?,
    data: Intent?
) {
    viewModel.setAudioSource(source)
    try {
        if (source == AudioSourceType.Internal) {
            if (resultCode == null || data == null) {
                android.widget.Toast.makeText(
                    context,
                    "内部录音缺少系统授权结果，请重新点 Start 并允许录制",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            RecordingService.start(context, source, resultCode, data)
        } else {
            RecordingService.start(context, source)
        }
        if (viewModel.state.value.overlayEnabled) {
            SubtitleOverlayService.start(context)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            e.message ?: "启动录音失败",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * Live bilingual panel text. [partial] is in-flight only; if it already equals the
 * last cumulative block (commit race), do not append again.
 */
internal fun buildDisplay(cumulative: String, partial: String): String {
    val p = partial.trim()
    val c = cumulative.trimEnd()
    return when {
        c.isEmpty() && p.isEmpty() -> "..."
        c.isEmpty() -> p
        p.isEmpty() -> c
        // Already committed as last line / whole buffer
        c == p || c.endsWith("\n$p") -> c
        else -> "$c\n$p"
    }
}
