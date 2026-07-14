package com.example.livetranslate.ui.history

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        if (sessions.isEmpty()) {
            Text(
                "No sessions yet",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(sessions, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = {
                            Text(s.previewZh.ifBlank { "(empty)" })
                        },
                        supportingContent = {
                            Text(fmt.format(Date(s.createdAt)))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    sessionId: Long,
    viewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sessionId) {
        viewModel.loadDetail(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val md = viewModel.prepareExport() ?: return@IconButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, md)
                        }
                        context.startActivity(Intent.createChooser(send, "Export translation"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                }
            )
        }
    ) { padding ->
        val d = detail
        if (d == null) {
            Text("Loading...", modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                items(d.segments, key = { it.id }) { seg ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
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
