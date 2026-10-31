package org.meshmind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: ChatViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = androidx.lifecycle.ViewModelProvider(this)[ChatViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { MeshMind(model) }
        }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.setForeground(true) }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.setForeground(true) }
    override fun onStop() { if (::model.isInitialized) model.setForeground(false); super.onStop() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::model.isInitialized) model.trimMemory(level) }
}

@Composable
private fun MeshMind(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(1) }
    var draft by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
            Text("LFM3 Local Uncensored", style = MaterialTheme.typography.headlineMedium)
            Text("${state.status} · Experimental offline preview", style = MaterialTheme.typography.labelMedium)
            if (BuildConfig.REFUSAL_ADAPTER) Text(
                if (state.settings.refusalReduction) "Refusal reduction ON · experimental" else "Refusal reduction OFF · base model",
                style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("AI", "Settings").forEachIndexed { offset, title ->
                    val index = offset + 1
                    TextButton(onClick = { tab = index }) { Text(if (tab == index) "[$title]" else title) }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::clearError) { Text("Dismiss") }
                } }
            }
            when (tab) {
                0 -> Text("Bluetooth messenger is not implemented in this Phase 1 build. No peer discovery or message delivery is claimed.", Modifier.padding(vertical = 24.dp))
                1 -> {
                    Text(if (state.ready) "Model ready · private on-device chat" else "No model loaded · choose a model in Settings", Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(state.messages) { _, message ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(if (message.role == "user") "You" else "Local AI", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text(message.content.ifEmpty { "Generating..." }) }
                            } }
                        }
                    }
                    if (state.stats.isNotBlank()) Text(state.stats, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(draft, { if (it.length <= 16384) draft = it }, label = { Text("Message stays on this device") },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy, maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.send(draft); draft = "" }, enabled = state.ready && !state.busy && draft.isNotBlank()) { Text("Send to local AI") }
                        if (state.busy) OutlinedButton(onClick = vm::stop) { Text("Stop") }
                        else TextButton(onClick = vm::reset) { Text("Reset") }
                    }
                }
                2 -> ModelSettings(vm, state)
            }
        }
    }
}

@Composable
private fun ModelSettings(vm: ChatViewModel, state: UiState) {
    val manifests = remember { vm.repository.officialManifests() }
    var manifestText by remember { mutableStateOf(manifests.firstOrNull { it.first == "lfm3-uncensored.json" }?.second ?: manifests.firstOrNull()?.second ?: "") }
    var showNotices by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, manifestText)
    }
    val pickManifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val part = ByteArray(4096)
                    while (buffer.size() <= 65536) {
                        val n = stream.read(part, 0, minOf(part.size, 65537 - buffer.size()))
                        if (n < 0) break
                        buffer.write(part, 0, n)
                    }
                    val bytes = buffer.toByteArray()
                    require(bytes.size <= 65536) { "Manifest exceeds 64 KiB" }
                    val text = bytes.toString(Charsets.UTF_8)
                    org.meshmind.model.ModelManifest.parse(text)
                    manifestText = text
                }
            }.onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (BuildConfig.REFUSAL_ADAPTER) item {
            Text("Experimental refusal reduction", style = MaterialTheme.typography.titleLarge)
            Row {
                Switch(state.settings.refusalReduction, vm::refusalReduction, enabled = !state.busy)
                Text(if (state.settings.refusalReduction) "Enabled" else "Disabled")
            }
            Text("This build uses baked-in refusal editing. No separate adapter toggle is available.")
        }
        item { Text(state.information, style = MaterialTheme.typography.bodyMedium) }
        item { Text("Local model import", style = MaterialTheme.typography.titleLarge) }
        item { Text("Transfer GGUF by USB first. Select its manifest, then import the matching file. This app never downloads models. User-provided manifests prove integrity, not publisher authenticity.") }
        itemsIndexed(manifests.sortedBy { if (it.first == "lfm3-uncensored.json") 0 else 1 }) { _, (name, text) ->
            FilterChip(selected = text == manifestText, onClick = { manifestText = text }, label = { Text(name.removePrefix("lfm3-").removeSuffix(".json")) }, enabled = !state.busy)
        }
        item { Text(runCatching { org.meshmind.model.ModelManifest.parse(manifestText).name }.getOrDefault("Select a manifest")) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickManifest.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !state.busy) { Text("Custom manifest") }
            Button(onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = !state.busy && manifestText.isNotBlank()) { Text("Import GGUF") }
        } }
        itemsIndexed(state.models) { _, model -> Card { Column(Modifier.padding(12.dp)) {
            Text(model.manifest.name)
            Text("${model.manifest.format} · ${model.manifest.size / 1_000_000} MB", style = MaterialTheme.typography.labelMedium)
            Row {
                Button(onClick = { vm.load(model) }, enabled = !state.busy) { Text("Load") }
                TextButton(onClick = { vm.delete(model) }, enabled = !state.busy) { Text("Delete model") }
            }
        } } }
        item { OutlinedButton(onClick = vm::unload, enabled = !state.busy && state.ready) { Text("Unload model") } }
        item { Text("Generation settings", style = MaterialTheme.typography.titleLarge); Text("Unload the model before changing settings. Thinking may exhaust the short output budget.") }
        item { SettingSlider("Threads", state.settings.threads.toFloat(), 1f..8f, !state.busy && !state.ready) { vm.settings(state.settings.copy(threads = it.toInt())) } }
        item { Row { listOf(2048, 4096).forEach { value -> FilterChip(state.settings.context == value, { vm.settings(state.settings.copy(context = value)) }, label = { Text("Context $value") }, enabled = !state.busy && !state.ready) } } }
        item { SettingSlider("Maximum output tokens", state.settings.maxTokens.toFloat(), 32f..1024f, !state.busy && !state.ready) { vm.settings(state.settings.copy(maxTokens = it.toInt())) } }
        item { SettingSlider("Temperature", state.settings.temperature, 0f..2f, !state.busy && !state.ready) { vm.settings(state.settings.copy(temperature = it)) } }
        item { SettingSlider("Top-p", state.settings.topP, 0.05f..1f, !state.busy && !state.ready) { vm.settings(state.settings.copy(topP = it)) } }
        item { SettingSlider("Top-k", state.settings.topK.toFloat(), 1f..200f, !state.busy && !state.ready) { vm.settings(state.settings.copy(topK = it.toInt())) } }
        item { Row { Checkbox(state.settings.thinking, { vm.settings(state.settings.copy(thinking = it)) }, enabled = !state.busy && !state.ready); Text("Thinking mode") } }
        item { Row { Checkbox(state.settings.memoryEfficient, { vm.settings(state.settings.copy(memoryEfficient = it)) }, enabled = !state.busy && !state.ready); Text("Memory-efficient batches") } }
        item { Text("Privacy", style = MaterialTheme.typography.titleLarge); Text("No network permission, telemetry, or model server. AI history is encrypted with an Android Keystore key; unlocked or compromised devices may expose it. Backgrounding stops generation.") }
        item { OutlinedButton(onClick = vm::reset, enabled = !state.busy) { Text("Delete AI history") } }
        item { TextButton(onClick = { showNotices = !showNotices }) { Text("License notices") } }
        if (showNotices) item { Text(remember { context.assets.open("THIRD_PARTY.txt").bufferedReader().use { it.readText() } }) }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, change: (Float) -> Unit) {
    Column { Text("$label: ${"%.2f".format(value)}"); Slider(value, change, valueRange = range, enabled = enabled) }
}
