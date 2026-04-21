plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.1")
        jetbrainsRuntime()
    }
    implementation("com.agentclientprotocol:acp:0.18.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/buildConfig"))
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        name = "AgentDock"
        description = "Provides widely used AI coding agents such as Codex, Claude Code, Copilot, and others with a rich GUI that follows your JetBrains IDE theme."
        vendor {
            name = "E"
        }
    }
}

val devMode = providers.gradleProperty("devMode").map { it.toBoolean() }.getOrElse(false)

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildConfig")
    val isDev = devMode
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("agentdock/BuildConfig.kt")
        file.parentFile.mkdirs()
        file.writeText("package agentdock\n\ninternal object BuildConfig {\n    const val IS_DEV: Boolean = $isDev\n}\n")
    }
}

tasks {
    val npm = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"

    val npmBuild by registering(Exec::class) {
        workingDir = file("frontend")
        commandLine(npm, "run", "build")
    }

    compileKotlin {
        dependsOn(generateBuildConfig)
    }

    processResources {
        dependsOn(npmBuild)
    }
}
