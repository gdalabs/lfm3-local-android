package org.meshmind.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Lfm3DeviceInferenceTest {
    @Test fun packageHasNoNetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }

    @Test fun optInBundledModelInference() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLfm3") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = ModelRepository(instrumentation.targetContext)
        repository.provisionBundled { }
        val model = repository.installed().first { it.manifest.name.contains("Uncensored") }
        repository.verify(model)
        val engine = BonsaiEngine()
        try {
            val settings = GenerationSettings(temperature = 0f, maxTokens = 64)
            engine.prepare()
            val info = engine.initialize(ModelConfig(model.file.absolutePath, model.manifest.format,
                model.manifest.specialTokens), settings)
            assertTrue(info.contains("lfm3"))
            val output = StringBuilder()
            val decoder = Utf8Stream()
            val stats = engine.generate(listOf(ChatMessage("user", "Say hello in one short sentence.")), settings) {
                output.append(decoder.append(it)); true
            }
            output.append(decoder.append(byteArrayOf(), true))
            assertTrue(output.isNotBlank())
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("lfm3_output", output.toString())
                putString("lfm3_stats", stats)
            })
        } finally { engine.close() }
    }
}
