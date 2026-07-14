plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.argus.data"
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
    // Compose OFF: questo modulo è persistenza pura, nessuna UI.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric ha bisogno del merged manifest/resources
    }
    // Lo schema Room esportato (schemas/) viene impacchettato negli asset androidTest così che
    // MigrationTestHelper possa validarlo su device (T3 Step 4, scaffolding migrazioni future).
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    // Esporta lo schema JSON versionato per i test di migrazione.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine-core"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)      // supporto suspend (query off-main-thread gestito da Room)
    ksp(libs.androidx.room.compiler)

    // engine-core dichiara serialization/coroutines come `implementation` (non transitivi): li ridichiaro.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Robolectric gira con JUnit4 (@RunWith(RobolectricTestRunner)); evita l'attrito JUnit5-Robolectric.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
