package org.meshmind.app

data class ChatMessage(val role: String, val content: String)
data class ModelConfig(val path: String, val format: String, val specialTokens: List<String>, val adapterPath: String = "")
data class GenerationSettings(
    val threads: Int = 2, val context: Int = 2048, val maxTokens: Int = 384,
    val temperature: Float = 0.1f, val topP: Float = 1f, val topK: Int = 50,
    val thinking: Boolean = false, val memoryEfficient: Boolean = true, val seed: Int = 42,
    val refusalReduction: Boolean = false
)
data class EngineCapabilities(val architectures: Set<String>, val formats: Set<String>, val backend: String)
interface LocalLLMEngine : AutoCloseable {
    fun initialize(config: ModelConfig, settings: GenerationSettings): String
    fun generate(messages: List<ChatMessage>, settings: GenerationSettings, stream: (ByteArray) -> Boolean): String
    fun cancel()
    fun prepare()
    fun resetContext()
    fun unload()
    fun getCapabilities(): EngineCapabilities
}

class BonsaiEngine : LocalLLMEngine {
    companion object { init { System.loadLibrary("meshmind") } }
    private val handle = java.util.concurrent.atomic.AtomicLong(nativeCreate())
    private fun activeHandle(): Long = handle.get().also { check(it != 0L) { "Inference engine is closed" } }
    interface TokenSink { fun onBytes(bytes: ByteArray): Boolean }
    private external fun nativeCreate(): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeLoad(handle: Long, path: ByteArray, context: Int, threads: Int, efficient: Boolean,
        format: ByteArray, specialTokens: Array<ByteArray>, adapterPath: ByteArray): ByteArray
    private external fun nativeGenerate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, seed: Int, thinking: Boolean, refusalReduction: Boolean, sink: TokenSink): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativePrepare(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeUnload(handle: Long)
    override fun initialize(config: ModelConfig, settings: GenerationSettings) = nativeLoad(
        activeHandle(), config.path.toByteArray(Charsets.UTF_8), settings.context, settings.threads, settings.memoryEfficient,
        config.format.toByteArray(Charsets.UTF_8), config.specialTokens.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        config.adapterPath.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
    override fun generate(messages: List<ChatMessage>, settings: GenerationSettings, stream: (ByteArray) -> Boolean) =
        nativeGenerate(activeHandle(), messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(), settings.maxTokens,
            settings.temperature, settings.topP, settings.topK, settings.seed, settings.thinking, settings.refusalReduction,
            object : TokenSink { override fun onBytes(bytes: ByteArray) = stream(bytes) }).toString(Charsets.UTF_8)
    override fun cancel() = nativeCancel(handle.get())
    override fun prepare() = nativePrepare(activeHandle())
    override fun resetContext() = nativeReset(activeHandle())
    override fun unload() = nativeUnload(activeHandle())
    override fun close() { val previous = handle.getAndSet(0); if (previous != 0L) nativeClose(previous) }
    override fun getCapabilities() = EngineCapabilities(setOf("lfm3"), setOf("AD-Q4_K_M", "AD-Q6_K"), "lfm3-19cce691-cpu")
}
