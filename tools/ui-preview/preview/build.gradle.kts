plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}
android {
    namespace = "dev.gdalabs.uipreview"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].java.srcDir("../../../android-app/src/main/java/dev/gdalabs/localui")
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.commonmark:commonmark:0.31.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.31.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.31.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.paparazzi:paparazzi:2.0.0-alpha04")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<Test>().configureEach { maxHeapSize = "2g"; reports.html.required.set(false) }
