import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// S16: firma release da keystore.properties LOCALE (mai committato; vedi .gitignore).
// Senza il file la build release resta possibile ma non firmata (CI/altre macchine).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.argus"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.argus"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            // R8 spento al primo giro (S16): meno variabili per l'E2E dei tester; si accende
            // in un secondo momento con le keep-rules per serialization/Hilt/Room.
            isMinifyEnabled = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":automation-android"))
    implementation(project(":ui"))
    implementation(project(":engine-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Icone bottom-nav §9 (chat_bubble/bolt/history/tune) vivono in material-icons-extended:
    // `:ui` la dichiara `implementation` (non transitiva), quindi l'app la ridichiara.
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    // S16: i moduli runtime servono in TUTTI i buildType (prima erano debugImplementation e la
    // release nasceva senza brain/data/shizuku/device-tools). L'entry point Hilt degli E2E resta
    // esposto solo dal source set debug.
    implementation(project(":brain-android"))
    implementation(project(":core-shizuku"))
    implementation(project(":data"))
    implementation(project(":device-tools"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(project(":brain-android"))
    androidTestImplementation(project(":core-shizuku"))
    androidTestImplementation(project(":data"))
    androidTestImplementation(project(":device-tools"))
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
