package com.example.livetranslate.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.settings.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val d = ui.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("ASR")
            field("API URL", d.asrBaseUrl) {
                viewModel.updateDraft { s -> s.copy(asrBaseUrl = it) }
            }
            Text(
                "完整接口(…/v1/xxx)原样用；只填站点或…/v1 则补 OpenAI 风格 path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            field("API Key", d.asrApiKey) {
                viewModel.updateDraft { s -> s.copy(asrApiKey = it) }
            }
            field("Model", d.asrModel) {
                viewModel.updateDraft { s -> s.copy(asrModel = it) }
            }
            field(
                "ASR style: OpenAiTranscriptions | ChatCompletionsAudio",
                d.asrApiStyle
            ) {
                viewModel.updateDraft { s -> s.copy(asrApiStyle = it.trim()) }
            }
            Text(
                "请求体格式：Whisper multipart / chat+base64 音频（与 URL 规则无关）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            field(
                "ASR auth: Bearer | ApiKeyHeader",
                d.asrAuthStyle
            ) {
                viewModel.updateDraft { s -> s.copy(asrAuthStyle = it.trim()) }
            }
            Button(
                onClick = {
                    viewModel.updateDraft { s ->
                        s.copy(
                            asrBaseUrl = "https://api.xiaomimimo.com/v1/chat/completions",
                            asrModel = "mimo-v2.5-asr",
                            asrApiStyle = AsrApiStyle.ChatCompletionsAudio.name,
                            asrAuthStyle = ApiAuthStyle.ApiKeyHeader.name,
                            inputLanguage = "auto"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Fill MIMO ASR defaults") }

            Spacer(Modifier.height(16.dp))
            Text("LLM (chat completions stream)")
            field("API URL", d.llmBaseUrl) {
                viewModel.updateDraft { s -> s.copy(llmBaseUrl = it) }
            }
            Text(
                "完整接口原样用；否则补 /v1/chat/completions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            field("API Key", d.llmApiKey) {
                viewModel.updateDraft { s -> s.copy(llmApiKey = it) }
            }
            field("Model", d.llmModel) {
                viewModel.updateDraft { s -> s.copy(llmModel = it) }
            }
            field(
                "LLM auth: Bearer | ApiKeyHeader",
                d.llmAuthStyle
            ) {
                viewModel.updateDraft { s -> s.copy(llmAuthStyle = it.trim()) }
            }
            multilineField(
                label = "Translation system prompt",
                value = d.llmSystemPrompt,
                onChange = { viewModel.updateDraft { s -> s.copy(llmSystemPrompt = it) } }
            )
            Text(
                text = "Placeholders: {{to}} = output language, {{from}} = input language",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Button(
                onClick = {
                    viewModel.updateDraft { s ->
                        s.copy(llmSystemPrompt = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset prompt to default")
            }

            Spacer(Modifier.height(16.dp))
            Text("Languages")
            field("Input language", d.inputLanguage) {
                viewModel.updateDraft { s -> s.copy(inputLanguage = it) }
            }
            field("Output language", d.outputLanguage) {
                viewModel.updateDraft { s -> s.copy(outputLanguage = it) }
            }

            Spacer(Modifier.height(16.dp))
            Text("VAD / chunking")
            field("Silence ms", d.silenceMs.toString()) {
                it.toIntOrNull()?.let { v ->
                    viewModel.updateDraft { s -> s.copy(silenceMs = v) }
                }
            }
            field("Max utterance ms (force cut)", d.maxUtteranceMs.toString()) {
                it.toIntOrNull()?.let { v ->
                    viewModel.updateDraft { s -> s.copy(maxUtteranceMs = v) }
                }
            }
            field("Min utterance ms", d.minUtteranceMs.toString()) {
                it.toIntOrNull()?.let { v ->
                    viewModel.updateDraft { s -> s.copy(minUtteranceMs = v) }
                }
            }
            field("Energy threshold", d.energyThreshold.toString()) {
                it.toDoubleOrNull()?.let { v ->
                    viewModel.updateDraft { s -> s.copy(energyThreshold = v) }
                }
            }
            field("Context window N", d.contextWindowSize.toString()) {
                it.toIntOrNull()?.let { v ->
                    viewModel.updateDraft { s -> s.copy(contextWindowSize = v) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            ui.savedMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
        }
    }
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true
    )
}

@Composable
private fun multilineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(140.dp),
        singleLine = false,
        minLines = 4,
        maxLines = 10
    )
}
