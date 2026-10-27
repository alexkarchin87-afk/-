import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // namespace уникален: BuildConfig модуля = com.voiceagent.oneplus13.core.aiengines.BuildConfig
    // Kotlin-пакет файлов остаётся com.voiceagent.oneplus13.core — импорты в :app не меняются.
    namespace = "com.voiceagent.oneplus13.core.aiengines"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField(
            "String", "DEEPSEEK_API_KEY",
            "\"${localProps.getProperty("DEEPSEEK_API_KEY", "")}\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    // Публичные сигнатуры: Flow (STTEngine/TTSEngine) и org.vosk.Model (STTEngine.ensureModel)
    api(libs.kotlinx.coroutines.android)
    api(libs.vosk.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto) // SecretsStore
    implementation(libs.okhttp)                   // LLMClient
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
