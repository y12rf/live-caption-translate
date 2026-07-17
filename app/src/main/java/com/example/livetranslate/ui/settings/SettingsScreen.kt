package com.example.livetranslate.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import com.example.livetranslate.data.llm.LlmReasoningEffort
import com.example.livetranslate.data.llm.LlmReasoningEffortStyle
import com.example.livetranslate.data.llm.LlmThinkingMode
import com.example.livetranslate.data.settings.GlossaryEntry
import com.example.livetranslate.data.settings.OverlayLayoutMode
import com.example.livetranslate.data.settings.OverlayTextMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.util.AppLocale
import com.example.livetranslate.util.KeepAliveHelper

/** Top-level settings sections (hub → detail). */
private enum class SettingsCategory {
    General,
    Asr,
    Llm,
    AudioPipeline,
    Appearance,
    SystemData
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val d = ui.draft
    val context = LocalContext.current
    var category by remember { mutableStateOf<SettingsCategory?>(null) }
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

    val labels = rememberSettingsLabels()

    // System back on a sub-page returns to the hub instead of leaving Settings.
    BackHandler(enabled = category != null) {
        category = null
    }

    when (val page = category) {
        null -> SettingsHub(
            ui = ui,
            onOpen = { category = it },
            onBack = onBack,
            onSave = viewModel::save
        )
        else -> SettingsDetailScaffold(
            title = page.title(),
            onBack = { category = null },
            onSave = viewModel::save,
            savedMessage = ui.savedMessage,
            warnings = ui.warnings
        ) {
            when (page) {
                SettingsCategory.General -> GeneralCategoryContent(
                    d = d,
                    labels = labels,
                    onUpdate = viewModel::updateDraft,
                    onInfo = ::showInfo
                )
                SettingsCategory.Asr -> AsrCategoryContent(
                    d = d,
                    ui = ui,
                    labels = labels,
                    onUpdate = viewModel::updateDraft,
                    onInfo = ::showInfo,
                    onTestAsr = viewModel::testAsrLatency
                )
                SettingsCategory.Llm -> LlmCategoryContent(
                    d = d,
                    ui = ui,
                    labels = labels,
                    onUpdate = viewModel::updateDraft,
                    onInfo = ::showInfo,
                    onTestLlm = viewModel::testLlmLatency
                )
                SettingsCategory.AudioPipeline -> AudioPipelineCategoryContent(
                    d = d,
                    labels = labels,
                    onUpdate = viewModel::updateDraft,
                    onInfo = ::showInfo
                )
                SettingsCategory.Appearance -> AppearanceCategoryContent(
                    d = d,
                    labels = labels,
                    onUpdate = viewModel::updateDraft,
                    onInfo = ::showInfo
                )
                SettingsCategory.SystemData -> SystemDataCategoryContent(
                    ui = ui,
                    labels = labels,
                    ignoringBattery = ignoringBattery,
                    onInfo = ::showInfo,
                    onClearCache = viewModel::clearCache,
                    onClearAllHistory = viewModel::clearAllHistory
                )
            }
        }
    }
}

@Composable
private fun SettingsCategory.title(): String = stringResource(
    when (this) {
        SettingsCategory.General -> R.string.settings_cat_general
        SettingsCategory.Asr -> R.string.settings_cat_asr
        SettingsCategory.Llm -> R.string.settings_cat_llm
        SettingsCategory.AudioPipeline -> R.string.settings_cat_audio
        SettingsCategory.Appearance -> R.string.settings_cat_appearance
        SettingsCategory.SystemData -> R.string.settings_cat_system
    }
)

@Composable
private fun SettingsCategory.subtitle(): String = stringResource(
    when (this) {
        SettingsCategory.General -> R.string.settings_cat_general_desc
        SettingsCategory.Asr -> R.string.settings_cat_asr_desc
        SettingsCategory.Llm -> R.string.settings_cat_llm_desc
        SettingsCategory.AudioPipeline -> R.string.settings_cat_audio_desc
        SettingsCategory.Appearance -> R.string.settings_cat_appearance_desc
        SettingsCategory.SystemData -> R.string.settings_cat_system_desc
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    ui: SettingsUiState,
    onOpen: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
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
                .verticalScroll(rememberScrollState())
        ) {
            SettingsCategory.entries.forEach { cat ->
                SettingsHubRow(
                    title = cat.title(),
                    subtitle = cat.subtitle(),
                    onClick = { onOpen(cat) }
                )
            }

            if (ui.warnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_warnings),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                ui.warnings.forEach { msg ->
                    Text(
                        text = "⚠ $msg",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) { Text(stringResource(R.string.save)) }
            ui.savedMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsHubRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    savedMessage: String?,
    warnings: List<String>,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            content()
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                sectionTitle(stringResource(R.string.settings_warnings))
                warnings.forEach { msg ->
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
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save)) }
            savedMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// region Category pages

@Composable
private fun GeneralCategoryContent(
    d: UserSettings,
    labels: SettingsLabels,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onInfo: (String, String) -> Unit
) {
    sectionTitle(labels.uiLangSection) {
        onInfo(labels.uiLangSection, labels.uiLangInfo)
    }
    val uiLangDisplay =
        if (AppLocale.normalize(d.uiLanguage) == AppLocale.ZH) labels.zhLabel else labels.enLabel
    dropdownField(
        label = stringResource(R.string.ui_language),
        value = uiLangDisplay,
        options = listOf(labels.enLabel, labels.zhLabel),
        onSelect = { sel ->
            val code = if (sel == labels.zhLabel) AppLocale.ZH else AppLocale.EN
            onUpdate { s -> s.copy(uiLanguage = code) }
        }
    )
    OutlinedButton(
        onClick = { onUpdate { s -> s.copy(uiLanguage = UserSettings().uiLanguage) } },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_ui_language)) }

    Spacer(Modifier.height(16.dp))
    sectionTitle(labels.displayTitle) { onInfo(labels.displayTitle, labels.displayInfo) }
    switchField(
        label = labels.keepScreenLabel,
        checked = d.keepScreenOn,
        onChange = { onUpdate { s -> s.copy(keepScreenOn = it) } },
        onInfo = { onInfo(labels.keepScreenLabel, labels.keepScreenInfo) }
    )
    switchField(
        label = labels.immersiveLabel,
        checked = d.immersiveMode,
        onChange = { onUpdate { s -> s.copy(immersiveMode = it) } },
        onInfo = { onInfo(labels.immersiveLabel, labels.immersiveInfo) }
    )
    switchField(
        label = labels.asrOnlyTitle,
        checked = d.asrOnlyMode,
        onChange = { onUpdate { s -> s.copy(asrOnlyMode = it) } },
        onInfo = { onInfo(labels.asrOnlyTitle, labels.asrOnlyInfo) }
    )
    OutlinedButton(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
                s.copy(
                    keepScreenOn = def.keepScreenOn,
                    immersiveMode = def.immersiveMode,
                    asrOnlyMode = def.asrOnlyMode
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_display)) }
}

@Composable
private fun AsrCategoryContent(
    d: UserSettings,
    ui: SettingsUiState,
    labels: SettingsLabels,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onInfo: (String, String) -> Unit,
    onTestAsr: () -> Unit
) {
    sectionTitle(labels.asrSection) { onInfo(labels.asrSection, labels.asrSectionInfo) }
    field(
        label = labels.apiUrlLabel,
        value = d.asrBaseUrl,
        onChange = { onUpdate { s -> s.copy(asrBaseUrl = it) } },
        onInfo = { onInfo(labels.apiUrlLabel, labels.apiUrlAsrInfo) }
    )
    switchField(
        label = labels.fullUrlLabel,
        checked = d.asrFullUrl,
        onChange = { onUpdate { s -> s.copy(asrFullUrl = it) } },
        onInfo = { onInfo(labels.fullUrlTitle, labels.fullUrlInfo) }
    )
    secretField(
        label = labels.apiKeyLabel,
        value = d.asrApiKey,
        onChange = { onUpdate { s -> s.copy(asrApiKey = it) } },
        onInfo = { onInfo(labels.apiKeyLabel, labels.apiKeyInfo) }
    )
    field(
        label = labels.modelLabel,
        value = d.asrModel,
        onChange = { onUpdate { s -> s.copy(asrModel = it) } },
        onInfo = { onInfo(labels.modelLabel, labels.modelInfo) }
    )
    dropdownField(
        label = labels.asrStyleLabel,
        value = d.asrApiStyle,
        options = AsrApiStyle.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(asrApiStyle = it) } },
        onInfo = { onInfo(labels.asrStyleLabel, labels.asrStyleInfo) }
    )
    dropdownField(
        label = labels.asrAuthLabel,
        value = d.asrAuthStyle,
        options = ApiAuthStyle.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(asrAuthStyle = it) } },
        onInfo = { onInfo(labels.asrAuthLabel, labels.asrAuthInfo) }
    )
    Button(
        onClick = onTestAsr,
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
            onUpdate { s ->
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
}

@Composable
private fun LlmCategoryContent(
    d: UserSettings,
    ui: SettingsUiState,
    labels: SettingsLabels,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onInfo: (String, String) -> Unit,
    onTestLlm: () -> Unit
) {
    sectionTitle(labels.llmSection) { onInfo(labels.llmSection, labels.llmSectionInfo) }
    field(
        label = labels.apiUrlLabel,
        value = d.llmBaseUrl,
        onChange = { onUpdate { s -> s.copy(llmBaseUrl = it) } },
        onInfo = { onInfo(labels.apiUrlLabel, labels.apiUrlLlmInfo) }
    )
    switchField(
        label = labels.fullUrlLabel,
        checked = d.llmFullUrl,
        onChange = { onUpdate { s -> s.copy(llmFullUrl = it) } },
        onInfo = { onInfo(labels.fullUrlTitle, labels.fullUrlInfo) }
    )
    secretField(
        label = labels.apiKeyLabel,
        value = d.llmApiKey,
        onChange = { onUpdate { s -> s.copy(llmApiKey = it) } },
        onInfo = { onInfo(labels.apiKeyLabel, labels.apiKeyInfo) }
    )
    field(
        label = labels.modelLabel,
        value = d.llmModel,
        onChange = { onUpdate { s -> s.copy(llmModel = it) } },
        onInfo = { onInfo(labels.modelLabel, labels.modelInfo) }
    )
    dropdownField(
        label = labels.llmAuthLabel,
        value = d.llmAuthStyle,
        options = ApiAuthStyle.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(llmAuthStyle = it) } },
        onInfo = { onInfo(labels.llmAuthLabel, labels.llmAuthInfo) }
    )
    dropdownField(
        label = labels.thinkingLabel,
        value = LlmThinkingMode.fromStorage(d.llmThinking).name,
        options = LlmThinkingMode.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(llmThinking = it) } },
        onInfo = { onInfo(labels.thinkingLabel, labels.thinkingInfo) }
    )
    dropdownField(
        label = labels.effortLabel,
        value = LlmReasoningEffort.fromStorage(d.llmReasoningEffort).name,
        options = LlmReasoningEffort.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(llmReasoningEffort = it) } },
        onInfo = { onInfo(labels.effortLabel, labels.effortInfo) }
    )
    dropdownField(
        label = labels.effortStyleLabel,
        value = LlmReasoningEffortStyle.fromStorage(d.llmReasoningEffortStyle).name,
        options = LlmReasoningEffortStyle.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(llmReasoningEffortStyle = it) } },
        onInfo = { onInfo(labels.effortStyleLabel, labels.effortStyleInfo) }
    )
    Button(
        onClick = onTestLlm,
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
    Spacer(Modifier.height(12.dp))
    val promptSection = stringResource(R.string.settings_prompt_section)
    val promptSectionInfo = stringResource(R.string.settings_prompt_section_info)
    val systemPromptLabel = stringResource(R.string.settings_system_prompt)
    val systemPromptTitle = stringResource(R.string.settings_prompt_title)
    val systemPromptInfo = stringResource(R.string.settings_info_system_prompt)
    val userPromptLabel = stringResource(R.string.settings_user_prompt)
    val userPromptTitle = stringResource(R.string.settings_user_prompt_title)
    val userPromptInfo = stringResource(R.string.settings_info_user_prompt)
    val titleSysLabel = stringResource(R.string.settings_title_system_prompt)
    val titleSysTitle = stringResource(R.string.settings_title_system_prompt_title)
    val titleSysInfo = stringResource(R.string.settings_info_title_system_prompt)
    val titleUserLabel = stringResource(R.string.settings_title_user_prompt)
    val titleUserTitle = stringResource(R.string.settings_title_user_prompt_title)
    val titleUserInfo = stringResource(R.string.settings_info_title_user_prompt)

    sectionTitle(promptSection) { onInfo(promptSection, promptSectionInfo) }
    multilineField(
        label = systemPromptLabel,
        value = d.llmSystemPrompt,
        onChange = { onUpdate { s -> s.copy(llmSystemPrompt = it) } },
        onInfo = { onInfo(systemPromptTitle, systemPromptInfo) },
        minHeight = 160
    )
    OutlinedButton(
        onClick = {
            onUpdate { s ->
                if (s.llmSystemPrompt.contains("{{glossary}}")) s
                else s.copy(llmSystemPrompt = s.llmSystemPrompt.trimEnd() + labels.glossarySuffix)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.glossary_insert_placeholder)) }
    multilineField(
        label = userPromptLabel,
        value = d.llmUserPrompt,
        onChange = { onUpdate { s -> s.copy(llmUserPrompt = it) } },
        onInfo = { onInfo(userPromptTitle, userPromptInfo) },
        minHeight = 140
    )
    multilineField(
        label = titleSysLabel,
        value = d.llmTitleSystemPrompt,
        onChange = { onUpdate { s -> s.copy(llmTitleSystemPrompt = it) } },
        onInfo = { onInfo(titleSysTitle, titleSysInfo) },
        minHeight = 100
    )
    multilineField(
        label = titleUserLabel,
        value = d.llmTitleUserPrompt,
        onChange = { onUpdate { s -> s.copy(llmTitleUserPrompt = it) } },
        onInfo = { onInfo(titleUserTitle, titleUserInfo) },
        minHeight = 120
    )
    OutlinedButton(
        onClick = {
            onUpdate { s ->
                s.copy(
                    llmSystemPrompt = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT,
                    llmUserPrompt = UserSettings.DEFAULT_LLM_USER_PROMPT,
                    llmTitleSystemPrompt = UserSettings.DEFAULT_LLM_TITLE_SYSTEM_PROMPT,
                    llmTitleUserPrompt = UserSettings.DEFAULT_LLM_TITLE_USER_PROMPT
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_all_prompts)) }
    OutlinedButton(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
                s.copy(
                    llmBaseUrl = def.llmBaseUrl,
                    llmModel = def.llmModel,
                    llmAuthStyle = def.llmAuthStyle,
                    llmFullUrl = def.llmFullUrl,
                    llmThinking = def.llmThinking,
                    llmReasoningEffort = def.llmReasoningEffort,
                    llmReasoningEffortStyle = def.llmReasoningEffortStyle,
                    llmSystemPrompt = UserSettings.DEFAULT_LLM_SYSTEM_PROMPT,
                    llmUserPrompt = UserSettings.DEFAULT_LLM_USER_PROMPT,
                    llmTitleSystemPrompt = UserSettings.DEFAULT_LLM_TITLE_SYSTEM_PROMPT,
                    llmTitleUserPrompt = UserSettings.DEFAULT_LLM_TITLE_USER_PROMPT
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_llm)) }

    Spacer(Modifier.height(16.dp))
    sectionTitle(labels.glossarySection) { onInfo(labels.glossarySection, labels.glossaryInfo) }
    d.glossaryTerms.forEachIndexed { index, entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = entry.source,
                onValueChange = { v ->
                    onUpdate { s ->
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
                    onUpdate { s ->
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
                    onUpdate { s ->
                        s.copy(
                            glossaryTerms = s.glossaryTerms.filterIndexed { i, _ -> i != index }
                        )
                    }
                }
            ) { Text(stringResource(R.string.glossary_remove)) }
        }
        Spacer(Modifier.height(6.dp))
    }
    OutlinedButton(
        onClick = {
            onUpdate { s ->
                if (s.glossaryTerms.size >= 100) s
                else s.copy(glossaryTerms = s.glossaryTerms + GlossaryEntry("", ""))
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.glossary_add)) }
    OutlinedButton(
        onClick = { onUpdate { s -> s.copy(glossaryTerms = emptyList()) } },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_glossary)) }

    Spacer(Modifier.height(16.dp))
    sectionTitle(labels.languagesSection) {
        onInfo(labels.languagesSection, labels.languagesInfo)
    }
    field(
        label = labels.inputLangLabel,
        value = d.inputLanguage,
        onChange = { onUpdate { s -> s.copy(inputLanguage = it) } },
        onInfo = { onInfo(labels.inputLangLabel, labels.inputLangInfo) }
    )
    field(
        label = labels.outputLangLabel,
        value = d.outputLanguage,
        onChange = { onUpdate { s -> s.copy(outputLanguage = it) } },
        onInfo = { onInfo(labels.outputLangLabel, labels.outputLangInfo) }
    )
    OutlinedButton(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
                s.copy(
                    inputLanguage = def.inputLanguage,
                    outputLanguage = def.outputLanguage
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_reset_languages)) }
}

@Composable
private fun AudioPipelineCategoryContent(
    d: UserSettings,
    labels: SettingsLabels,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onInfo: (String, String) -> Unit
) {
    sectionTitle(labels.vadSection) { onInfo(labels.vadSection, labels.vadInfo) }
    field(
        label = labels.silenceLabel,
        value = d.silenceMs.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(silenceMs = v) } }
        },
        onInfo = { onInfo(labels.silenceLabel, labels.silenceInfo) }
    )
    field(
        label = labels.maxUttLabel,
        value = d.maxUtteranceMs.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(maxUtteranceMs = v) } }
        },
        onInfo = { onInfo(labels.maxUttLabel, labels.maxUttInfo) }
    )
    field(
        label = labels.minUttLabel,
        value = d.minUtteranceMs.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(minUtteranceMs = v) } }
        },
        onInfo = { onInfo(labels.minUttLabel, labels.minUttInfo) }
    )
    field(
        label = labels.energyLabel,
        value = d.energyThreshold.toString(),
        onChange = {
            it.toDoubleOrNull()?.let { v -> onUpdate { s -> s.copy(energyThreshold = v) } }
        },
        onInfo = { onInfo(labels.energyLabel, labels.energyInfo) }
    )
    field(
        label = labels.contextLabel,
        value = d.contextWindowSize.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(contextWindowSize = v) } }
        },
        onInfo = { onInfo(labels.contextLabel, labels.contextInfo) }
    )
    OutlinedButton(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
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
    sectionTitle(labels.pipelineTitle) { onInfo(labels.pipelineTitle, labels.pipelineInfo) }
    field(
        label = labels.offlineBatchLabel,
        value = d.offlineVadBatchSize.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(offlineVadBatchSize = v) } }
        },
        onInfo = { onInfo(labels.offlineBatchLabel, labels.offlineBatchInfo) }
    )
    field(
        label = labels.titleThresholdLabel,
        value = d.titleTurnThreshold.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(titleTurnThreshold = v) } }
        },
        onInfo = { onInfo(labels.titleThresholdLabel, labels.titleThresholdInfo) }
    )
    field(
        label = labels.maxAttemptsLabel,
        value = d.maxNetworkAttempts.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(maxNetworkAttempts = v) } }
        },
        onInfo = { onInfo(labels.maxAttemptsLabel, labels.maxAttemptsInfo) }
    )
    field(
        label = labels.translationCacheLabel,
        value = d.translationCacheMax.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(translationCacheMax = v) } }
        },
        onInfo = { onInfo(labels.translationCacheLabel, labels.cacheMaxInfo) }
    )
    OutlinedButton(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
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
}

@Composable
private fun AppearanceCategoryContent(
    d: UserSettings,
    labels: SettingsLabels,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onInfo: (String, String) -> Unit
) {
    sectionTitle(labels.overlaySection) { onInfo(labels.overlaySection, labels.overlayInfo) }
    switchField(
        label = stringResource(R.string.overlay_translation_on_top),
        checked = d.overlayTranslationOnTop,
        onChange = { onUpdate { s -> s.copy(overlayTranslationOnTop = it) } },
        onInfo = { onInfo(labels.orderTitle, labels.orderInfo) }
    )
    dropdownField(
        label = labels.textModeTitle,
        value = d.overlayTextMode,
        options = OverlayTextMode.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(overlayTextMode = it) } },
        onInfo = { onInfo(labels.textModeTitle, labels.textModeInfo) }
    )
    dropdownField(
        label = labels.layoutTitle,
        value = d.overlayLayoutMode,
        options = OverlayLayoutMode.entries.map { it.name },
        onSelect = { onUpdate { s -> s.copy(overlayLayoutMode = it) } },
        onInfo = { onInfo(labels.layoutTitle, labels.layoutInfo) }
    )
    field(
        label = labels.overlayFontLabel,
        value = d.overlayFontSizeSp.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(overlayFontSizeSp = v) } }
        },
        onInfo = { onInfo(labels.overlayFontLabel, labels.overlayFontInfo) }
    )
    field(
        label = labels.liveFontLabel,
        value = d.liveFontSizeSp.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(liveFontSizeSp = v) } }
        },
        onInfo = { onInfo(labels.liveFontLabel, labels.liveFontInfo) }
    )
    field(
        label = labels.overlayWLabel,
        value = d.overlayMaxWidthDp.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(overlayMaxWidthDp = v) } }
        },
        onInfo = { onInfo(labels.overlayWLabel, labels.overlaySizeInfo) }
    )
    field(
        label = labels.overlayHLabel,
        value = d.overlayMaxHeightDp.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(overlayMaxHeightDp = v) } }
        },
        onInfo = { onInfo(labels.overlayHLabel, labels.overlaySizeInfo) }
    )
    field(
        label = labels.overlayAlphaLabel,
        value = d.overlayAlphaPercent.toString(),
        onChange = {
            it.toIntOrNull()?.let { v -> onUpdate { s -> s.copy(overlayAlphaPercent = v) } }
        },
        onInfo = { onInfo(labels.overlayAlphaLabel, labels.overlayColorInfo) }
    )
    field(
        label = labels.overlayBgLabel,
        value = d.overlayBgColor,
        onChange = { onUpdate { s -> s.copy(overlayBgColor = it) } },
        onInfo = { onInfo(labels.overlayBgLabel, labels.overlayColorInfo) }
    )
    field(
        label = labels.overlayEnColorLabel,
        value = d.overlayEnTextColor,
        onChange = { onUpdate { s -> s.copy(overlayEnTextColor = it) } },
        onInfo = { onInfo(labels.overlayEnColorLabel, labels.overlayColorInfo) }
    )
    field(
        label = labels.overlayZhColorLabel,
        value = d.overlayZhTextColor,
        onChange = { onUpdate { s -> s.copy(overlayZhTextColor = it) } },
        onInfo = { onInfo(labels.overlayZhColorLabel, labels.overlayColorInfo) }
    )
    Button(
        onClick = {
            val def = UserSettings()
            onUpdate { s ->
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
}

@Composable
private fun SystemDataCategoryContent(
    ui: SettingsUiState,
    labels: SettingsLabels,
    ignoringBattery: Boolean,
    onInfo: (String, String) -> Unit,
    onClearCache: () -> Unit,
    onClearAllHistory: () -> Unit
) {
    val context = LocalContext.current
    sectionTitle(labels.keepaliveSection) {
        onInfo(labels.keepaliveSection, labels.keepaliveInfo)
    }
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
                Toast.makeText(context, labels.oemFallback, Toast.LENGTH_SHORT).show()
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
    sectionTitle(labels.cacheSection) { onInfo(labels.cacheSection, labels.cacheInfo) }
    Button(
        onClick = onClearCache,
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
                    onClearAllHistory()
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
}

// endregion

// region Shared field widgets + labels

private data class SettingsLabels(
    val uiLangSection: String,
    val uiLangInfo: String,
    val asrSection: String,
    val asrSectionInfo: String,
    val llmSection: String,
    val llmSectionInfo: String,
    val apiUrlLabel: String,
    val apiUrlAsrInfo: String,
    val apiUrlLlmInfo: String,
    val fullUrlLabel: String,
    val fullUrlTitle: String,
    val fullUrlInfo: String,
    val apiKeyLabel: String,
    val apiKeyInfo: String,
    val modelLabel: String,
    val modelInfo: String,
    val asrStyleLabel: String,
    val asrStyleInfo: String,
    val asrAuthLabel: String,
    val asrAuthInfo: String,
    val llmAuthLabel: String,
    val llmAuthInfo: String,
    val thinkingLabel: String,
    val thinkingInfo: String,
    val effortLabel: String,
    val effortInfo: String,
    val effortStyleLabel: String,
    val effortStyleInfo: String,
    val promptTitle: String,
    val promptInfo: String,
    val glossarySection: String,
    val glossaryInfo: String,
    val glossarySuffix: String,
    val languagesSection: String,
    val languagesInfo: String,
    val inputLangLabel: String,
    val inputLangInfo: String,
    val outputLangLabel: String,
    val outputLangInfo: String,
    val vadSection: String,
    val vadInfo: String,
    val silenceLabel: String,
    val silenceInfo: String,
    val maxUttLabel: String,
    val maxUttInfo: String,
    val minUttLabel: String,
    val minUttInfo: String,
    val energyLabel: String,
    val energyInfo: String,
    val contextLabel: String,
    val contextInfo: String,
    val overlayFontLabel: String,
    val liveFontLabel: String,
    val overlayWLabel: String,
    val overlayHLabel: String,
    val overlayAlphaLabel: String,
    val overlayBgLabel: String,
    val overlayEnColorLabel: String,
    val overlayZhColorLabel: String,
    val offlineBatchLabel: String,
    val titleThresholdLabel: String,
    val maxAttemptsLabel: String,
    val translationCacheLabel: String,
    val keepScreenLabel: String,
    val immersiveLabel: String,
    val overlaySection: String,
    val overlayInfo: String,
    val orderTitle: String,
    val orderInfo: String,
    val textModeTitle: String,
    val textModeInfo: String,
    val layoutTitle: String,
    val layoutInfo: String,
    val overlayFontInfo: String,
    val liveFontInfo: String,
    val overlaySizeInfo: String,
    val overlayColorInfo: String,
    val keepaliveSection: String,
    val keepaliveInfo: String,
    val cacheSection: String,
    val cacheInfo: String,
    val pipelineTitle: String,
    val pipelineInfo: String,
    val offlineBatchInfo: String,
    val titleThresholdInfo: String,
    val maxAttemptsInfo: String,
    val cacheMaxInfo: String,
    val displayTitle: String,
    val displayInfo: String,
    val keepScreenInfo: String,
    val immersiveInfo: String,
    val asrOnlyTitle: String,
    val asrOnlyInfo: String,
    val oemFallback: String,
    val enLabel: String,
    val zhLabel: String
)

@Composable
private fun rememberSettingsLabels(): SettingsLabels = SettingsLabels(
    uiLangSection = stringResource(R.string.ui_language_section),
    uiLangInfo = stringResource(R.string.ui_language_info),
    asrSection = stringResource(R.string.settings_asr),
    asrSectionInfo = stringResource(R.string.settings_info_asr_section),
    llmSection = stringResource(R.string.settings_llm),
    llmSectionInfo = stringResource(R.string.settings_info_llm_section),
    apiUrlLabel = stringResource(R.string.settings_api_url),
    apiUrlAsrInfo = stringResource(R.string.settings_info_asr_url),
    apiUrlLlmInfo = stringResource(R.string.settings_info_llm_url),
    fullUrlLabel = stringResource(R.string.settings_full_url),
    fullUrlTitle = stringResource(R.string.settings_full_url_title),
    fullUrlInfo = stringResource(R.string.settings_info_full_url),
    apiKeyLabel = stringResource(R.string.settings_api_key),
    apiKeyInfo = stringResource(R.string.settings_info_api_key),
    modelLabel = stringResource(R.string.settings_model),
    modelInfo = stringResource(R.string.settings_info_model),
    asrStyleLabel = stringResource(R.string.settings_asr_style),
    asrStyleInfo = stringResource(R.string.settings_info_asr_style),
    asrAuthLabel = stringResource(R.string.settings_asr_auth),
    asrAuthInfo = stringResource(R.string.settings_info_asr_auth),
    llmAuthLabel = stringResource(R.string.settings_llm_auth),
    llmAuthInfo = stringResource(R.string.settings_info_llm_auth),
    thinkingLabel = stringResource(R.string.settings_thinking),
    thinkingInfo = stringResource(R.string.settings_info_thinking),
    effortLabel = stringResource(R.string.settings_reasoning_effort),
    effortInfo = stringResource(R.string.settings_info_reasoning_effort),
    effortStyleLabel = stringResource(R.string.settings_reasoning_effort_style),
    effortStyleInfo = stringResource(R.string.settings_info_reasoning_effort_style),
    promptTitle = stringResource(R.string.settings_prompt_title),
    promptInfo = stringResource(R.string.settings_info_system_prompt),
    glossarySection = stringResource(R.string.glossary_section),
    glossaryInfo = stringResource(R.string.glossary_info),
    glossarySuffix = stringResource(R.string.glossary_prompt_suffix),
    languagesSection = stringResource(R.string.settings_languages),
    languagesInfo = stringResource(R.string.settings_info_languages),
    inputLangLabel = stringResource(R.string.settings_input_lang),
    inputLangInfo = stringResource(R.string.settings_info_input_lang),
    outputLangLabel = stringResource(R.string.settings_output_lang),
    outputLangInfo = stringResource(R.string.settings_info_output_lang),
    vadSection = stringResource(R.string.settings_vad),
    vadInfo = stringResource(R.string.settings_info_vad),
    silenceLabel = stringResource(R.string.settings_silence_ms),
    silenceInfo = stringResource(R.string.settings_info_silence_ms),
    maxUttLabel = stringResource(R.string.settings_max_utt_ms),
    maxUttInfo = stringResource(R.string.settings_info_max_utt_ms),
    minUttLabel = stringResource(R.string.settings_min_utt_ms),
    minUttInfo = stringResource(R.string.settings_info_min_utt_ms),
    energyLabel = stringResource(R.string.settings_energy),
    energyInfo = stringResource(R.string.settings_info_energy),
    contextLabel = stringResource(R.string.settings_context_n),
    contextInfo = stringResource(R.string.settings_info_context_n),
    overlayFontLabel = stringResource(R.string.settings_overlay_font_sp),
    liveFontLabel = stringResource(R.string.settings_live_font_sp),
    overlayWLabel = stringResource(R.string.settings_overlay_w),
    overlayHLabel = stringResource(R.string.settings_overlay_h),
    overlayAlphaLabel = stringResource(R.string.settings_overlay_alpha),
    overlayBgLabel = stringResource(R.string.settings_overlay_bg),
    overlayEnColorLabel = stringResource(R.string.settings_overlay_en_color),
    overlayZhColorLabel = stringResource(R.string.settings_overlay_zh_color),
    offlineBatchLabel = stringResource(R.string.settings_offline_vad_batch),
    titleThresholdLabel = stringResource(R.string.settings_title_threshold),
    maxAttemptsLabel = stringResource(R.string.settings_max_attempts),
    translationCacheLabel = stringResource(R.string.settings_translation_cache),
    keepScreenLabel = stringResource(R.string.settings_keep_screen_on),
    immersiveLabel = stringResource(R.string.settings_immersive),
    overlaySection = stringResource(R.string.settings_overlay),
    overlayInfo = stringResource(R.string.settings_info_overlay),
    orderTitle = stringResource(R.string.settings_overlay_order_title),
    orderInfo = stringResource(R.string.settings_overlay_order_info),
    textModeTitle = stringResource(R.string.settings_overlay_text_mode),
    textModeInfo = stringResource(R.string.settings_overlay_text_mode_info),
    layoutTitle = stringResource(R.string.settings_overlay_layout),
    layoutInfo = stringResource(R.string.settings_overlay_layout_info),
    overlayFontInfo = stringResource(R.string.settings_info_overlay_font),
    liveFontInfo = stringResource(R.string.settings_info_live_font),
    overlaySizeInfo = stringResource(R.string.settings_info_overlay_size),
    overlayColorInfo = stringResource(R.string.settings_info_overlay_color),
    keepaliveSection = stringResource(R.string.keepalive_section),
    keepaliveInfo = stringResource(R.string.keepalive_info),
    cacheSection = stringResource(R.string.cache_section),
    cacheInfo = stringResource(R.string.cache_info),
    pipelineTitle = stringResource(R.string.settings_pipeline),
    pipelineInfo = stringResource(R.string.settings_pipeline_info),
    offlineBatchInfo = stringResource(R.string.settings_info_offline_vad_batch),
    titleThresholdInfo = stringResource(R.string.settings_info_title_threshold),
    maxAttemptsInfo = stringResource(R.string.settings_info_max_attempts),
    cacheMaxInfo = stringResource(R.string.settings_info_translation_cache),
    displayTitle = stringResource(R.string.settings_display),
    displayInfo = stringResource(R.string.settings_display_info),
    keepScreenInfo = stringResource(R.string.settings_info_keep_screen_on),
    immersiveInfo = stringResource(R.string.settings_info_immersive),
    asrOnlyTitle = stringResource(R.string.settings_asr_only),
    asrOnlyInfo = stringResource(R.string.settings_asr_only_info),
    oemFallback = stringResource(R.string.keepalive_oem_fallback),
    enLabel = stringResource(R.string.ui_language_en),
    zhLabel = stringResource(R.string.ui_language_zh)
)

@Composable
private fun sectionTitle(title: String, onInfo: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
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
    onInfo: (() -> Unit)? = null,
    minHeight: Int = 140
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(minHeight.dp),
        singleLine = false,
        minLines = 3,
        maxLines = 16,
        trailingIcon = if (onInfo != null) {
            { InfoButton(onClick = onInfo) }
        } else {
            null
        }
    )
}

// endregion
