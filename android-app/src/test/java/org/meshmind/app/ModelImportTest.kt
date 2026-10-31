package org.meshmind.app

import org.junit.Assert.*
import org.junit.Test
import org.meshmind.model.ModelManifest
import org.meshmind.model.ModelVerifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ModelImportTest {
    private val data = byteArrayOf(0x47, 0x47, 0x55, 0x46, 3, 0, 0, 0) + ByteArray(32)
    private fun manifest(bytes: ByteArray = data) = ModelManifest("Test model", "1", "lfm3", "AD-Q4_K_M",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        bytes.size.toLong(), ModelManifest.BACKEND, "embedded", 2048, listOf("<|im_start|>"), null, null)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun validImportPreservesBytes() {
        val out = ByteArrayOutputStream()
        ModelVerifier.copyVerified(ByteArrayInputStream(data), out, manifest())
        assertArrayEquals(data, out.toByteArray())
    }
    @Test fun tamperedModelRejected() {
        val tampered = data.clone().also { it[15] = 7 }
        rejects { ModelVerifier.copyVerified(ByteArrayInputStream(tampered), ByteArrayOutputStream(), manifest()) }
    }
    @Test fun truncatedModelRejected() {
        rejects { ModelVerifier.copyVerified(ByteArrayInputStream(data.dropLast(1).toByteArray()), ByteArrayOutputStream(), manifest()) }
    }
    @Test fun oversizedModelRejected() {
        rejects { ModelVerifier.copyVerified(ByteArrayInputStream(data + byteArrayOf(1)), ByteArrayOutputStream(), manifest()) }
    }
    @Test fun renamedNonGgufRejectedEvenWithMatchingHash() {
        val invalid = ByteArray(40)
        rejects { ModelVerifier.copyVerified(ByteArrayInputStream(invalid), ByteArrayOutputStream(), manifest(invalid)) }
    }
    @Test fun unsupportedGgufVersionRejected() {
        val invalid = data.clone().also { it[4] = 2 }
        rejects { ModelVerifier.copyVerified(ByteArrayInputStream(invalid), ByteArrayOutputStream(), manifest(invalid)) }
    }
    @Test fun unsupportedBackendRejected() { rejects { manifest().copy(backend = "stock-llama").validate() } }
    @Test fun supportedArchitecturesAccepted() { manifest().copy(architecture = "qwen3").validate(); manifest().copy(architecture = "qwen3.5").validate(); manifest().copy(architecture = "qwen35").validate() }
    @Test fun unsupportedArchitectureRejected() { rejects { manifest().copy(architecture = "llama").validate() } }
    @Test fun unsupportedFormatRejected() { rejects { manifest().copy(format = "Q1_0").validate() } }
    @Test fun unsafeContextRejected() { rejects { manifest().copy(context = 262144).validate() } }
    @Test fun missingSpecialTokensRejected() { rejects { manifest().copy(specialTokens = emptyList()).validate() } }
    @Test fun invalidChecksumRejected() { rejects { manifest().copy(sha256 = "../../model").validate() } }
    @Test fun externalTemplateRejected() { rejects { manifest().copy(chatTemplate = "remote").validate() } }
    @Test fun manifestParsingUsesRequiredFields() {
        val json = """{"name":"Test","version":"1","base_architecture":"lfm3","gguf_format":"AD-Q4_K_M","sha256":"${manifest().sha256}","size_bytes":40,"required_backend":"${ModelManifest.BACKEND}","chat_template":"embedded","context_length":2048,"special_tokens":["<|im_start|>"]}"""
        assertEquals("AD-Q4_K_M", ModelManifest.parse(json).format)
    }
    @Test fun utf8TokensCanSplitAtEveryByte() {
        val stream = Utf8Stream()
        val text = "Hello 日本語 👋 café"
        val actual = buildString {
            text.toByteArray(Charsets.UTF_8).forEach { append(stream.append(byteArrayOf(it))) }
            append(stream.append(byteArrayOf(), true))
        }
        assertEquals(text, actual)
    }
}
