package com.example.qwenchat.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    temperature: Float,
    contextSize: Int,
    systemPrompt: String,
    reasoningEnabled: Boolean,
    recursionDepth: Int,
    mmprojLoaded: Boolean = false,
    onTemperatureChange: (Float) -> Unit,
    onContextSizeChange: (Int) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onRecursionDepthChange: (Int) -> Unit,
    onPickMmproj: () -> Unit = {},
    onBack: () -> Unit,
) {
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Temperature
            Column {
                Text(
                    "Temperature: ${"%.1f".format(temperature)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Lower = more focused, Higher = more creative",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    steps = 19
                )
            }

            // Context Size
            Column {
                Text(
                    "Context Size: $contextSize",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Larger context uses more memory. Takes effect on next model load.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = contextSize.toFloat(),
                    onValueChange = { onContextSizeChange(it.toInt()) },
                    valueRange = 2048f..32768f,
                    steps = 14
                )
            }

            // Recursion Depth
            Column {
                Text(
                    "Recursion Depth: $recursionDepth",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (recursionDepth == 0) "No recursion — single answer"
                    else "Repeats the question $recursionDepth time${if (recursionDepth > 1) "s" else ""}, shows only the final refined answer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = recursionDepth.toFloat(),
                    onValueChange = { onRecursionDepthChange(it.toInt()) },
                    valueRange = 0f..6f,
                    steps = 5
                )
            }

            // Reasoning Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Reasoning Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Enable thinking/reasoning for Qwen models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reasoningEnabled,
                    onCheckedChange = onReasoningChange
                )
            }

            // Vision Projector
            Column {
                Text(
                    "Vision Projector (mmproj)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (mmprojLoaded) "Loaded - image attachments enabled"
                    else "Load an mmproj GGUF file to enable image input",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mmprojLoaded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onPickMmproj) {
                    Text(if (mmprojLoaded) "Replace Vision Model" else "Load Vision Model")
                }
            }

            // System Prompt
            Column {
                Text(
                    "System Prompt",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
