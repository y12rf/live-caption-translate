package com.example.livetranslate.ui.live

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.domain.model.SessionPhase

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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
    }

    fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.start()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                text = "Phase: ${state.phase.name}" +
                    (state.lastCutReason?.let { " · cut=${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelMedium
            )
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
                    onClick = { requestStart() },
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.phase == SessionPhase.Paused) "Resume" else "Start")
                }
                OutlinedButton(
                    onClick = viewModel::pause,
                    enabled = state.phase == SessionPhase.Recording ||
                        state.phase == SessionPhase.Processing,
                    modifier = Modifier.weight(1f)
                ) { Text("Pause") }
                OutlinedButton(
                    onClick = viewModel::stop,
                    enabled = state.phase != SessionPhase.Idle,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }
        }
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
