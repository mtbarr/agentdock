package agentdock.acp

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object AcpNpmInstaller {
    internal fun downloadFromNpm(
        targetDir: File,
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        statusCallback: ((String) -> Unit)? = null,
        cancellation: AcpAdapterInstallCancellation? = null
    ): Boolean {
        return try {
            cancellation?.throwIfCancelled()
            if (!requireLocalCommand("node", "Node.js is required", statusCallback, cancellation)) return false
            if (!requireLocalCommand("npm", "Node.js is required", statusCallback, cancellation)) return false

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
            cancellation?.register(installProc)

            val recentOutput = java.util.Collections.synchronizedList(mutableListOf<String>())
            val outputDrainer = Thread {
                installProc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) {
                            recentOutput.add(trimmed)
                            if (recentOutput.size > 12) recentOutput.removeAt(0)
                        }
                        if (trimmed.contains("added", ignoreCase = true)
                            || line.contains("tarball", ignoreCase = true)
                            || line.contains("install", ignoreCase = true)
                        ) {
                            statusCallback?.invoke("NPM: $trimmed")
                        }
                    }
                }
            }
            outputDrainer.isDaemon = true
            outputDrainer.start()

            try {
                while (true) {
                    cancellation?.throwIfCancelled()
                    if (installProc.waitFor(250, TimeUnit.MILLISECONDS)) break
                }
            } catch (e: CancellationException) {
                installProc.destroyForcibly()
                outputDrainer.join(1000)
                throw e
            } finally {
                cancellation?.unregister(installProc)
            }

            cancellation?.throwIfCancelled()
            outputDrainer.join(1000)
            val exitCode = installProc.exitValue()
            if (exitCode == 0) {
                true
            } else {
                val detail = recentOutput.joinToString("\n").ifBlank { "npm install failed" }
                statusCallback?.invoke("Error: npm install failed with exit code $exitCode\n$detail")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusCallback?.invoke("Error: ${e.message ?: "npm install failed"}")
            false
        }
    }

    private fun requireLocalCommand(
        commandName: String,
        errorMessage: String,
        statusCallback: ((String) -> Unit)? = null,
        cancellation: AcpAdapterInstallCancellation? = null
    ): Boolean {
        val executable = when {
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("npm", ignoreCase = true) -> "npm.cmd"
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("npx", ignoreCase = true) -> "npx.cmd"
            System.getProperty("os.name").lowercase().contains("win") && commandName.equals("node", ignoreCase = true) -> "node.exe"
            else -> commandName
        }
        return try {
            cancellation?.throwIfCancelled()
            val process = ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start()
            cancellation?.register(process)
            try {
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (true) {
                    cancellation?.throwIfCancelled()
                    if (process.waitFor(250, TimeUnit.MILLISECONDS)) break
                    if (System.nanoTime() >= deadlineNanos) {
                        process.destroyForcibly()
                        statusCallback?.invoke("Error: $errorMessage")
                        return false
                    }
                }
            } finally {
                cancellation?.unregister(process)
            }
            cancellation?.throwIfCancelled()
            if (process.exitValue() == 0) {
                true
            } else {
                statusCallback?.invoke("Error: $errorMessage")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            statusCallback?.invoke("Error: $errorMessage")
            false
        }
    }

}
