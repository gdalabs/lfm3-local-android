package org.meshmind.app

import android.content.Context
import android.net.Uri
import org.meshmind.model.ModelManifest
import org.meshmind.model.ModelVerifier
import org.meshmind.model.RefusalAdapter
import java.io.File
import java.util.UUID

data class InstalledModel(val manifest: ModelManifest, val directory: File) {
    val file get() = File(directory, "model.gguf")
}
class ModelRepository(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    fun adapterFor(model: InstalledModel): String {
        if (!BuildConfig.REFUSAL_ADAPTER) return ""
        val metadata = context.assets.open("refusal-adapter.json").bufferedReader().use { RefusalAdapter.parse(it.readText()) }
        metadata.requireBase(model.manifest)
        val destination = File(root, "refusal-adapter.gguf")
        val staging = File(root, ".adapter-${UUID.randomUUID()}")
        try {
            context.assets.open("refusal-adapter.gguf").use { input -> staging.outputStream().use { output ->
                metadata.copyVerified(input, output)
                output.fd.sync()
            } }
            check(staging.renameTo(destination)) { "Could not install verified adapter" }
        } finally { staging.delete() }
        return destination.absolutePath
    }
    fun installed(): List<InstalledModel> = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }.mapNotNull {
        runCatching { InstalledModel(ModelManifest.parse(File(it, "manifest.json").readText()), it) }
            .getOrNull()?.takeIf { m -> m.file.isFile && m.file.length() == m.manifest.size }
    }.sortedBy { it.manifest.name }
    fun officialManifests(): List<Pair<String, String>> = context.assets.list("").orEmpty()
        .filter { it.endsWith(".json") && (it != "bonsai-PTQ1_0.json" || BuildConfig.REFUSAL_ADAPTER) }
        .map { it to context.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
    fun import(uri: Uri, manifestText: String, progress: (Long) -> Unit): InstalledModel {
        return install(manifestText, { context.contentResolver.openInputStream(uri) ?: error("Cannot open selected model") }, progress)
    }
    fun provisionBundled(progress: (Long) -> Unit) {
        if (!context.assets.list("").orEmpty().contains("bundled-model.json")) return
        val text = context.assets.open("bundled-model.json").bufferedReader().use { it.readText() }
        val manifest = ModelManifest.parse(text)
        if (installed().any { it.manifest.sha256 == manifest.sha256 }) return
        install(text, { context.assets.open("bundled-model.gguf") }, progress)
    }
    private fun install(manifestText: String, source: () -> java.io.InputStream, progress: (Long) -> Unit): InstalledModel {
        val manifest = ModelManifest.parse(manifestText)
        if (BuildConfig.REFUSAL_ADAPTER) context.assets.open("refusal-adapter.json").bufferedReader().use {
            RefusalAdapter.parse(it.readText()).requireBase(manifest)
        }
        require(root.usableSpace > manifest.size + 128L * 1024 * 1024) { "Not enough free storage for import" }
        val destination = File(root, manifest.sha256)
        require(!destination.exists()) { "This model is already imported" }
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val input = source()
            input.use { source -> File(staging, "model.gguf").outputStream().use { out ->
                ModelVerifier.copyVerified(source, out, manifest, progress)
                out.fd.sync()
            } }
            File(staging, "manifest.json").writeText(manifestText)
            check(staging.renameTo(destination)) { "Could not finalize model import" }
            return InstalledModel(manifest, destination)
        } finally {
            staging.listFiles().orEmpty().forEach { it.delete() }
            staging.delete()
        }
    }
    fun verify(model: InstalledModel) {
        model.file.inputStream().use { source ->
            ModelVerifier.copyVerified(source, object : java.io.OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }, model.manifest)
        }
    }
    fun delete(model: InstalledModel) {
        require(model.directory.canonicalFile.parentFile == root.canonicalFile)
        listOf(File(model.directory, "model.gguf"), File(model.directory, "manifest.json"), model.directory).forEach {
            check(!it.exists() || it.delete()) { "Cannot delete local model" }
        }
    }
}
