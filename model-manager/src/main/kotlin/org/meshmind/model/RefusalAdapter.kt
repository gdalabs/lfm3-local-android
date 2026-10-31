package org.meshmind.model

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class RefusalAdapter(val size: Long, val sha256: String, val baseSha256: String, val baseFormat: String) {
    companion object {
        fun parse(text: String): RefusalAdapter {
            val json = JSONObject(text)
            val adapter = RefusalAdapter(json.getLong("size_bytes"), json.getString("sha256"),
                json.getString("base_sha256"), json.getString("base_format"))
            require(adapter.size in 1..(4L * 1024 * 1024)) { "Adapter size exceeds preview limit" }
            require(adapter.sha256.matches(Regex("[0-9a-f]{64}")) && adapter.baseSha256.matches(Regex("[0-9a-f]{64}")))
            require(adapter.baseFormat == "PTQ1_0" && json.getInt("scale") == 1) { "Unsupported adapter configuration" }
            return adapter
        }
    }
    fun requireBase(manifest: ModelManifest) {
        require(manifest.sha256 == baseSha256 && manifest.format == baseFormat) {
            "This preview adapter requires the exact official Bonsai 2 2B PTQ1_0 model"
        }
    }
    fun copyVerified(input: InputStream, output: OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= size) { "Adapter exceeds expected size" }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
        require(count == size && digest.digest().joinToString("") { "%02x".format(it) } == sha256) {
            "Adapter checksum or size mismatch"
        }
    }
}
