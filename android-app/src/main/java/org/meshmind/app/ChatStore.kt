package org.meshmind.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChatStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "ai-history.enc")
    private val alias = "meshmind-local-ai-history-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 2 * 1024 * 1024) { "History is too large" }
        val bytes = file.readBytes()
        require(bytes.size >= 28) { "History is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONArray(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        return List(json.length()) { val m = json.getJSONObject(it); ChatMessage(m.getString("role"), m.getString("content")) }
    }
    fun write(messages: List<ChatMessage>) {
        val data = JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }
            .toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 1024 * 1024 && messages.size <= 200) { "History limit reached; reset conversation" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val pending = File(file.parentFile, "ai-history.pending")
        pending.outputStream().use { out -> out.write(cipher.iv); out.write(cipher.doFinal(data)); out.fd.sync() }
        check(pending.renameTo(file)) { "Cannot save encrypted history" }
    }
    fun clear() {
        listOf(file, File(file.parentFile, "ai-history.pending")).forEach {
            check(!it.exists() || it.delete()) { "Cannot delete local AI history" }
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
