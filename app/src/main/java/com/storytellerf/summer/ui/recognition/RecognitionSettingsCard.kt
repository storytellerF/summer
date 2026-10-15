package com.storytellerf.summer.ui.recognition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import com.storytellerf.summer.ui.components.EntryMessage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storytellerf.summer.data.recognition.RecognitionBackend

@Composable
fun RecognitionSettingsCard(viewModel: RecognitionSettingsViewModel, llmdOptions: @Composable () -> Unit = {}) {
    val host = viewModel.host
    val focus = LocalFocusManager.current
    val state by host.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recognition service", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { expanded = true }, enabled = !state.loading && !state.saving) {
                Text("Provider: ${state.backend.displayName}")
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RecognitionBackend.entries.forEach { backend ->
                        DropdownMenuItem(text = { Text(backend.displayName) }, onClick = {
                            expanded = false
                            focus.clearFocus()
                            host.selectBackend(backend)
                        })
                    }
                }
            }
            if (state.backend == RecognitionBackend.Llmd) {
                Text("Use an installed LLMD app to read balances and transactions.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                llmdOptions()
            }
            if (state.backend != RecognitionBackend.Llmd) {
                Text("Images are sent to the selected API provider. Choose a model that supports image input.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.connection.baseUrl,
                    onValueChange = { url -> host.updateConnection { it.copy(baseUrl = url) } },
                    label = { Text("API base URL") }, singleLine = true, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.connection.model,
                    onValueChange = { model -> host.updateConnection { it.copy(model = model) } },
                    label = { Text("Image model") }, singleLine = true, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.connection.apiKey,
                    onValueChange = { key -> host.updateConnection { it.copy(apiKey = key) } },
                    label = { Text("API key") }, singleLine = true, enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (state.backend in listOf(RecognitionBackend.OpenAI, RecognitionBackend.OpenAICompatible)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.connection.useResponsesApi, enabled = !state.saving,
                            onCheckedChange = { enabled -> host.updateConnection { it.copy(useResponsesApi = enabled) } })
                        Text("Use Responses API")
                    }
                }
            }
            state.message?.let { EntryMessage(it) }
            Button(onClick = { focus.clearFocus(); host.save() }, enabled = !state.loading && !state.saving) {
                Text(if (state.saving) "Saving recognition settings..." else "Save recognition settings")
            }
            if (state.backend != RecognitionBackend.Llmd) {
                TextButton(onClick = { host.clearSavedConnection() }, enabled = !state.loading && !state.saving) {
                    Text("Remove saved connection")
                }
            }
        }
    }
}
