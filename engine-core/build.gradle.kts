plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Pattern controllati dall'utente/LLM su testo non fidato: RE2/J garantisce matching lineare.
    implementation("com.google.re2j:re2j:1.8")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test { useJUnitPlatform() }
// jvmTarget 17 esplicito invece di jvmToolchain(17): quest'ultimo tira il
// foojay-resolver (download JDK remoto) che lo scanner F-Droid rifiuta.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
sourceSets {
    test {
        // Stessa fixture consumata dal validator Python del bridge: nessuna copia divergente.
        resources {
            srcDir(rootProject.file("ops/hermes"))
            include("state_query_contract_v2.json")
        }
    }
}