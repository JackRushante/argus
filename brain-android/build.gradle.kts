plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.argus.brain"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Compose OFF: modulo di trasporto HTTP puro, nessuna UI.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":engine-core"))

    // Bridge Hermes via OkHttp (dep diretta: evito di toccare il catalog condiviso mid-flight).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // engine-core dichiara serialization/coroutines come `implementation` (non transitivi): li ridichiaro.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Unit test puri JVM su OkHttp MockWebServer (nessun device/Robolectric necessario).
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// I test (HermesBrainTest) girano su JUnit5, come engine-core.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
