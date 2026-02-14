plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.10.5"
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
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        name = "Unified LLM Agents"
        description = "Unified LLM AI Agents plugin."
        vendor {
            name = "E"
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    val npmBuild by registering(Exec::class) {
        workingDir = file("frontend")
        // Use npm.cmd on Windows so Gradle can find npm; on Linux/macOS use "npm"
        val npm = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"
        commandLine(npm, "run", "build")
    }

    processResources {
        dependsOn(npmBuild)
    }
}
