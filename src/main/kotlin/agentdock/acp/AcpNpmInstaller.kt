package agentdock.acp

import java.io.File
import java.util.concurrent.TimeUnit

object AcpNpmInstaller {
    private const val ARCHIVE_COMMAND_TIMEOUT_MINUTES = 10L

    fun downloadFromNpm(targetDir: File, adapterInfo: AcpAdapterConfig.AdapterInfo, statusCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            if (!requireLocalCommand("node", "Node.js is required", statusCallback)) return false
            if (!requireLocalCommand("npm", "Node.js is required", statusCallback)) return false

            val packageName = adapterInfo.distribution.packageName
                ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
            val version = adapterInfo.distribution.version

            statusCallback?.invoke("Installing $packageName@$version via npm...")
            File(targetDir, "package.json").writeText("""{"name":"${adapterInfo.id}-runtime","private":true}""")

            val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
            val installProc = ProcessBuilder(npm, "install", "$packageName@$version", "--no-save", "--no-package-lock")
                .directory(targetDir)
                .redirectErrorStream(true)
                .start()

            val recentOutput = ArrayDeque<String>()
            installProc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank()) {
                        recentOutput.addLast(trimmed)
                        if (recentOutput.size > 12) recentOutput.removeFirst()
                    }
                    if (trimmed.contains("added", ignoreCase = true)
                        || line.contains("tarball", ignoreCase = true)
                        || line.contains("install", ignoreCase = true)
                    ) {
                        statusCallback?.invoke("NPM: $trimmed")
                    }
                }
            }

            val exitCode = installProc.waitFor()
            if (exitCode == 0) {
                true
            } else {
                val detail = recentOutput.joinToString("\n").ifBlank { "npm install failed" }
                statusCallback?.invoke("Error: npm install failed with exit code $exitCode\n$detail")
                false
            }
        } catch (e: Exception) {
            statusCallback?.invoke("Error: ${e.message ?: "npm install failed"}")
            false
        }
    }

    fun downloadFromNpmWsl(
        targetDir: String,
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        statusCallback: ((String) -> Unit)? = null
    ): Boolean {
        if (!requireWslCommand("node --version", "Linux Node.js runtime is required in WSL", statusCallback)) return false
        if (!requireWslCommand("npm --version", "Linux npm is required in WSL", statusCallback)) return false

        val packageName = adapterInfo.distribution.packageName
            ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
        val version = adapterInfo.distribution.version
        statusCallback?.invoke("Installing $packageName@$version via npm in WSL...")
        val script = """
            set -e
            rm -rf ${quoteUnixShellArg(targetDir)}
            mkdir -p ${quoteUnixShellArg(targetDir)}
            cd ${quoteUnixShellArg(targetDir)}
            cat > package.json <<EOF
            {"name":"${adapterInfo.id}-runtime","private":true}
            EOF
            npm install ${quoteUnixShellArg("$packageName@$version")} --no-save --no-package-lock
        """.trimIndent()
        val result = AcpExecutionMode.runWslShell(script, timeoutSeconds = ARCHIVE_COMMAND_TIMEOUT_MINUTES * 60)
        if (result?.exitCode != 0) {
            statusCallback?.invoke("Error: ${result?.stderr?.trim().orEmpty().ifBlank { result?.stdout?.trim().orEmpty().ifBlank { "npm install failed in WSL" } }}")
            return false
        }
        return true
    }

    private fun requireLocalCommand(commandName: String, errorMessage: String, statusCallback: ((String) -> Unit)? = null): Boolean {
        val executable = when {
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("npm", ignoreCase = true) -> "npm.cmd"
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("npx", ignoreCase = true) -> "npx.cmd"
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("node", ignoreCase = true) -> "node.exe"
            else -> commandName
        }
        return try {
            val process = ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                statusCallback?.invoke("Error: $errorMessage")
                return false
            }
            if (process.exitValue() == 0) {
                true
            } else {
                statusCallback?.invoke("Error: $errorMessage")
                false
            }
        } catch (_: Exception) {
            statusCallback?.invoke("Error: $errorMessage")
            false
        }
    }

    private fun requireWslCommand(validationCommand: String, errorMessage: String, statusCallback: ((String) -> Unit)? = null): Boolean {
        val result = AcpExecutionMode.runWslShell(validationCommand, timeoutSeconds = 15)
        if (result?.exitCode == 0) return true
        val detail = result?.stderr?.trim().orEmpty().ifBlank { result?.stdout?.trim().orEmpty() }
        statusCallback?.invoke(if (detail.isBlank()) "Error: $errorMessage" else "Error: $errorMessage. $detail")
        return false
    }

    private fun quoteUnixShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}
