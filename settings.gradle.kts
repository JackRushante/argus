pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // PREFER_PROJECT (invece del PREFER_SETTINGS del brief): engine-core dichiara il proprio
    // repositories { mavenCentral() }; con PREFER_SETTINGS Gradle emetterebbe un warning e lo
    // ignorerebbe. Con PREFER_PROJECT engine-core continua a usare i suoi repo senza warning,
    // mentre ui/app (che non dichiarano repository) ereditano quelli di settings qui sotto.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories { google(); mavenCentral() }
}

plugins {
    // Provisioning automatico dei JDK toolchain (jvmToolchain(17) in engine-core)
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "argus"
include("engine-core", "ui", "app", "data")
include("brain-android")
