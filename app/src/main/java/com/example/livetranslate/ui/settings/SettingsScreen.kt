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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.livetranslate.R
import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.llm.LlmThinkingMode
import com.example.livetranslate.data.settings.GlossaryEntry
import com.example.livetranslate.data.settings.OverlayLayoutMode
import com.example.livetranslate.data.settings.OverlayTextMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.util.AppLocale
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

    // --- Localized labels / info (hoisted so non-@Composable lambdas can use them) ---
    val uiLangSection = stringResource(R.string.ui_language_section)
    val uiLangInfo = stringResource(R.string.ui_language_info)
    val asrSection = stringResource(R.string.settings_asr)
    val asrSectionInfo = stringResource(R.string.settings_info_asr_section)
    val llmSection = stringResource(R.string.settings_llm)
    val llmSectionInfo = stringResource(R.string.settings_info_llm_section)
    val apiUrlLabel = stringResource(R.string.settings_api_url)
    val apiUrlAsrInfo = stringResource(R.string.settings_info_asr_url)
    val apiUrlLlmInfo = stringResource(R.string.settings_info_llm_url)
    val fullUrlLabel = stringResource(R.string.settings_full_url)
    val fullUrlTitle = stringResource(R.string.settings_full_url_title)
    val fullUrlInfo = stringResource(R.string.settings_info_full_url)
    val apiKeyLabel = stringResource(R.string.settings_api_key)
    val apiKeyInfo = stringResource(R.string.settings_info_api_key)
    val modelLabel = stringResource(R.string.settings_model)
    val modelInfo = stringResource(R.string.settings_info_model)
    val asrStyleLabel = stringResource(R.string.settings_asr_style)
    val asrStyleInfo = stringResource(R.string.settings_info_asr_style)
    val asrAuthLabel = stringResource(R.string.settings_asr_auth)
    val asrAuthInfo = stringResource(R.string.settings_info_asr_auth)
    val llmAuthLabel = stringResource(R.string.settings_llm_auth)
    val llmAuthInfo = stringResource(R.string.settings_info_llm_auth)
    val thinkingLabel = stringResource(R.string.settings_thinking)
    val thinkingInfo = stringResource(R.string.settings_info_thinking)
    val promptTitle = stringResource(R.string.settings_prompt_title)
    val promptInfo = stringResource(R.string.settings_info_system_prompt)
    val glossarySection = stringResource(R.string.glossary_section)
    val glossaryInfo = stringResource(R.string.glossary_info)
    val glossarySuffix = stringResource(R.string.glossary_prompt_suffix)
    val languagesSection = stringResource(R.string.settings_languages)
    val languagesInfo = stringResource(R.string.settings_info_languages)
    val inputLangLabel = stringResource(R.string.settings_input_lang)
    val inputLangInfo = stringResource(R.string.settings_info_input_lang)
    val outputLangLabel = stringResource(R.string.settings_output_lang)
    val outputLangInfo = stringResource(R.string.settings_info_output_lang)
    val vadSection = stringResource(R.string.settings_vad)
    val vadInfo = stringResource(R.string.settings_info_vad)
    val silenceLabel = stringResource(R.string.settings_silence_ms)
    val silenceInfo = stringResource(R.string.settings_info_silence_ms)
    val maxUttLabel = stringResource(R.string.settings_max_utt_ms)
    val maxUttInfo = stringResource(R.string.settings_info_max_utt_ms)
    val minUttLabel = stringResource(R.string.settings_min_utt_ms)
    val minUttInfo = stringResource(R.string.settings_info_min_utt_ms)
    val energyLabel = stringResource(R.string.settings_energy)
    val energyInfo = stringResource(R.string.settings_info_energy)
    val contextLabel = stringResource(R.string.settings_context_n)
    val contextInfo = stringResource(R.string.settings_info_context_n)
    val overlayFontLabel = stringResource(R.string.settings_overlay_font_sp)
    val liveFontLabel = stringResource(R.string.settings_live_font_sp)
    val overlayWLabel = stringResource(R.string.settings_overlay_w)
    val overlayHLabel = stringResource(R.string.settings_overlay_h)
    val overlayAlphaLabel = stringResource(R.string.settings_overlay_alpha)
    val overlayBgLabel = stringResource(R.string.settings_overlay_bg)
    val overlayEnColorLabel = stringResource(R.string.settings_overlay_en_color)
    val overlayZhColorLabel = stringResource(R.string.settings_overlay_zh_color)
    val offlineBatchLabel = stringResource(R.string.settings_offline_vad_batch)
    val titleThresholdLabel = stringResource(R.string.settings_title_threshold)
    val maxAttemptsLabel = stringResource(R.string.settings_max_attempts)
    val translationCacheLabel = stringResource(R.string.settings_translation_cache)
    val keepScreenLabel = stringResource(R.string.settings_keep_screen_on)
    val immersiveLabel = stringResource(R.string.settings_immersive)
    val overlaySection = stringResource(R.string.settings_overlay)
    val overlayInfo = stringResource(R.string.settings_info_overlay)
    val orderTitle = stringResource(R.string.settings_overlay_order_title)
    val orderInfo = stringResource(R.string.settings_overlay_order_info)
    val textModeTitle = stringResource(R.string.settings_overlay_text_mode)
    val textModeInfo = stringResource(R.string.settings_overlay_text_mode_info)
    val layoutTitle = stringResource(R.string.settings_overlay_layout)
    val layoutInfo = stringResource(R.string.settings_overlay_layout_info)
    val overlayFontInfo = stringResource(R.string.settings_info_overlay_font)
    val liveFontInfo = stringResource(R.string.settings_info_live_font)
    val overlaySizeInfo = stringResource(R.string.settings_info_overlay_size)
    val overlayColorInfo = stringResource(R.string.settings_info_overlay_color)
    val keepaliveSection = stringResource(R.string.keepalive_section)
    val keepaliveInfo = stringResource(R.string.keepalive_info)
    val cacheSection = stringResource(R.string.cache_section)
    val cacheInfo = stringResource(R.string.cache_info)
    val pipelineTitle = stringResource(R.string.settings_pipeline)
    val pipelineInfo = stringResource(R.string.settings_pipeline_info)
    val offlineBatchInfo = stringResource(R.string.settings_info_offline_vad_batch)
    val titleThresholdInfo = stringResource(R.string.settings_info_title_threshold)
    val maxAttemptsInfo = stringResource(R.string.settings_info_max_attempts)
    val cacheMaxInfo = stringResource(R.string.settings_info_translation_cache)
    val displayTitle = stringResource(R.string.settings_display)
    val displayInfo = stringResource(R.string.settings_display_info)
    val keepScreenInfo = stringResource(R.string.settings_info_keep_screen_on)
    val immersiveInfo = stringResource(R.string.settings_info_immersive)
    val asrOnlyTitle = stringResource(R.string.settings_asr_only)
    val asrOnlyInfo = stringResource(R.string.settings_asr_only_info)
    val oemFallback = stringResource(R.string.keepalive_oem_fallback)
    val enLabel = stringResource(R.string.ui_language_en)
    val zhLabel = stringResource(R.string.ui_language_zh)

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
            // —— App language ——
            sectionTitle(uiLangSection) { showInfo(uiLangSection, uiLangInfo) }
            val uiLangDisplay =
                if (AppLocale.normalize(d.uiLanguage) == AppLocale.ZH) zhLabel else enLabel
            dropdownField(
                label = stringResource(R.string.ui_language),
                value = uiLangDisplay,
                options = listOf(enLabel, zhLabel),
                onSelect = { sel ->
                    val code = if (sel == zhLabel) AppLocale.ZH else AppLocale.EN
                    viewModel.updateDraft { s -> s.copy(uiLanguage = code) }
                }
            )
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s -> s.copy(uiLanguage = UserSettings().uiLanguage) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_ui_language)) }

            Spacer(Modifier.height(16.dp))

            // —— ASR ——
            sectionTitle(asrSection) { showInfo(asrSection, asrSectionInfo) }
            field(
                label = apiUrlLabel,
                value = d.asrBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(asrBaseUrl = it) } },
                onInfo = { showInfo(apiUrlLabel, apiUrlAsrInfo) }
            )
            switchField(
                label = fullUrlLabel,
                checked = d.asrFullUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(asrFullUrl = it) } },
                onInfo = { showInfo(fullUrlTitle, fullUrlInfo) }
            )
            secretField(
                label = apiKeyLabel,
                value = d.asrApiKey,
                onChange = { viewModel.updateDraft { s -> s.copy(asrApiKey = it) } },
                onInfo = { showInfo(apiKeyLabel, apiKeyInfo) }
            )
            field(
                label = modelLabel,
                value = d.asrModel,
                onChange = { viewModel.updateDraft { s -> s.copy(asrModel = it) } },
                onInfo = { showInfo(modelLabel, modelInfo) }
            )
            dropdownField(
                label = asrStyleLabel,
                value = d.asrApiStyle,
                options = AsrApiStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(asrApiStyle = it) } },
                onInfo = { showInfo(asrStyleLabel, asrStyleInfo) }
            )
            dropdownField(
                label = asrAuthLabel,
                value = d.asrAuthStyle,
                options = ApiAuthStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(asrAuthStyle = it) } },
                onInfo = { showInfo(asrAuthLabel, asrAuthInfo) }
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
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            asrBaseUrl = def.asrBaseUrl,
                            asrModel = def.asrModel,
                            asrApiStyle = def.asrApiStyle,
                            asrAuthStyle = def.asrAuthStyle,
                            asrFullUrl = def.asrFullUrl
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_asr)) }

            Spacer(Modifier.height(16.dp))

            // —— LLM ——
            sectionTitle(llmSection) { showInfo(llmSection, llmSectionInfo) }
            field(
                label = apiUrlLabel,
                value = d.llmBaseUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(llmBaseUrl = it) } },
                onInfo = { showInfo(apiUrlLabel, apiUrlLlmInfo) }
            )
            switchField(
                label = fullUrlLabel,
                checked = d.llmFullUrl,
                onChange = { viewModel.updateDraft { s -> s.copy(llmFullUrl = it) } },
                onInfo = { showInfo(fullUrlTitle, fullUrlInfo) }
            )
            secretField(
                label = apiKeyLabel,
                value = d.llmApiKey,
                onChange = { viewModel.updateDraft { s -> s.copy(llmApiKey = it) } },
                onInfo = { showInfo(apiKeyLabel, apiKeyInfo) }
            )
            field(
                label = modelLabel,
                value = d.llmModel,
                onChange = { viewModel.updateDraft { s -> s.copy(llmModel = it) } },
                onInfo = { showInfo(modelLabel, modelInfo) }
            )
            dropdownField(
                label = llmAuthLabel,
                value = d.llmAuthStyle,
                options = ApiAuthStyle.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(llmAuthStyle = it) } },
                onInfo = { showInfo(llmAuthLabel, llmAuthInfo) }
            )
            dropdownField(
                label = thinkingLabel,
                value = d.llmThinking,
                options = LlmThinkingMode.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(llmThinking = it) } },
                onInfo = { showInfo(thinkingLabel, thinkingInfo) }
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
            ) { Text(stringResource(R.string.settings_reset_prompt)) }
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s ->
                        if (s.llmSystemPrompt.contains("{{glossary}}")) s
                        else s.copy(
                            llmSystemPrompt = s.llmSystemPrompt.trimEnd() + glossarySuffix
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.glossary_insert_placeholder)) }
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            llmBaseUrl = def.llmBaseUrl,
                            llmModel = def.llmModel,
                            llmAuthStyle = def.llmAuthStyle,
                            llmFullUrl = def.llmFullUrl,
                            llmThinking = def.llmThinking,
                            llmSystemPrompt = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_llm)) }

            Spacer(Modifier.height(16.dp))

            // —— Glossary ——
            sectionTitle(glossarySection) { showInfo(glossarySection, glossaryInfo) }
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
                                    glossaryTerms = s.glossaryTerms.filterIndexed { i, _ ->
                                        i != index
                                    }
                                )
                            }
                        }
                    ) { Text(stringResource(R.string.glossary_remove)) }
                }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s ->
                        if (s.glossaryTerms.size >= 100) s
                        else s.copy(glossaryTerms = s.glossaryTerms + GlossaryEntry("", ""))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.glossary_add)) }
            OutlinedButton(
                onClick = {
                    viewModel.updateDraft { s -> s.copy(glossaryTerms = emptyList()) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_glossary)) }

            Spacer(Modifier.height(16.dp))

            // —— Translate languages ——
            sectionTitle(languagesSection) { showInfo(languagesSection, languagesInfo) }
            field(
                label = inputLangLabel,
                value = d.inputLanguage,
                onChange = { viewModel.updateDraft { s -> s.copy(inputLanguage = it) } },
                onInfo = { showInfo(inputLangLabel, inputLangInfo) }
            )
            field(
                label = outputLangLabel,
                value = d.outputLanguage,
                onChange = { viewModel.updateDraft { s -> s.copy(outputLanguage = it) } },
                onInfo = { showInfo(outputLangLabel, outputLangInfo) }
            )
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            inputLanguage = def.inputLanguage,
                            outputLanguage = def.outputLanguage
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_languages)) }

            Spacer(Modifier.height(16.dp))

            // —— VAD ——
            sectionTitle(vadSection) { showInfo(vadSection, vadInfo) }
            field(
                label = silenceLabel,
                value = d.silenceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(silenceMs = v) }
                    }
                },
                onInfo = { showInfo(silenceLabel, silenceInfo) }
            )
            field(
                label = maxUttLabel,
                value = d.maxUtteranceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(maxUtteranceMs = v) }
                    }
                },
                onInfo = { showInfo(maxUttLabel, maxUttInfo) }
            )
            field(
                label = minUttLabel,
                value = d.minUtteranceMs.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(minUtteranceMs = v) }
                    }
                },
                onInfo = { showInfo(minUttLabel, minUttInfo) }
            )
            field(
                label = energyLabel,
                value = d.energyThreshold.toString(),
                onChange = {
                    it.toDoubleOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(energyThreshold = v) }
                    }
                },
                onInfo = { showInfo(energyLabel, energyInfo) }
            )
            field(
                label = contextLabel,
                value = d.contextWindowSize.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(contextWindowSize = v) }
                    }
                },
                onInfo = { showInfo(contextLabel, contextInfo) }
            )
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            silenceMs = def.silenceMs,
                            maxUtteranceMs = def.maxUtteranceMs,
                            minUtteranceMs = def.minUtteranceMs,
                            energyThreshold = def.energyThreshold,
                            contextWindowSize = def.contextWindowSize
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_vad)) }

            Spacer(Modifier.height(16.dp))

            // —— Overlay / captions ——
            sectionTitle(overlaySection) { showInfo(overlaySection, overlayInfo) }
            switchField(
                label = stringResource(R.string.overlay_translation_on_top),
                checked = d.overlayTranslationOnTop,
                onChange = {
                    viewModel.updateDraft { s -> s.copy(overlayTranslationOnTop = it) }
                },
                onInfo = { showInfo(orderTitle, orderInfo) }
            )
            dropdownField(
                label = textModeTitle,
                value = d.overlayTextMode,
                options = OverlayTextMode.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(overlayTextMode = it) } },
                onInfo = { showInfo(textModeTitle, textModeInfo) }
            )
            dropdownField(
                label = layoutTitle,
                value = d.overlayLayoutMode,
                options = OverlayLayoutMode.entries.map { it.name },
                onSelect = { viewModel.updateDraft { s -> s.copy(overlayLayoutMode = it) } },
                onInfo = { showInfo(layoutTitle, layoutInfo) }
            )
            field(
                label = overlayFontLabel,
                value = d.overlayFontSizeSp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayFontSizeSp = v) }
                    }
                },
                onInfo = { showInfo(overlayFontLabel, overlayFontInfo) }
            )
            field(
                label = liveFontLabel,
                value = d.liveFontSizeSp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(liveFontSizeSp = v) }
                    }
                },
                onInfo = { showInfo(liveFontLabel, liveFontInfo) }
            )
            field(
                label = overlayWLabel,
                value = d.overlayMaxWidthDp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayMaxWidthDp = v) }
                    }
                },
                onInfo = { showInfo(overlayWLabel, overlaySizeInfo) }
            )
            field(
                label = overlayHLabel,
                value = d.overlayMaxHeightDp.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayMaxHeightDp = v) }
                    }
                },
                onInfo = { showInfo(overlayHLabel, overlaySizeInfo) }
            )
            field(
                label = overlayAlphaLabel,
                value = d.overlayAlphaPercent.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(overlayAlphaPercent = v) }
                    }
                },
                onInfo = { showInfo(overlayAlphaLabel, overlayColorInfo) }
            )
            field(
                label = overlayBgLabel,
                value = d.overlayBgColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayBgColor = it) } },
                onInfo = { showInfo(overlayBgLabel, overlayColorInfo) }
            )
            field(
                label = overlayEnColorLabel,
                value = d.overlayEnTextColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayEnTextColor = it) } },
                onInfo = { showInfo(overlayEnColorLabel, overlayColorInfo) }
            )
            field(
                label = overlayZhColorLabel,
                value = d.overlayZhTextColor,
                onChange = { viewModel.updateDraft { s -> s.copy(overlayZhTextColor = it) } },
                onInfo = { showInfo(overlayZhColorLabel, overlayColorInfo) }
            )
            Button(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            overlayBgColor = UserSettings.DEFAULT_OVERLAY_BG,
                            overlayEnTextColor = UserSettings.DEFAULT_OVERLAY_EN,
                            overlayZhTextColor = UserSettings.DEFAULT_OVERLAY_ZH,
                            overlayAlphaPercent = UserSettings.DEFAULT_OVERLAY_ALPHA,
                            overlayMaxWidthDp = UserSettings.DEFAULT_OVERLAY_WIDTH_DP,
                            overlayMaxHeightDp = UserSettings.DEFAULT_OVERLAY_HEIGHT_DP,
                            overlayTranslationOnTop = def.overlayTranslationOnTop,
                            overlayTextMode = def.overlayTextMode,
                            overlayLayoutMode = def.overlayLayoutMode,
                            overlayFontSizeSp = UserSettings.DEFAULT_OVERLAY_FONT_SP,
                            liveFontSizeSp = UserSettings.DEFAULT_LIVE_FONT_SP
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_caption_defaults)) }

            Spacer(Modifier.height(16.dp))

            // —— Keep-alive ——
            sectionTitle(keepaliveSection) { showInfo(keepaliveSection, keepaliveInfo) }
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
                    onClick = { KeepAliveHelper.requestIgnoreBatteryOptimizations(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) { Text(stringResource(R.string.keepalive_request_battery)) }
            } else {
                OutlinedButton(
                    onClick = { KeepAliveHelper.openBatteryOptimizationSettings(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) { Text(stringResource(R.string.keepalive_open_battery_list)) }
            }
            OutlinedButton(
                onClick = {
                    val opened = KeepAliveHelper.openOemAutoStartSettings(context)
                    if (!opened) {
                        Toast.makeText(context, oemFallback, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) { Text(stringResource(R.string.keepalive_oem_autostart)) }
            OutlinedButton(
                onClick = { KeepAliveHelper.openAppDetailsSettings(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) { Text(stringResource(R.string.keepalive_app_details)) }

            Spacer(Modifier.height(16.dp))

            // —— Cache ——
            sectionTitle(cacheSection) { showInfo(cacheSection, cacheInfo) }
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
            ) { Text(stringResource(R.string.clear_all_history)) }
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

            // —— Pipeline ——
            sectionTitle(pipelineTitle) { showInfo(pipelineTitle, pipelineInfo) }
            field(
                label = offlineBatchLabel,
                value = d.offlineVadBatchSize.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(offlineVadBatchSize = v) }
                    }
                },
                onInfo = { showInfo(offlineBatchLabel, offlineBatchInfo) }
            )
            field(
                label = titleThresholdLabel,
                value = d.titleTurnThreshold.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(titleTurnThreshold = v) }
                    }
                },
                onInfo = { showInfo(titleThresholdLabel, titleThresholdInfo) }
            )
            field(
                label = maxAttemptsLabel,
                value = d.maxNetworkAttempts.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(maxNetworkAttempts = v) }
                    }
                },
                onInfo = { showInfo(maxAttemptsLabel, maxAttemptsInfo) }
            )
            field(
                label = translationCacheLabel,
                value = d.translationCacheMax.toString(),
                onChange = {
                    it.toIntOrNull()?.let { v ->
                        viewModel.updateDraft { s -> s.copy(translationCacheMax = v) }
                    }
                },
                onInfo = { showInfo(translationCacheLabel, cacheMaxInfo) }
            )
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            offlineVadBatchSize = def.offlineVadBatchSize,
                            titleTurnThreshold = def.titleTurnThreshold,
                            maxNetworkAttempts = def.maxNetworkAttempts,
                            translationCacheMax = def.translationCacheMax
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_pipeline)) }

            Spacer(Modifier.height(16.dp))

            // —— Display ——
            sectionTitle(displayTitle) { showInfo(displayTitle, displayInfo) }
            switchField(
                label = keepScreenLabel,
                checked = d.keepScreenOn,
                onChange = { viewModel.updateDraft { s -> s.copy(keepScreenOn = it) } },
                onInfo = { showInfo(keepScreenLabel, keepScreenInfo) }
            )
            switchField(
                label = immersiveLabel,
                checked = d.immersiveMode,
                onChange = { viewModel.updateDraft { s -> s.copy(immersiveMode = it) } },
                onInfo = { showInfo(immersiveLabel, immersiveInfo) }
            )
            switchField(
                label = asrOnlyTitle,
                checked = d.asrOnlyMode,
                onChange = { viewModel.updateDraft { s -> s.copy(asrOnlyMode = it) } },
                onInfo = { showInfo(asrOnlyTitle, asrOnlyInfo) }
            )
            OutlinedButton(
                onClick = {
                    val def = UserSettings()
                    viewModel.updateDraft { s ->
                        s.copy(
                            keepScreenOn = def.keepScreenOn,
                            immersiveMode = def.immersiveMode,
                            asrOnlyMode = def.asrOnlyMode
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_reset_display)) }

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
            contentDescription = stringResource(R.string.settings_info)
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
    val display = if (options.contains(value)) value else (options.firstOrNull() ?: value)
    val infoCd = stringResource(R.string.settings_info)
    val expandCd = stringResource(R.string.settings_expand)

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
                            Icon(Icons.Outlined.Info, contentDescription = infoCd)
                        }
                    }
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = expandCd)
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

/** API key field: masked by default; eye toggle reveals temporarily. */
@Composable
private fun secretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    val showLabel = stringResource(R.string.settings_api_key_show)
    val hideLabel = stringResource(R.string.settings_api_key_hide)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onInfo != null) {
                    InfoButton(onClick = onInfo)
                }
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (visible) hideLabel else showLabel
                    )
                }
            }
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
