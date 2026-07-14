package com.example.livetranslate.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }

    fun showInfo(title: String, body: String) {
        infoTitle = title
        infoBody = body
    }

    if (infoTitle != null) {
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            title = { Text(infoTitle!!) },
            text = { Text(infoBody) },
            confirmButton = {
                TextButton(onClick = { infoTitle = null }) {
                    Text("OK")
                }
            }
        )
    }

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
            sectionTitle("ASR")
            field(
                label = "API URL",
                value = d.asrBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(asrBaseUrl = it) } },
                onInfo = {
                    showInfo(
                        "ASR API URL",
                        "若 URL 在 /v1/ 后面还有路径（完整接口），则原样使用。\n" +
                            "若只填站点或只到 …/v1，则按 OpenAI 风格自动补 path：\n" +
                            "• OpenAiTranscriptions → /v1/audio/transcriptions\n" +
                            "• ChatCompletionsAudio → /v1/chat/completions"
                    )
                }
            )
            field(
                label = "API Key",
                value = d.asrApiKey,
                onChange = { viewModel.updateDraft { s -> s.copy(asrApiKey = it) } }
            )
            field(
                label = "Model",
                value = d.asrModel,
                onChange = { viewModel.updateDraft { s -> s.copy(asrModel = it) } }
            )
            dropdownField(
                label = "ASR style",
                value = d.asrApiStyle,
                options = AsrApiStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(asrApiStyle = it) } },
                onInfo = {
                    showInfo(
                        "ASR style",
                        "• OpenAiTranscriptions — multipart 上传 WAV（经典 Whisper）\n" +
                            "• ChatCompletionsAudio — chat + base64 input_audio（如 MIMO）\n\n" +
                            "只影响请求体格式，与 URL 解析规则无关。"
                    )
                }
            )
            dropdownField(
                label = "ASR auth",
                value = d.asrAuthStyle,
                options = ApiAuthStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(asrAuthStyle = it) } },
                onInfo = {
                    showInfo(
                        "ASR auth",
                        "• Bearer — Authorization: Bearer <key>\n" +
                            "• ApiKeyHeader — api-key: <key>（如小米 MIMO）"
                    )
                }
            )

            Spacer(Modifier.height(16.dp))
            sectionTitle("LLM")
            field(
                label = "API URL",
                value = d.llmBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(llmBaseUrl = it) } },
                onInfo = {
                    showInfo(
                        "LLM API URL",
                        "若 /v1/ 后已有路径则原样使用。\n" +
                            "否则补全为 OpenAI 风格：/v1/chat/completions"
                    )
                }
            )
            field(
                label = "API Key",
                value = d.llmApiKey,
                onChange = { viewModel.updateDraft { s -> s.copy(llmApiKey = it) } }
            )
            field(
                label = "Model",
                value = d.llmModel,
                onChange = { viewModel.updateDraft { s -> s.copy(llmModel = it) } }
            )
            dropdownField(
                label = "LLM auth",
                value = d.llmAuthStyle,
                options = ApiAuthStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(llmAuthStyle = it) } },
                onInfo = {
                    showInfo(
                        "LLM auth",
                        "有 API Key ≠ 选 ApiKeyHeader。\n\n" +
                            "• Bearer（DeepSeek / OpenAI 等）— 请求头：\n" +
                            "  Authorization: Bearer <你的Key>\n" +
                            "• ApiKeyHeader — 请求头名就是 api-key（部分网关 curl 写法）\n\n" +
                            "DeepSeek 请选 Bearer。"
                    )
                }
            )
            Button(
                onClick = {
                    viewModel.updateDraft { s ->
                        s.copy(
                            llmBaseUrl = "https://api.deepseek.com",
                            llmModel = "deepseek-chat",
                            llmAuthStyle = ApiAuthStyle.Bearer.name
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Fill DeepSeek LLM defaults") }
            multilineField(
                label = "Translation system prompt",
                value = d.llmSystemPrompt,
                onChange = { viewModel.updateDraft { s -> s.copy(llmSystemPrompt = it) } },
                onInfo = {
                    showInfo(
                        "System prompt",
                        "翻译用系统提示词。占位符：\n" +
                            "• {{to}} — 输出语言（设置里的 Output language）\n" +
                            "• {{from}} — 输入语言（Input language）"
                    )
                }
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
            sectionTitle("Languages")
            field(
                label = "Input language",
                value = d.inputLanguage,
                onChange = { viewModel.updateDraft { s -> s.copy(inputLanguage = it) } }
            )
            field(
                label = "Output language",
                value = d.outputLanguage,
                onChange = { viewModel.updateDraft { s -> s.copy(outputLanguage = it) } }
            )

            Spacer(Modifier.height(16.dp))
            sectionTitle(
                title = "VAD / chunking",
                onInfo = {
                    showInfo(
                        "VAD / chunking",
                        "能量 VAD 切句参数：\n" +
                            "• Silence ms — 静音持续多久判为一句结束\n" +
                            "• Max utterance ms — 最长采样，超时强制截断\n" +
                            "• Min utterance ms — 过短片段丢弃\n" +
                            "• Energy threshold — 能量阈值\n" +
                            "• Context window N — LLM 滑动上下文句数"
                    )
                }
            )
            field(
                label = "Silence ms",
                value = d.silenceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(silenceMs = v) }
                    }
                }
            )
            field(
                label = "Max utterance ms",
                value = d.maxUtteranceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(maxUtteranceMs = v) }
                    }
                }
            )
            field(
                label = "Min utterance ms",
                value = d.minUtteranceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(minUtteranceMs = v) }
                    }
                }
            )
            field(
                label = "Energy threshold",
                value = d.energyThreshold.toString(),
                onChange = {
                    it.toDoubleOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(energyThreshold = v) }
                    }
                }
            )
            field(
                label = "Context window N",
                value = d.contextWindowSize.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(contextWindowSize = v) }
                    }
                }
            )

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
private fun sectionTitle(title: String, onInfo: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(title, modifier = Modifier.weight(1f))
        if (onInfo != null) {
            InfoButton(onClick = onInfo)
        }
    }
}

@Composable
private fun InfoButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Info"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun dropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    // Keep display valid even if stored value is unknown
    val display = if (options.contains(value)) value else (options.firstOrNull() ?: value)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onInfo != null) {
                        IconButton(onClick = onInfo) {
                            Icon(Icons.Outlined.Info, contentDescription = "Info")
                        }
                    }
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Expand")
                }
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true,
        trailingIcon = if (onInfo != null) {
            { InfoButton(onClick = onInfo) }
        } else {
            null
        }
    )
}

@Composable
private fun multilineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onInfo: (() -> Unit)? = null
) {
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
        maxLines = 10,
        trailingIcon = if (onInfo != null) {
            { InfoButton(onClick = onInfo) }
        } else {
            null
        }
    )
}
