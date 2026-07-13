plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.argus"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.argus"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-demo"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { getByName("debug") { isDebuggable = true } }
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
    // Il solo APK debug espone l'entry point Hilt usato dagli E2E di produzione.
    debugImplementation(project(":brain-android"))
    debugImplementation(project(":core-shizuku"))
    debugImplementation(project(":data"))
    debugImplementation(project(":device-tools"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(project(":brain-android"))
    androidTestImplementation(project(":core-shizuku"))
    androidTestImplementation(project(":data"))
    androidTestImplementation(project(":device-tools"))
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
