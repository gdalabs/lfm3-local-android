package org.meshmind.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(
    val messages: List<ChatMessage> = emptyList(), val models: List<InstalledModel> = emptyList(),
    val ready: Boolean = false, val busy: Boolean = false, val generating: Boolean = false,
    val information: String = "Import a supported LFM3 GGUF to begin.",
    val status: String = "Offline AI", val error: String? = null, val stats: String = "",
    val selected: String? = null, val settings: GenerationSettings = GenerationSettings(refusalReduction = BuildConfig.REFUSAL_ADAPTER)
)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val repository = ModelRepository(application)
    private val history = ChatStore(application)
    private val engine: LocalLLMEngine = BonsaiEngine()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "meshmind-inference") }
    private val foreground = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    init {
        mutable.update { it.copy(busy = true, status = "Checking local models...") }
        worker.execute {
            try {
                repository.provisionBundled { count -> mutable.update { it.copy(status = "Provisioning bundled model: ${count / 1_000_000} MB") } }
                mutable.update { it.copy(models = repository.installed(), messages = history.read()) }
            } catch (e: Exception) { mutable.update { it.copy(error = "Local initialization failed: ${e.message}", models = repository.installed()) } }
            finally { mutable.update { it.copy(busy = false, status = "Offline AI") } }
        }
    }
    private fun task(label: String, action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, status = label) }
        stopped.set(false)
        engine.prepare()
        worker.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try { action() }
            catch (e: OutOfMemoryError) { engine.unload(); mutable.update { it.copy(ready = false, error = "Out of memory; reduce context and enable memory-efficient mode") } }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
            finally { mutable.update { it.copy(busy = false, generating = false, status = "Offline AI") } }
        }
    }
    fun setForeground(value: Boolean) { foreground.set(value); if (!value) stop() }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun refusalReduction(value: Boolean) {
        if (!mutable.value.busy && BuildConfig.REFUSAL_ADAPTER) mutable.update {
            it.copy(settings = it.settings.copy(refusalReduction = value))
        }
    }
    fun settings(value: GenerationSettings) {
        if (!mutable.value.busy && !mutable.value.ready) mutable.update { it.copy(settings = value) }
    }
    fun import(uri: Uri, manifest: String) = task("Verifying local model...") {
        repository.import(uri, manifest) { count ->
            check(!stopped.get()) { "Model import cancelled" }
            mutable.update { it.copy(status = "Import: ${count / 1_000_000} MB checked") }
        }
        mutable.update { it.copy(models = repository.installed()) }
    }
    fun load(model: InstalledModel) = task("Checking SHA-256 and loading model...") {
        engine.unload()
        mutable.update { it.copy(ready = false, selected = null) }
        repository.verify(model)
        if (!foreground.get()) error("Model loading cancelled because the app entered the background")
        val settings = mutable.value.settings.copy(context = minOf(mutable.value.settings.context, model.manifest.context))
        val adapterPath = repository.adapterFor(model)
        val info = engine.initialize(ModelConfig(model.file.absolutePath, model.manifest.format, model.manifest.specialTokens, adapterPath), settings)
        if (!foreground.get()) { engine.unload(); error("Model loading cancelled in background") }
        mutable.update { it.copy(ready = true, selected = model.manifest.sha256, information = info, settings = settings) }
    }
    fun unload() { stop(); task("Releasing model memory...") {
        engine.unload(); mutable.update { it.copy(ready = false, selected = null, information = "No model loaded") }
    } }
    fun delete(model: InstalledModel) = task("Deleting model...") {
        if (mutable.value.selected == model.manifest.sha256) {
            engine.unload(); mutable.update { it.copy(ready = false, selected = null) }
        }
        repository.delete(model); mutable.update { it.copy(models = repository.installed()) }
    }
    fun reset() = task("Clearing conversation...") { history.clear(); engine.resetContext(); mutable.update { it.copy(messages = emptyList(), stats = "") } }
    fun stop() { stopped.set(true); engine.cancel() }
    fun send(text: String) {
        if (text.isBlank() || text.length > 16384 || !mutable.value.ready || mutable.value.busy || !foreground.get()) return
        stopped.set(false)
        task("Generating on this device...") {
            if (!foreground.get() || stopped.get()) return@task
            val before = mutable.value
            check(before.messages.size < 198) { "Conversation limit reached; reset it" }
            val messages = before.messages + ChatMessage("user", text)
            mutable.update { it.copy(messages = messages + ChatMessage("assistant", ""), generating = true) }
            val decoder = Utf8Stream()
            val output = StringBuilder()
            val battery = getApplication<Application>().getSystemService(BatteryManager::class.java)
            val power = getApplication<Application>().getSystemService(PowerManager::class.java)
            val chargeBefore = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val cpuStart = Process.getElapsedCpuTime()
            var lastPublish = 0L
            try {
                val stats = engine.generate(messages, before.settings) { chunk ->
                    output.append(decoder.append(chunk))
                    val now = System.nanoTime()
                    if (now - lastPublish > 50_000_000L) {
                        val current = output.toString()
                        mutable.update { it.copy(messages = messages + ChatMessage("assistant", current)) }
                        lastPublish = now
                    }
                    foreground.get() && !stopped.get()
                }
                output.append(decoder.append(byteArrayOf(), true))
                val chargeAfter = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val charge = if (chargeBefore > 0 && chargeAfter > 0) (chargeBefore - chargeAfter).toString() else "unavailable"
                val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus.toString() else "unavailable"
                mutable.update { it.copy(stats = "$stats\nProcess CPU ms: ${Process.getElapsedCpuTime() - cpuStart}; charge delta uAh: $charge; thermal status: $thermal") }
            } finally {
                val finalMessages = messages + ChatMessage("assistant", output.toString())
                mutable.update { it.copy(messages = finalMessages) }
                history.write(finalMessages)
            }
        }
    }
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            stop()
            worker.execute { engine.unload(); mutable.update { it.copy(ready = false, selected = null) } }
        }
    }
    override fun onCleared() {
        stop()
        worker.execute { engine.close() }
        worker.shutdown()
    }
}
