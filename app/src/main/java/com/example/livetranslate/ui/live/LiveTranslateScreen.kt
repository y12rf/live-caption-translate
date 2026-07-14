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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import com.example.livetranslate.service.RecordingService
import com.example.livetranslate.service.SubtitleOverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslateScreen(
    viewModel: LiveTranslateViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val enScroll = rememberScrollState()
    val zhScroll = rememberScrollState()
    var pendingStartSource by remember { mutableStateOf(AudioSourceType.Microphone) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (audioOk) {
            beginCapture(context, viewModel, pendingStartSource, null, null)
        }
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
        }
    }

    fun requestStart(source: AudioSourceType) {
        pendingStartSource = source
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return
            }
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
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
                title = { Text("Live Translate") },
                actions = {
                    IconButton(onClick = {
                        val md = viewModel.exportMarkdown() ?: return@IconButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, md)
                        }
                        context.startActivity(Intent.createChooser(send, "Export dialogue"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
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
                    (state.lastCutReason?.let { " · cut=${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                FilterChip(
                    selected = state.audioSource == AudioSourceType.Microphone,
                    onClick = {
                        if (state.phase == SessionPhase.Idle) {
                            viewModel.setAudioSource(AudioSourceType.Microphone)
                        }
                    },
                    label = { Text("Mic") },
                    enabled = state.phase == SessionPhase.Idle
                )
                FilterChip(
                    selected = state.audioSource == AudioSourceType.Internal,
                    onClick = {
                        if (state.phase == SessionPhase.Idle) {
                            viewModel.setAudioSource(AudioSourceType.Internal)
                        }
                    },
                    label = { Text("Internal") },
                    enabled = state.phase == SessionPhase.Idle &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Floating bilingual captions")
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
            Text("English", fontWeight = FontWeight.Bold)
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
            Text("中文", fontWeight = FontWeight.Bold)
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
                if (state.canRetry) {
                    OutlinedButton(onClick = viewModel::retry) {
                        Text("Retry last utterance")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val canStart = state.phase == SessionPhase.Idle ||
                    state.phase == SessionPhase.Paused
                Button(
                    onClick = {
                        if (state.phase == SessionPhase.Paused) {
                            RecordingService.start(context, state.audioSource)
                        } else {
                            requestStart(state.audioSource)
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.phase == SessionPhase.Paused) "Resume" else "Start")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.pause()
                    },
                    enabled = state.phase == SessionPhase.Recording ||
                        state.phase == SessionPhase.Processing,
                    modifier = Modifier.weight(1f)
                ) { Text("Pause") }
                OutlinedButton(
                    onClick = {
                        RecordingService.stop(context)
                        viewModel.stop()
                        SubtitleOverlayService.stop(context)
                    },
                    enabled = state.phase != SessionPhase.Idle,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
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
    if (source == AudioSourceType.Internal && resultCode != null && data != null) {
        RecordingService.start(context, source, resultCode, data)
    } else {
        RecordingService.start(context, source)
    }
    if (viewModel.state.value.overlayEnabled) {
        SubtitleOverlayService.start(context)
    }
}

private fun buildDisplay(cumulative: String, partial: String): String {
    return when {
        cumulative.isEmpty() && partial.isEmpty() -> "..."
        cumulative.isEmpty() -> partial
        partial.isEmpty() -> cumulative
        else -> cumulative + "\n" + partial
    }
}
