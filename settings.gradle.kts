pluginManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
        maven("https://cache-redirector.jetbrains.com/maven-central")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            // rdgen plugin ID doesn't follow Gradle's default convention
            if (requested.id.id == "com.jetbrains.rdgen") {
                useModule("com.jetbrains.rd:rd-gen:${requested.version}")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jetbrains-index-mcp-plugin"

// Protocol module is only included when explicitly building with Rider SDK.
// It requires rider-model.jar which is not available in IC (IntelliJ Community) builds.
// To generate protocol stubs: ./gradlew :protocol:rdgen (requires Rider SDK)
if (System.getenv("INCLUDE_PROTOCOL_MODULE") != null) {
    include(":protocol")
}
