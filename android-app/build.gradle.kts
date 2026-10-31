import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val emulatorBuild = providers.gradleProperty("meshmindEmulator").orNull == "true"
val bundledModel = providers.gradleProperty("meshmindBundledModel").orNull
val bundledFormat = providers.gradleProperty("meshmindBundledFormat").orElse("AD-Q4_K_M").get()
val uncensored = false
val bundledAdapter = providers.gradleProperty("meshmindBundledAdapter").orNull
require(!uncensored || (bundledModel != null && bundledFormat == "PTQ1_0" && bundledAdapter != null)) {
    "Uncensored preview requires the pinned PTQ1_0 model and AtomicChat adapter"
}
require(uncensored || bundledAdapter == null) { "Adapters require the separate uncensored application" }
val bundledAssets = layout.buildDirectory.dir("generated/bundled-assets-" + if (uncensored) "uncensored" else "standard")
val prepareBundledModel = if (bundledModel != null) tasks.register("prepareBundledModel") {
    require(bundledFormat in setOf("AD-Q4_K_M", "AD-Q6_K")) { "Unsupported bundled format" }
    val source = file(bundledModel)
    val manifest = file("../model-manager/manifests/" + if (bundledFormat == "AD-Q6_K") "lfm3-uncensored-q6.json" else "lfm3-uncensored.json")
    inputs.files(source, manifest)
    inputs.property("uncensored", uncensored)
    if (uncensored) inputs.files(file(bundledAdapter!!), file("../model-manager/adapters/atomicchat-2b.json"))
    outputs.dir(bundledAssets)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val metadata = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any>
        require(source.length() == (metadata["size_bytes"] as Number).toLong()) { "Bundled model size mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
        } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == metadata["sha256"]) { "Bundled model checksum mismatch" }
        val output = bundledAssets.get().asFile.apply { mkdirs() }
        source.copyTo(output.resolve("bundled-model.gguf"), overwrite = true)
        manifest.copyTo(output.resolve("bundled-model.json"), overwrite = true)
        if (uncensored) {
            val adapterManifest = file("../model-manager/adapters/atomicchat-2b.json")
            @Suppress("UNCHECKED_CAST")
            val adapterMetadata = groovy.json.JsonSlurper().parse(adapterManifest) as Map<String, Any>
            require(metadata["sha256"] == adapterMetadata["base_sha256"]) { "Adapter base mismatch" }
            val adapterSource = file(bundledAdapter!!)
            require(adapterSource.length() == (adapterMetadata["size_bytes"] as Number).toLong()) { "Adapter size mismatch" }
            val adapterDigest = MessageDigest.getInstance("SHA-256").digest(adapterSource.readBytes())
            require(adapterDigest.joinToString("") { "%02x".format(it) } == adapterMetadata["sha256"]) { "Adapter checksum mismatch" }
            adapterSource.copyTo(output.resolve("refusal-adapter.gguf"), overwrite = true)
            adapterManifest.copyTo(output.resolve("refusal-adapter.json"), overwrite = true)
        }
    }
} else null
android {
    namespace = "org.meshmind.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.meshmind.app.lfm3.uncensored"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-lfm3-preview"
        buildConfigField("boolean", "REFUSAL_ADAPTER", uncensored.toString())
        manifestPlaceholders["appLabel"] = "LFM3 Local Uncensored"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (emulatorBuild) "x86_64" else "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            if (emulatorBuild) arguments += "-DMESHMIND_EMULATOR=ON"
            cppFlags += "-std=c++17"
        } }
    }
    externalNativeBuild { cmake { path = file("../inference/CMakeLists.txt"); version = "3.31.6" } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("../model-manager/src/main/kotlin")
    sourceSets["main"].assets.srcDirs("../model-manager/manifests", "../docs/notices")
    if (bundledModel != null) sourceSets["main"].assets.srcDir(bundledAssets)
    androidResources { noCompress += "gguf" }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { if (emulatorBuild) { applicationIdSuffix = ".emulator"; versionNameSuffix = "-emulator" } }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
if (prepareBundledModel != null) tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareBundledModel) }
androidComponents { beforeVariants(selector().withBuildType("release")) { if (emulatorBuild) it.enable = false } }
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
