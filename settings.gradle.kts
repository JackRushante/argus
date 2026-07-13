plugins {
    // Provisioning automatico dei JDK toolchain (jvmToolchain(17) nei moduli)
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "argus"

include("engine-core")
