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
        versionCode = 8
        versionName = "0.3.1"
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
            // Reproducible build per F-Droid: il resource shrinking rinominava risorse in modo
            // non deterministico (mismatch byte-per-byte col buildserver F-Droid).
            isShrinkResources = false
            // Niente git SHA in META-INF/version-control-info.textproto: F-Droid ricompila da un
            // clone pulito al tag e il file conterrebbe un hash diverso -> repro fallisce.
            vcsInfo { include = false }
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    // AGP inietta un blocco "Dependency metadata" (0x504b4453) nell'APK Signing Block: F-Droid
    // lo rifiuta al job `check apk`. Disabilitarlo alla sorgente evita lo strip manuale.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // ABI split (richiesta maintainer linsui, MR fdroiddata!43234): l'APK universale imbarcava
    // libandroidx.graphics.path.so per tutte e 4 le ABI. Produciamo un APK per ABI, niente
    // universale, così ogni device scarica solo la propria architettura. Il versionCode per-output
    // e assegnato sotto (androidComponents) e deve combaciare con VercodeOperation nella recipe.
    // F-Droid compila ogni ABI come build block separato e passa -PargusAbi=<abi>: in quel caso
    // gradle emette solo quello split (build byte-identico allo split corrispondente della release).
    // Senza la property una build locale emette tutti e quattro gli APK.
    val argusAbi = (project.findProperty("argusAbi") as String?)?.takeIf { it.isNotBlank() }
    splits {
        abi {
            isEnable = true
            reset()
            if (argusAbi != null) {
                include(argusAbi)
            } else {
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            isUniversalApk = false
        }
    }
}

// versionCode distinto e monotono per ABI, come da guida F-Droid (VercodeOperation 100*%c+n):
// armeabi-v7a=+1 < arm64-v8a=+2 < x86=+3 < x86_64=+4. Deve restare allineato alla recipe fdroiddata.
androidComponents {
    onVariants { variant ->
        val abiVersionCodes = mapOf(
            "armeabi-v7a" to 1,
            "arm64-v8a" to 2,
            "x86" to 3,
            "x86_64" to 4,
        )
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier ?: return@forEach
            val base = output.versionCode.get() ?: 0
            output.versionCode.set(base * 100 + abiVersionCodes.getValue(abi))
        }
    }
}

// Riproducibilita F-Droid: la ricompilazione pulita del buildserver F-Droid produceva un
// assets/dexopt/baseline.prof diverso dal nostro, e D8 usa il profilo di startup per ordinare
// classes2.dex (che divergeva a cascata; classes.dex, profile-independent, gia combaciava).
// Il baseline profile compilato dai profili delle librerie AndroidX non e byte-stabile tra host
// di build diversi. Disabilitiamo i task ART/startup profile per la release: nessun baseline.prof
// impacchettato e layout dex in ordine sorgente -> deterministico tra host. (Perde solo
// l'ottimizzazione di avvio guidata dal profilo, accettabile per la distribuzione F-Droid.)
tasks.configureEach {
    if (name == "compileReleaseArtProfile" ||
        name == "mergeReleaseArtProfile" ||
        name == "mergeReleaseStartupProfile") {
        enabled = false
    }
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
