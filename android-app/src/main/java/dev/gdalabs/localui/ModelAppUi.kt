// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 GDA Labs
package dev.gdalabs.localui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class AppIdentity(val family: String, val variant: String, val version: String, val preferredManifest: String, val appLicense: String, val modelLicense: String, val preferredFormat: String = "")
data class UiMessage(val role: String, val content: String, val reasoning: Boolean = false)
data class UiModel(val id: String, val name: String, val format: String, val bytes: Long)
data class UiTuning(val threads: Int, val context: Int, val maxTokens: Int, val temperature: Float, val topP: Float, val topK: Int, val thinking: Boolean, val memoryEfficient: Boolean, val refusalReduction: Boolean = false)
data class ModelAppState(val messages: List<UiMessage>, val models: List<UiModel>, val ready: Boolean, val busy: Boolean, val generating: Boolean, val selected: String?, val status: String, val error: String?, val information: String, val stats: String, val tuning: UiTuning)
data class ModelAppActions(val send: (String) -> Unit, val stop: () -> Unit, val reset: () -> Unit, val load: (String) -> Unit, val unload: () -> Unit, val delete: (String) -> Unit, val import: (Uri, String) -> Unit, val clearError: () -> Unit, val tune: (UiTuning) -> Unit, val outputLimit: (Int) -> Unit, val refusal: ((Boolean) -> Unit)? = null)

fun preferredModel(models: List<UiModel>, format: String): UiModel? = models.firstOrNull { it.format == format } ?: models.firstOrNull()

@Composable
fun ModelAppTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFB9AEFF), onPrimary = Color(0xFF241B4A), primaryContainer = Color(0xFF353052),
        secondary = Color(0xFF80D8BB), background = Color(0xFF101217), surface = Color(0xFF171A21),
        surfaceVariant = Color(0xFF252934), onBackground = Color(0xFFF0F1F7), onSurface = Color(0xFFF0F1F7), onSurfaceVariant = Color(0xFFADB3C3), outline = Color(0xFF424757)
    ) else lightColorScheme(
        primary = Color(0xFF6552BB), onPrimary = Color.White, primaryContainer = Color(0xFFEAE5FF),
        secondary = Color(0xFF187F63), background = Color(0xFFF7F8FC), surface = Color.White,
        surfaceVariant = Color(0xFFEEF0F7), onBackground = Color(0xFF202330), onSurface = Color(0xFF202330), onSurfaceVariant = Color(0xFF626A7B), outline = Color(0xFFCBD0DE)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp)), content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelAppScreen(identity: AppIdentity, state: ModelAppState, actions: ModelAppActions,
                   manifests: List<Pair<String, String>>, manifestName: (String) -> String, initialPage: Int = 0) {
    var page by rememberSaveable { mutableIntStateOf(initialPage) }
    var draft by rememberSaveable { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark(identity.family, 42)
                    Column(Modifier.weight(1f)) {
                        Text(identity.family, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline))
                            Text(if (state.ready && actions.refusal != null && !state.tuning.refusalReduction) "Ready · Adapter off" else if (state.ready) "Ready · On device" else "On-device AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.messages.isNotEmpty() && page == 0) TextButton({ clear = true }, enabled = !state.busy) { Text("New chat") }
                    else VariantPill(identity.variant)
                }
            },
            bottomBar = {
                if (!keyboard) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("Chat", "Models", "Settings").forEachIndexed { index, title ->
                        NavigationBarItem(page == index, { page = index }, icon = { NavSymbol(index, page == index) }, label = { Text(title) })
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                state.error?.let { ErrorBanner(it, actions.clearError) }
                if (state.busy && !state.generating) PreparationBanner(state.status)
                when (page) {
                    0 -> ChatScreen(identity, state, actions, draft, { draft = it }, { page = 1 })
                    1 -> ModelsScreen(identity, state, actions, manifests, manifestName) { page = 0 }
                    2 -> SettingsScreen(identity, state, actions) { clear = true }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Start a new chat?") },
        text = { Text("The current conversation will be cleared from this device.") },
        confirmButton = { TextButton({ clear = false; actions.reset(); page = 0 }) { Text("New chat") } },
        dismissButton = { TextButton({ clear = false }) { Text("Keep chat") } })
}

@Composable
private fun NavSymbol(index: Int, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24
        val stroke = Stroke(1.8f * unit)
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(3 * unit, 3 * unit), Size(18 * unit, 14 * unit), CornerRadius(4 * unit), style = stroke)
                drawLine(color, Offset(7 * unit, 17 * unit), Offset(7 * unit, 21 * unit), 1.8f * unit)
                drawLine(color, Offset(7 * unit, 21 * unit), Offset(12 * unit, 17 * unit), 1.8f * unit)
            }
            1 -> {
                drawRoundRect(color, Offset(6 * unit, 3 * unit), Size(14 * unit, 16 * unit), CornerRadius(3 * unit), style = stroke)
                drawRoundRect(color, Offset(3 * unit, 6 * unit), Size(14 * unit, 16 * unit), CornerRadius(3 * unit), style = stroke)
            }
            else -> for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                drawLine(color, Offset(3 * unit, y * unit), Offset(21 * unit, y * unit), 1.8f * unit)
                drawCircle(color, 2.5f * unit, Offset(x * unit, y * unit))
            }
        }
    }
}

@Composable
private fun BrandMark(family: String, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .32f).dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary.copy(alpha = .23f)))), contentAlignment = Alignment.Center) {
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize().padding((size * .15f).dp)) {
            val u = this.size.width / 40
            if (family.startsWith("Bonsai")) {
                val leaf = Path().apply { moveTo(9*u, 29*u); cubicTo(1*u, 13*u, 19*u, 5*u, 33*u, 5*u); cubicTo(34*u, 20*u, 26*u, 34*u, 9*u, 29*u); close() }
                drawPath(leaf, color.copy(alpha = .8f))
                drawLine(color, Offset(6*u, 35*u), Offset(26*u, 13*u), 2.7f*u, StrokeCap.Round)
            } else for (y in listOf(11f, 20f, 29f)) {
                val wave = Path().apply { moveTo(4*u, y*u); cubicTo(13*u, (y-9)*u, 26*u, (y+9)*u, 36*u, y*u) }
                drawPath(wave, color, style = Stroke(3*u, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun VariantPill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorBanner(error: String, dismiss: () -> Unit) {
    var details by remember(error) { mutableStateOf(false) }
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Something needs your attention", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(dismiss) { Text("Dismiss") }
            }
            TextButton({ details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "Hide details" else "Show details") }
            if (details) SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PreparationBanner(status: String) {
    Row(Modifier.fillMaxWidth().padding(20.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Column {
            Text(if (status.contains("Provisioning")) "Preparing your model" else if (status.contains("loading", true)) "Loading your model" else "Getting ready", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatScreen(identity: AppIdentity, state: ModelAppState, actions: ModelAppActions, draft: String, setDraft: (String) -> Unit, models: () -> Unit) {
    val list = rememberLazyListState()
    val context = LocalContext.current
    val nearBottom by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.messages.lastIndex - 1 } ?: true } }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty() && nearBottom) list.scrollToItem(state.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(identity.family, 76)
            Spacer(Modifier.height(24.dp))
            Text(if (state.ready) "A little space to think." else "Your AI.\nOn your phone.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(if (state.ready) "Ask a question, work on an idea, or write something new." else "Load your model once, then chat entirely on your device.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (!state.ready) Button(onClick = {
                val model = preferredModel(state.models, identity.preferredFormat)
                if (model != null) actions.load(model.id) else models()
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                Text(if (state.models.isNotEmpty()) "Load model & start" else "Choose a model")
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Explain simply" to "Explain a topic in simple terms.", "Help me write" to "Help me write a short message.", "Explore an idea" to "Help me brainstorm an idea.").forEach { (label, prompt) ->
                    SuggestionChip({ setDraft(prompt) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("No account. No cloud connection.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(20.dp, 18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(state.messages) { index, message ->
                val user = message.role == "user"
                if (user) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(Modifier.fillMaxWidth(.88f), shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        SelectionContainer { Text(message.content, Modifier.padding(16.dp, 13.dp), style = MaterialTheme.typography.bodyLarge) }
                    }
                } else {
                    val reply = remember(message.content, message.reasoning) { presentReply(message.content, message.reasoning) }
                    var reasoning by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrandMark(identity.family, 26)
                            Text(identity.family, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (reply.reasoning.isNotBlank() || reply.awaitingAnswer) {
                            TextButton({ reasoning = !reasoning }, contentPadding = PaddingValues(0.dp)) {
                                if (state.generating && index == state.messages.lastIndex && reply.awaitingAnswer) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                                Text((if (reply.awaitingAnswer && state.generating) "Thinking" else "Reasoning") + if (reasoning) "  −" else "  +")
                            }
                            if (reasoning) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                                SelectionContainer { MarkdownView(reply.reasoning, Modifier.padding(14.dp)) }
                            }
                        }
                        if (reply.answer.isNotBlank()) SelectionContainer { MarkdownView(reply.answer, Modifier.fillMaxWidth()) }
                        else if (state.generating && index == state.messages.lastIndex && !reply.awaitingAnswer) Text("Writing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (!state.generating && reply.awaitingAnswer) Text("The reply stopped before a final answer.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (reply.answer.isNotBlank() && !(state.generating && index == state.messages.lastIndex)) TextButton({
                            val text = MarkdownReader.parse(reply.answer).plainText
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(identity.family, text))
                        }, contentPadding = PaddingValues(0.dp)) { Text("Copy reply", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
        if (!state.ready && state.messages.isNotEmpty()) TextButton(models, Modifier.align(Alignment.CenterHorizontally), enabled = !state.busy) { Text("Load a model to continue") }
        Composer(state, actions, draft, setDraft)
    }
}

@Composable
private fun Composer(state: ModelAppState, actions: ModelAppActions, draft: String, setDraft: (String) -> Unit) {
    var lengths by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f))) {
        Column(Modifier.padding(8.dp)) {
            OutlinedTextField(draft, { if (it.length <= 16384) setDraft(it) }, Modifier.fillMaxWidth(),
                placeholder = { Text(if (state.ready) "Message your local AI…" else "Load a model to start") },
                enabled = !state.busy && state.ready, minLines = 1, maxLines = 5, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent))
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton({ lengths = true }, enabled = !state.busy) { Text("Reply limit · ${state.tuning.maxTokens}", style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(lengths, { lengths = false }) {
                        listOf(256 to "Short", 512 to "Balanced", 1024 to "Long").forEach { (value, title) ->
                            DropdownMenuItem(text = { Text("$title · $value tokens") }, onClick = { lengths = false; actions.outputLimit(value) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.generating) FilledTonalButton(actions.stop, shape = CircleShape) { Text("■  Stop") }
                else Button({ val text = draft; setDraft(""); actions.send(text) }, enabled = state.ready && !state.busy && draft.isNotBlank(), shape = CircleShape) { Text("↑  Send") }
            }
        }
    }
    Text("On device · AI can make mistakes", Modifier.fillMaxWidth().padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelsScreen(identity: AppIdentity, state: ModelAppState, actions: ModelAppActions, manifests: List<Pair<String, String>>, manifestName: (String) -> String, chat: () -> Unit) {
    var importer by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<UiModel?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Your models", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text("Keep a model on your phone. Load it whenever you want to chat.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.models.isEmpty()) item { InfoCard("Ready when you are", "A bundled model appears here after setup. You can also import a compatible GGUF from your device.") }
        itemsIndexed(state.models) { _, model ->
            val active = state.ready && state.selected == model.id
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandMark(identity.family, 42)
                        Column(Modifier.weight(1f)) { Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text("${model.format} · ${modelSize(model.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (active) VariantPill("Loaded · Ready to chat")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ if (active) chat() else { actions.load(model.id); chat() } }, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text(if (active) "Open chat" else "Load model") }
                        if (active) TextButton(actions.unload, enabled = !state.busy) { Text("Unload") }
                        else TextButton({ deleting = model }, enabled = !state.busy) { Text("Remove") }
                    }
                }
            }
        }
        item { OutlinedButton({ importer = !importer }, Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = MaterialTheme.shapes.medium, enabled = !state.busy) { Text(if (importer) "Close import" else "+  Import a model") } }
        if (importer) item { ModelImporter(identity, manifests, manifestName, state.busy, actions.import) }
        item { Text("Models are verified locally before loading. Importing a new model keeps your conversation on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
    }
    deleting?.let { model -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove this model?") }, text = { Text("${model.name} will be removed from this device. Your conversation is kept.") }, confirmButton = { TextButton({ deleting = null; actions.delete(model.id) }) { Text("Remove") } }, dismissButton = { TextButton({ deleting = null }) { Text("Keep model") } }) }
}

private fun modelSize(bytes: Long): String = if (bytes >= 1_000_000_000) String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0) else "${bytes / 1_000_000} MB"

@Composable
private fun ModelImporter(identity: AppIdentity, manifests: List<Pair<String, String>>, name: (String) -> String, busy: Boolean, import: (Uri, String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(manifests.firstOrNull { it.first == identity.preferredManifest }?.second ?: manifests.firstOrNull()?.second.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri, selected) }
    val manifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runCatching {
        val text = context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (output.size() <= 65536) {
                val n = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size())); if (n < 0) break
                output.write(buffer, 0, n)
            }
            require(output.size() <= 65536) { "Manifest exceeds 64 KiB" }; output.toString("UTF-8")
        }
        name(text); selected = text; error = null
    }.onFailure { error = it.message } }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import from your device", style = MaterialTheme.typography.titleMedium)
            Text("Transfer the GGUF file to your phone first, then select the matching variant below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton({ menu = true }, enabled = !busy) { Text(runCatching { name(selected) }.getOrDefault("Select a variant"), maxLines = 2) }
                DropdownMenu(menu, { menu = false }) { manifests.forEach { (_, text) -> DropdownMenuItem(text = { Text(runCatching { name(text) }.getOrDefault("Model")) }, onClick = { selected = text; menu = false }) } }
            }
            Button({ file.launch(arrayOf("*/*")) }, enabled = !busy && selected.isNotBlank(), shape = MaterialTheme.shapes.medium) { Text("Choose GGUF file") }
            TextButton({ manifest.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !busy) { Text("Use a custom manifest") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(identity: AppIdentity, state: ModelAppState, actions: ModelAppActions, clear: () -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val canTune = !state.busy && !state.ready
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Make it yours", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text("A few controls, without the noise.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { InfoCard("Private by design", "Replies are generated on your device. No account or cloud connection is required. Conversation history is encrypted locally.") }
        actions.refusal?.let { change -> item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Refusal reduction", style = MaterialTheme.typography.titleMedium); Text("Optional local adapter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(state.tuning.refusalReduction, change, enabled = !state.busy) }
                    Text("Changes take effect on the next reply. Start a new chat to compare the same question.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        item { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Response length", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(256 to "Short", 512 to "Balanced", 1024 to "Long").forEach { (value, label) -> FilterChip(state.tuning.maxTokens == value, { actions.outputLimit(value) }, label = { Text(label) }, enabled = !state.busy) } }
                Text("A longer limit lets the model write more. It may take longer to finish.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        item { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Advanced controls", style = MaterialTheme.typography.titleMedium); Text("Memory and generation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide" else "Show") } }
                if (advanced) {
                    if (state.ready) { Text("Unload your model before changing these controls.", style = MaterialTheme.typography.bodySmall); OutlinedButton(actions.unload, enabled = !state.busy) { Text("Unload model") } }
                    TuningSlider("Threads", state.tuning.threads.toFloat(), 1f..8f, canTune) { actions.tune(state.tuning.copy(threads = it.toInt())) }
                    Text("Context window", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2048, 4096).forEach { v -> FilterChip(state.tuning.context == v, { actions.tune(state.tuning.copy(context = v)) }, label = { Text(if (v == 2048) "2K" else "4K") }, enabled = canTune) } }
                    TuningSlider("Temperature", state.tuning.temperature, 0f..2f, canTune) { actions.tune(state.tuning.copy(temperature = it)) }
                    TuningSlider("Top-p", state.tuning.topP, .05f..1f, canTune) { actions.tune(state.tuning.copy(topP = it)) }
                    TuningSlider("Top-k", state.tuning.topK.toFloat(), 1f..200f, canTune) { actions.tune(state.tuning.copy(topK = it.toInt())) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Reasoning mode"); Text("Can use more of the reply limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(state.tuning.thinking, { actions.tune(state.tuning.copy(thinking = it)) }, enabled = canTune) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Memory-efficient batches", Modifier.weight(1f)); Switch(state.tuning.memoryEfficient, { actions.tune(state.tuning.copy(memoryEfficient = it)) }, enabled = canTune) }
                }
            }
        } }
        item { TextButton(clear, enabled = !state.busy && state.messages.isNotEmpty()) { Text("Clear conversation") } }
        item { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(identity.family + " " + identity.variant, style = MaterialTheme.typography.titleMedium)
                Text(identity.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Independent Android app by GDA Labs. Model authors retain their names and licenses.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ licenses = true }, contentPadding = PaddingValues(0.dp)) { Text("Licenses & notices") }
                TextButton({ diagnostic = !diagnostic }, contentPadding = PaddingValues(0.dp)) { Text(if (diagnostic) "Hide diagnostics" else "Diagnostics") }
                if (diagnostic) SelectionContainer { Text(state.information + if (state.stats.isNotBlank()) "\n\n" + state.stats else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        } }
    }
    if (licenses) LicenseDialog(identity) { licenses = false }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, set: (Float) -> Unit) {
    Column { Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(String.format(java.util.Locale.ROOT, "%.2f", value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Slider(value, set, valueRange = range, enabled = enabled) }
}

@Composable
private fun LicenseDialog(identity: AppIdentity, close: () -> Unit) {
    val context = LocalContext.current
    var asset by remember { mutableStateOf("THIRD_PARTY.txt") }
    val text = remember(asset) { runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("See the source repository for complete license notices.") }
    AlertDialog(onDismissRequest = close, title = { Text("Licenses & notices") }, text = {
        Column(Modifier.heightIn(max = 460.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Overview" to "THIRD_PARTY.txt", "App" to identity.appLicense, "Model" to identity.modelLicense, "Markdown" to "commonmark-BSD-2-Clause.txt").forEach { (name, file) -> FilterChip(asset == file, { asset = file }, label = { Text(name) }) }
            }
            SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(close) { Text("Done") } })
}
