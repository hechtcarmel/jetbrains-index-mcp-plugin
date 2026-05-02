import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.kotlinSerialization) // Kotlin Serialization Plugin
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)

    // Include Rider protocol-generated sources when available
    sourceSets {
        main {
            kotlin.srcDir("src/rider/main/kotlin")
        }
    }
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    // MCP Kotlin SDK - exclude kotlinx-coroutines to use IntelliJ Platform's bundled version
    implementation(libs.mcp.kotlin.sdk) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
        exclude(group = "org.slf4j")
    }

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jtoon)

    // Ktor Server (for custom MCP server with configurable port)
    implementation(libs.ktor.server.core) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.slf4j")
    }
    implementation(libs.ktor.server.cio) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.slf4j")
    }
    implementation(libs.ktor.server.cors) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.slf4j")
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    testImplementation(libs.mockk) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        pluginVerifier()
        
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)

    }
}

// Expose rider-model.jar for the protocol module's rdgen code generation.
// This configuration is consumed by :protocol to get the rd model base classes.
val riderModel: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

afterEvaluate {
    val platformPath = intellijPlatform.platformPath
    val riderModelJar = platformPath.resolve("lib/rider-model.jar")
    if (riderModelJar.toFile().exists()) {
        artifacts.add(riderModel.name, riderModelJar.toFile())
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion")
            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
            // Keep the explicitly supported compatibility range under verifier coverage.
            create("IU", "2025.3")
            // CI must use a published IDE release version, not a raw build number.
            // 2026.1 resolves correctly in JetBrains repositories and still keeps 261 as the baseline.
            create("IU", "2026.1")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

//    runIde {
//        jvmArgs("-Xmx20g", "-Xms1g")
//    }


}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// ── Rider / .NET Backend Build Integration ──────────────────────────────────

val dotNetSolutionDir = file("src/dotnet")
val dotNetSolution = dotNetSolutionDir.resolve("ReSharperPlugin.IndexMcp.sln")
val dotNetConfiguration = "Release"
val dotNetOutputDir = dotNetSolutionDir.resolve("ReSharperPlugin.IndexMcp/bin/$dotNetConfiguration")

val compileDotNet by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the ReSharper backend .NET plugin"
    onlyIf { dotNetSolution.exists() }
    workingDir = dotNetSolutionDir
    commandLine("dotnet", "build", dotNetSolution.name, "-c", dotNetConfiguration, "/p:HostFullIdentifier=")
    notCompatibleWithConfigurationCache("Exec tasks are not configuration cache compatible")
}

val testDotNet by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run ReSharper backend .NET tests"
    dependsOn(compileDotNet)
    onlyIf { dotNetSolution.exists() }
    workingDir = dotNetSolutionDir
    commandLine("dotnet", "test", dotNetSolution.name, "-c", dotNetConfiguration, "--no-build")
    notCompatibleWithConfigurationCache("Exec tasks are not configuration cache compatible")
}

// Copy .NET backend DLLs into the plugin sandbox so Rider can load them
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>(Constants.Tasks.PREPARE_SANDBOX) {
    dependsOn(compileDotNet)
    val pluginName = providers.gradleProperty("pluginName").get()
    from(dotNetOutputDir) {
        include("*.dll")
        include("*.pdb")
        into("$pluginName/dotnet")
    }
}
