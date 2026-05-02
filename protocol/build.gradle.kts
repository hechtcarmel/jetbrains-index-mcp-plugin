import com.jetbrains.rd.generator.gradle.RdGenTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.jetbrains.rdgen") version libs.versions.rdGen
}

dependencies {
    implementation(libs.kotlinStdLib)
    implementation(libs.rdGen)
    implementation(
        project(
            mapOf(
                "path" to ":",
                "configuration" to "riderModel"
            )
        )
    )
}

val explicitRiderModelJarPath = providers.gradleProperty("riderModelJar")
    .orElse(providers.environmentVariable("RIDER_MODEL_JAR"))
    .orElse("")
val riderSdkPath = providers.gradleProperty("riderSdkPath")
    .orElse(providers.environmentVariable("RIDER_HOME"))
    .orElse("")

val verifyRiderModelJar by tasks.registering {
    group = "verification"
    description = "Verify rider-model.jar is available for Rider protocol generation"
    notCompatibleWithConfigurationCache("Verifies local Rider SDK paths at execution time")
    inputs.property("riderModelJarPath", explicitRiderModelJarPath)
    inputs.property("riderSdkPath", riderSdkPath)
    doLast {
        val explicitRiderModelJar = explicitRiderModelJarPath.get()
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
        val riderHomeModelJar = riderSdkPath.get()
            .takeIf { it.isNotBlank() }
            ?.let { File(it).resolve("lib/rider-model.jar") }
        val candidates = listOfNotNull(explicitRiderModelJar, riderHomeModelJar)
        if (candidates.none { it.isFile }) {
            throw GradleException(
                "Rider protocol generation requires rider-model.jar. " +
                    "Set RIDER_MODEL_JAR or -PriderModelJar to the jar path, " +
                    "or set RIDER_HOME or -PriderSdkPath to a Rider installation directory."
            )
        }
    }
}

val dotnetPluginId = "ReSharperPlugin.IndexMcp"
val riderPluginId = "indexmcp"

rdgen {
    val csOutput = File(rootDir, "src/dotnet/${dotnetPluginId}")
    val ktOutput = File(rootDir, "src/rider/main/kotlin/com/jetbrains/rider/plugins/${riderPluginId}")

    verbose = true
    packages = "model.rider"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "com.jetbrains.rider.model"
        directory = "$ktOutput"
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "JetBrains.Rider.Model"
        directory = "$csOutput"
    }
}

tasks.withType<RdGenTask> {
    val classPath = sourceSets["main"].runtimeClasspath
    dependsOn(verifyRiderModelJar, classPath)
    classpath(classPath)
}
