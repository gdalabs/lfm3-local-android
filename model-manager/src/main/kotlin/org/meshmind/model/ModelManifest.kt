package org.meshmind.model

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class ModelManifest(
    val name: String, val version: String, val architecture: String, val format: String,
    val sha256: String, val size: Long, val backend: String, val chatTemplate: String,
    val context: Int, val specialTokens: List<String>, val provenance: String?, val signature: String?
) {
    companion object {
        const val BACKEND = "lfm3-19cce691-cpu"
        fun parse(text: String): ModelManifest {
            require(text.length <= 65536) { "Manifest exceeds 64 KiB" }
            val j = JSONObject(text)
            val tokens = j.getJSONArray("special_tokens")
            return ModelManifest(j.getString("name"), j.getString("version"), j.getString("base_architecture"),
                j.getString("gguf_format"), j.getString("sha256"), j.getLong("size_bytes"),
                j.getString("required_backend"), j.getString("chat_template"), j.getInt("context_length"),
                List(tokens.length()) { tokens.getString(it) }, j.optString("provenance").ifEmpty { null },
                j.optString("signature").ifEmpty { null }).also { it.validate() }
        }
    }
    fun validate() {
        require(name.isNotBlank() && name.length <= 160 && version.isNotBlank()) { "Missing model name/version" }
        require(architecture == "lfm3") { "Unsupported architecture $architecture; this build supports lfm3 only" }
        require(format in setOf("AD-Q4_K_M", "AD-Q6_K")) { "Unsupported format $format" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Expected a lowercase SHA-256 checksum" }
        require(size in 24L..4_000_000_000L) { "Model size must be between 24 bytes and 4 GB" }
        require(backend == BACKEND) { "Required backend $backend is unavailable; install a compatible MeshMind build" }
        require(chatTemplate == "embedded") { "Only the GGUF embedded chat template is supported" }
        require(context in 512..4096) { "Use a context length between 512 and 4096" }
        require(specialTokens.isNotEmpty() && specialTokens.size <= 32) { "Missing special token requirements" }
        require(specialTokens.all { it.isNotBlank() && it.length <= 256 }) { "Invalid special token requirement" }
    }
}

object ModelVerifier {
    fun copyVerified(input: InputStream, output: OutputStream, manifest: ModelManifest, progress: (Long) -> Unit = {}) {
        manifest.validate()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val prefix = ByteArray(8)
        var prefixSize = 0
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            total += n
            require(total <= manifest.size) { "Model is larger than manifest size" }
            val take = minOf(8 - prefixSize, n)
            if (take > 0) { buffer.copyInto(prefix, prefixSize, 0, take); prefixSize += take }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            progress(total)
        }
        require(total == manifest.size) { "Incomplete model: expected ${manifest.size}, received $total bytes" }
        require(prefix.sliceArray(0..3).contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) { "Not a GGUF model" }
        require(prefix[4] == 3.toByte() && prefix.drop(5).all { it == 0.toByte() }) { "Only GGUF version 3 is supported" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == manifest.sha256) { "SHA-256 mismatch; model was not activated" }
    }
}
