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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
            Text("ASR (OpenAI-compatible)")
            field("Base URL", d.asrBaseUrl) {
                viewModel.updateDraft { s -> s.copy(asrBaseUrl = it) }
            }
            field("API Key", d.asrApiKey) {
                viewModel.updateDraft { s -> s.copy(asrApiKey = it) }
            }
            field("Model", d.asrModel) {
                viewModel.updateDraft { s -> s.copy(asrModel = it) }
            }

            Spacer(Modifier.height(16.dp))
            Text("LLM (OpenAI-compatible)")
            field("Base URL", d.llmBaseUrl) {
                viewModel.updateDraft { s -> s.copy(llmBaseUrl = it) }
            }
            field("API Key", d.llmApiKey) {
                viewModel.updateDraft { s -> s.copy(llmApiKey = it) }
            }
            field("Model", d.llmModel) {
                viewModel.updateDraft { s -> s.copy(llmModel = it) }
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
