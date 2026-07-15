package com.example.livetranslate.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.R
import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.llm.LlmThinkingMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.util.KeepAliveHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val d = ui.draft
    val context = LocalContext.current
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }
    var ignoringBattery by remember {
        mutableStateOf(KeepAliveHelper.isIgnoringBatteryOptimizations(context))
    }

    // Refresh status when returning from system battery settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoringBattery = KeepAliveHelper.isIgnoringBatteryOptimizations(context)
    }

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
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
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
            val uiLangSection = stringResource(R.string.ui_language_section)
            val uiLangInfo = stringResource(R.string.ui_language_info)
            val apiUrlLabel = stringResource(R.string.settings_api_url)
            val apiUrlInfo = stringResource(R.string.settings_info_asr_url)
            val fullUrlTitle = stringResource(R.string.settings_full_url_title)
            val fullUrlInfo = stringResource(R.string.settings_info_full_url)
            val promptTitle = stringResource(R.string.settings_prompt_title)
            val promptInfo = stringResource(R.string.settings_info_system_prompt)

            sectionTitle(
                title = uiLangSection,
                onInfo = { showInfo(uiLangSection, uiLangInfo) }
            )
            val enLabel = stringResource(R.string.ui_language_en)
            val zhLabel = stringResource(R.string.ui_language_zh)
            val uiLangDisplay =
                if (com.example.livetranslate.util.AppLocale.normalize(d.uiLanguage) ==
                    com.example.livetranslate.util.AppLocale.ZH
                ) {
                    zhLabel
                } else {
                    enLabel
                }
            dropdownField(
                label = stringResource(R.string.ui_language),
                value = uiLangDisplay,
                options = listOf(enLabel, zhLabel),
                onSelect = { sel ->
                    val code = if (sel == zhLabel) {
                        com.example.livetranslate.util.AppLocale.ZH
                    } else {
                        com.example.livetranslate.util.AppLocale.EN
                    }
                    viewModel.updateDraft { s -> s.copy(uiLanguage = code) }
                }
            )
            Spacer(Modifier.height(16.dp))

            sectionTitle(stringResource(R.string.settings_asr))
            field(
                label = apiUrlLabel,
                value = d.asrBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(asrBaseUrl = it) } },
                onInfo = { showInfo(apiUrlLabel, apiUrlInfo) }
            )
            switchField(
                label = stringResource(R.string.settings_full_url),
                checked = d.asrFullUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(asrFullUrl = it) } },
                onInfo = { showInfo(fullUrlTitle, fullUrlInfo) }
            )
            field(
                label = stringResource(R.string.settings_api_key),
                value = d.asrApiKey,
                onChange = { viewModel.updateDraft { s -> s.copy(asrApiKey = it) } }
            )
            field(
                label = stringResource(R.string.settings_model),
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
            Button(
                onClick = viewModel::testAsrLatency,
                enabled = !ui.asrLatencyTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ui.asrLatencyTesting) stringResource(R.string.settings_testing_asr)
                    else stringResource(R.string.settings_test_asr)
                )
            }
            ui.asrLatencyResult?.let {
                Spacer(Modifier.height(4.dp))
                Text(it)
            }

            Spacer(Modifier.height(16.dp))
            sectionTitle("LLM")
            field(
                label = "API URL",
                value = d.llmBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(llmBaseUrl = it) } },
                onInfo = {
                    showInfo(
                        "LLM API URL",
                        "默认：若 /v1/ 后已有路径则原样使用；\n" +
                            "否则补全为 OpenAI 风格：/v1/chat/completions\n\n" +
                            "打开「完整 URL」后：不做任何路径拼接，按填写内容原样请求。"
                    )
                }
            )
            switchField(
                label = "完整 URL（不拼接 path）",
                checked = d.llmFullUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(llmFullUrl = it) } },
                onInfo = {
                    showInfo(
                        "完整 URL",
                        "开启后，上方 API URL 将原样作为请求地址，不再拼接 /v1/chat/completions。"
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
            dropdownField(
                label = "Thinking",
                value = d.llmThinking,
                options = LlmThinkingMode.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(llmThinking = it) } },
                onInfo = {
                    showInfo(
                        "Thinking",
                        "控制请求体中的 thinking 字段：\n" +
                            "• Default — 不带 thinking 字段（默认）\n" +
                            "• True — 发送 \"thinking\": true\n" +
                            "• False — 发送 \"thinking\": false"
                    )
                }
            )
            Button(
                onClick = viewModel::testLlmLatency,
                enabled = !ui.llmLatencyTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (ui.llmLatencyTesting) stringResource(R.string.settings_testing_llm)
                    else stringResource(R.string.settings_test_llm)
                )
            }
            ui.llmLatencyResult?.let {
                Spacer(Modifier.height(4.dp))
                Text(it)
            }
            multilineField(
                label = stringResource(R.string.settings_system_prompt),
                value = d.llmSystemPrompt,
                onChange = { viewModel.updateDraft { s -> s.copy(llmSystemPrompt = it) } },
                onInfo = { showInfo(promptTitle, promptInfo) }
            )
            Button(
                onClick = {
                    viewModel.updateDraft { s ->
                        s.copy(llmSystemPrompt = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_reset_prompt))
            }
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s ->
                        if (s.llmSystemPrompt.contains("{{glossary}}")) s
                        else s.copy(
                            llmSystemPrompt = s.llmSystemPrompt.trimEnd() +
                                "\n术语表（须优先遵守；可为空）：\n{{glossary}}"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.glossary_insert_placeholder))
            }

            Spacer(Modifier.height(16.dp))
            sectionTitle(
                title = stringResource(R.string.glossary_section),
                onInfo = {
                    showInfo(
                        "术语表",
                        "全局源→译对照，经 {{glossary}} 注入 LLM system prompt。\n" +
                            "空术语表时 {{glossary}} 渲染为空。\n最多 100 条。"
                    )
                }
            )
            d.glossaryTerms.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = entry.source,
                        onValueChange = { v ->
                            viewModel.updateDraft { s ->
                                val list = s.glossaryTerms.toMutableList()
                                list[index] = list[index].copy(source = v)
                                s.copy(glossaryTerms = list)
                            }
                        },
                        label = { Text(stringResource(R.string.glossary_source)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = entry.target,
                        onValueChange = { v ->
                            viewModel.updateDraft { s ->
                                val list = s.glossaryTerms.toMutableList()
                                list[index] = list[index].copy(target = v)
                                s.copy(glossaryTerms = list)
                            }
                        },
                        label = { Text(stringResource(R.string.glossary_target)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            viewModel.updateDraft { s ->
                                s.copy(
                                    glossaryTerms = s.glossaryTerms.filterIndexed { i, _ -> i != index }
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.glossary_remove))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s ->
                        if (s.glossaryTerms.size >= 100) s
                        else s.copy(
                            glossaryTerms = s.glossaryTerms +
                                com.example.livetranslate.data.settings.GlossaryEntry("", "")
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.glossary_add))
            }

            Spacer(Modifier.height(16.dp))
            sectionTitle(stringResource(R.string.settings_languages))
            field(
                label = stringResource(R.string.settings_input_lang),
                value = d.inputLanguage,
                onChange = { viewModel.updateDraft { s -> s.copy(inputLanguage = it) } }
            )
            field(
                label = stringResource(R.string.settings_output_lang),
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
            sectionTitle(
                title = "Overlay (悬浮窗)",
                onInfo = {
                    showInfo(
                        "悬浮窗",
                        "控制字幕悬浮窗外观：\n" +
                            "• 默认：译文在上、原文在下，中间横线分割\n" +
                            "• 「译文在上」关闭后可调换为原文在上\n" +
                            "• Max width / height dp — 最大宽高\n" +
                            "• Alpha % — 背景不透明度 0–100\n" +
                            "• 颜色 — 十六进制 #RRGGBB（如 #FFFFFF）\n" +
                            "  背景色的透明度仍由 Alpha % 控制\n\n" +
                            "交互：在录音通知中点 Unlock/Lock overlay；\n" +
                            "解锁后可拖动悬浮窗，锁定后固定且触摸穿透。"
                    )
                }
            )
            switchField(
                label = "译文在上 / 原文在下",
                checked = d.overlayTranslationOnTop,
                onChange = {
                    viewModel.updateDraft { s -> s.copy(overlayTranslationOnTop = it) }
                },
                onInfo = {
                    showInfo(
                        "字幕顺序",
                        "开启（默认）：译文在上，原文在下，中间横线分隔。\n" +
                            "关闭：原文在上，译文在下。"
                    )
                }
            )
            field(
                label = "Max width dp",
                value = d.overlayMaxWidthDp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayMaxWidthDp = v) }
                    }
                }
            )
            field(
                label = "Max height dp",
                value = d.overlayMaxHeightDp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayMaxHeightDp = v) }
                    }
                }
            )
            field(
                label = "Alpha % (0-100)",
                value = d.overlayAlphaPercent.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayAlphaPercent = v) }
                    }
                }
            )
            field(
                label = "Background color",
                value = d.overlayBgColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayBgColor = it) } }
            )
            field(
                label = "EN text color",
                value = d.overlayEnTextColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayEnTextColor = it) } }
            )
            field(
                label = "ZH text color",
                value = d.overlayZhTextColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayZhTextColor = it) } }
            )
            Button(
                onClick = {
                    viewModel.updateDraft { s ->
                        s.copy(
                            overlayBgColor = UserSettings.DEFAULT_OVERLAY_BG,
                            overlayEnTextColor = UserSettings.DEFAULT_OVERLAY_EN,
                            overlayZhTextColor = UserSettings.DEFAULT_OVERLAY_ZH,
                            overlayAlphaPercent = 80,
                            overlayTranslationOnTop = true
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset overlay colors") }

            Spacer(Modifier.height(16.dp))
            sectionTitle(
                title = stringResource(R.string.keepalive_section),
                onInfo = {
                    showInfo(
                        "保活 / 电池",
                        "长时间录音/同传时，系统与厂商省电策略可能杀掉后台服务。\n\n" +
                            "建议依次开启：\n" +
                            "1. 忽略电池优化（最重要）\n" +
                            "2. 厂商自启动 / 后台运行白名单（小米/华为/OPPO/Vivo 等）\n" +
                            "3. 锁定任务（从多任务界面下拉加锁，若系统支持）\n\n" +
                            "录音时 App 还会持有：\n" +
                            "• 前台服务通知\n" +
                            "• Partial WakeLock（防 CPU 休眠）\n" +
                            "• WifiLock（稳定 ASR/LLM 网络）"
                    )
                }
            )
            Text(
                text = if (ignoringBattery) {
                    stringResource(R.string.keepalive_battery_ok)
                } else {
                    stringResource(R.string.keepalive_battery_need)
                },
                color = if (ignoringBattery) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (!ignoringBattery) {
                Button(
                    onClick = {
                        KeepAliveHelper.requestIgnoreBatteryOptimizations(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.keepalive_request_battery))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        KeepAliveHelper.openBatteryOptimizationSettings(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.keepalive_open_battery_list))
                }
            }
            OutlinedButton(
                onClick = {
                    val opened = KeepAliveHelper.openOemAutoStartSettings(context)
                    if (!opened) {
                        Toast.makeText(
                            context,
                            "未找到厂商页面，已打开应用详情",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(stringResource(R.string.keepalive_oem_autostart))
            }
            OutlinedButton(
                onClick = { KeepAliveHelper.openAppDetailsSettings(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(stringResource(R.string.keepalive_app_details))
            }

            Spacer(Modifier.height(16.dp))
            sectionTitle(
                title = stringResource(R.string.cache_section),
                onInfo = {
                    showInfo(
                        "缓存与数据",
                        "• 清除缓存：清空本场翻译内存缓存，并删除未关联历史的孤立录音文件。\n" +
                            "• 清除全部历史：删除所有历史会话与录音（不可恢复）。"
                    )
                }
            )
            Button(
                onClick = viewModel::clearCache,
                enabled = !ui.cacheBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    if (ui.cacheBusy) stringResource(R.string.cache_busy)
                    else stringResource(R.string.clear_cache)
                )
            }
            var confirmClearHistory by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { confirmClearHistory = true },
                enabled = !ui.cacheBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(stringResource(R.string.clear_all_history))
            }
            if (confirmClearHistory) {
                AlertDialog(
                    onDismissRequest = { confirmClearHistory = false },
                    title = { Text(stringResource(R.string.clear_all_history)) },
                    text = { Text(stringResource(R.string.clear_all_history_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClearHistory = false
                            viewModel.clearAllHistory()
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearHistory = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            ui.cacheMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            val pipelineTitle = stringResource(R.string.settings_pipeline)
            val pipelineInfo = stringResource(R.string.settings_pipeline_info)
            val displayTitle = stringResource(R.string.settings_display)
            val displayInfo = stringResource(R.string.settings_display_info)
            sectionTitle(
                title = pipelineTitle,
                onInfo = { showInfo(pipelineTitle, pipelineInfo) }
            )
            field(
                label = stringResource(R.string.settings_offline_vad_batch),
                value = d.offlineVadBatchSize.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(offlineVadBatchSize = v) }
                    }
                }
            )
            field(
                label = stringResource(R.string.settings_title_threshold),
                value = d.titleTurnThreshold.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(titleTurnThreshold = v) }
                    }
                }
            )
            field(
                label = stringResource(R.string.settings_max_attempts),
                value = d.maxNetworkAttempts.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(maxNetworkAttempts = v) }
                    }
                }
            )
            field(
                label = stringResource(R.string.settings_translation_cache),
                value = d.translationCacheMax.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(translationCacheMax = v) }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            sectionTitle(
                title = displayTitle,
                onInfo = { showInfo(displayTitle, displayInfo) }
            )
            switchField(
                label = stringResource(R.string.settings_keep_screen_on),
                checked = d.keepScreenOn,
                onChange = { viewModel.updateDraft { s -> s.copy(keepScreenOn = it) } }
            )
            switchField(
                label = stringResource(R.string.settings_immersive),
                checked = d.immersiveMode,
                onChange = { viewModel.updateDraft { s -> s.copy(immersiveMode = it) } }
            )

            if (ui.warnings.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                sectionTitle(stringResource(R.string.settings_warnings))
                ui.warnings.forEach { msg ->
                    Text(
                        text = "⚠ $msg",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save)) }
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
private fun switchField(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (onInfo != null) {
            InfoButton(onClick = onInfo)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
