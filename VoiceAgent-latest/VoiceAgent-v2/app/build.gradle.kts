import java.util.Properties
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Стабильный ключ подписи. Без него CI подписывает APK случайным debug-ключом, который меняется
// при каждой сборке: Android отказывается ОБНОВЛЯТЬ приложение (приходится удалять — вместе с
// данными и входом), а Google-вход не настроить, потому что SHA-1 каждый раз новый.
// Значения берутся из переменных окружения (CI) или local.properties (локально).
fun signingProp(name: String): String? =
    (System.getenv(name) ?: localProps.getProperty(name))?.takeIf { it.isNotBlank() }

val stableKeystore = signingProp("VA_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.voiceagent.oneplus13"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceagent.oneplus13"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0-overhaven"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${localProps.getProperty("DEEPSEEK_API_KEY", "")}\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${localProps.getProperty("TELEGRAM_BOT_TOKEN", "")}\"")
        buildConfigField("String", "MAX_SERVICE_TOKEN", "\"${localProps.getProperty("MAX_SERVICE_TOKEN", "")}\"")
        buildConfigField("String", "MAX_COMMUNITY_ID", "\"${localProps.getProperty("MAX_COMMUNITY_ID", "")}\"")
    }

    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = signingProp("VA_KEYSTORE_PASSWORD")
                keyAlias = signingProp("VA_KEY_ALIAS")
                keyPassword = signingProp("VA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            // R8 выключен: Moshi (рефлексия), Vosk/JNA и Compose не проверялись под минификацией,
            // а рабочий APK важнее размера. Включить можно, когда появятся keep-правила под сборку.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Личное использование: release подписывается debug-ключом, чтобы APK ставился без keystore.
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // ИИ-движки (LLMClient, STTEngine, TTSEngine, SecretsStore) — отдельный модуль.
    // Kotlin-пакет прежний (com.voiceagent.oneplus13.core), импорты не менялись.
    implementation(project(":core:ai-engines"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Нужны системному оверлею (overlay/OverlayLifecycleOwner.kt) — Compose внутри
    // окна WindowManager, добавленного из Service, требует свой Lifecycle/
    // ViewModelStore/SavedStateRegistry вместо тех, что бесплатно даёт Activity.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Нужен, чтобы MainScreen мог отличить телефон от планшета/ПК (адаптивный UI)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    // Icons.Filled.Settings / Icons.Filled.Send, используемые в новом UI
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)
    implementation(libs.play.services.auth)

    // Юнит-тесты на JVM: org.json из Android-заглушки не работает, нужна настоящая реализация.
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(libs.org.json)
}

// Vosk StorageService.unpack() читает assets/<модель>/uuid; без этого файла распаковка модели падает.
// Если папка с моделью есть, а uuid нет — создаём его перед сборкой.
val genVoskUuid by tasks.registering {
    val modelDir = layout.projectDirectory.dir("src/main/assets/model-ru-small").asFile
    doLast {
        val uuidFile = File(modelDir, "uuid")
        if (modelDir.isDirectory && !uuidFile.exists()) uuidFile.writeText(UUID.randomUUID().toString())
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(genVoskUuid) }

// Compile/unit tests remain available without model assets, but do not ship a voice APK without them.
val verifyVoskModel by tasks.registering {
    dependsOn(genVoskUuid)
    doLast {
        val dir = layout.projectDirectory.dir("src/main/assets/model-ru-small").asFile
        fun present(name: String) = File(dir, name).let { it.isFile && it.length() > 0 }
        val missing = listOf("uuid", "am/final.mdl", "conf/mfcc.conf").filterNot(::present).toMutableList()
        if (!present("graph/HCLG.fst") && !(present("graph/HCLr.fst") && present("graph/Gr.fst"))) missing += "graph/FST"
        check(missing.isEmpty()) {
            "Vosk model missing/incomplete in app/src/main/assets/model-ru-small: ${missing.joinToString()}. Run sh scripts/download_vosk_model.sh, then rebuild."
        }
    }
}
tasks.matching { it.name in setOf("packageDebug", "packageRelease") }.configureEach { dependsOn(verifyVoskModel) }
