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

// Niente foojay-resolver: lo scanner F-Droid lo blocca (scarica JDK da un servizio
// remoto a build-time). I moduli fissano il jvmTarget 17 esplicito e usano il JDK
// già presente (F-Droid builda con JDK 17).

rootProject.name = "argus"
include("engine-core", "ui", "app", "data")
include("brain-android")
include("automation-android")
include("core-shizuku")
include("device-tools")
