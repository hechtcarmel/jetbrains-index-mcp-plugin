import com.jetbrains.rd.generator.gradle.RdGenTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.jetbrains.rdgen") version libs.versions.rdGen
}

repositories {
    maven("https://cache-redirector.jetbrains.com/maven-central")
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-repository/releases")
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

val verifyRiderModelJar by tasks.registering {
    group = "verification"
    description = "Verify rider-model.jar is available for Rider protocol generation"
    val protocolClasspath = sourceSets["main"].runtimeClasspath
    inputs.files(protocolClasspath)
    doLast {
        if (protocolClasspath.files.none { it.name == "rider-model.jar" && it.isFile }) {
            throw GradleException(
                "Rider protocol generation requires rider-model.jar. " +
                    "Set RIDER_MODEL_JAR or -PriderModelJar to the jar path, " +
                    "or set RIDER_HOME or -PriderSdkPath to a Rider installation directory " +
                    "containing lib/rd/rider-model.jar, or set -PriderProtocolVersion to " +
                    "a published Rider RD artifact version."
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
    notCompatibleWithConfigurationCache("rdgen accesses Gradle project state during execution")
    val classPath = sourceSets["main"].runtimeClasspath
    dependsOn(verifyRiderModelJar, classPath)
    classpath(classPath)
}

val patchGeneratedKotlinProtocol by tasks.registering {
    group = "build"
    description = "Patch rdgen Kotlin output for the multi-IDE frontend build"
    val generatedFile = File(
        rootDir,
        "src/rider/main/kotlin/com/jetbrains/rider/plugins/${riderPluginId}/IndexMcpModel.Generated.kt"
    )
    inputs.file(generatedFile).optional()
    outputs.file(generatedFile)
    doLast {
        if (generatedFile.isFile) {
            val before = generatedFile.readText()
            val after = before.replace(
                "            com.jetbrains.rd.ide.model.IdeRoot.register(protocol.serializers)\r\n            \r\n",
                ""
            ).replace(
                "            com.jetbrains.rd.ide.model.IdeRoot.register(protocol.serializers)\n            \n",
                ""
            )
            if (after != before) {
                generatedFile.writeText(after)
            }
        }
    }
}

tasks.withType<RdGenTask> {
    finalizedBy(patchGeneratedKotlinProtocol)
}
