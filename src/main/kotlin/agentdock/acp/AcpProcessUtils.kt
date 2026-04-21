package agentdock.acp

import java.io.File

/**
 * Utility functions for managing OS processes related to ACP adapters.
 */
internal object AcpProcessUtils {

    fun stopProcessesUsingAdapterRoot(adapterName: String, target: AcpExecutionTarget = AcpAdapterPaths.getExecutionTarget()) {
        val adapterRoot = runCatching {
            File(AcpAdapterPaths.getDownloadPath(adapterName, target))
        }.getOrNull() ?: return

        val normalizedRoot = adapterRoot.absoluteFile.normalize().path.replace('\\', '/').lowercase().trimEnd('/')
        if (normalizedRoot.isBlank()) return

        ProcessHandle.allProcesses().forEach { handle ->
            if (processBelongsToAdapterRoot(handle, normalizedRoot)) {
                destroyProcessTree(handle)
            }
        }
    }

    fun destroyProcessTree(handle: ProcessHandle) {
        val descendants = runCatching { handle.descendants().toList() }.getOrElse { emptyList() }
        descendants.forEach { child ->
            try {
                child.destroyForcibly()
                child.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
        try {
            handle.destroyForcibly()
            handle.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun processBelongsToAdapterRoot(handle: ProcessHandle, normalizedRoot: String): Boolean {
        val info = try {
            handle.info()
        } catch (_: Exception) {
            return false
        }

        val command = try {
            info.command().orElse(null)
        } catch (_: Exception) {
            null
        }
        val cmdPath = if (command != null) normalizeProcessPath(command) else null
        if (cmdPath != null && (cmdPath == normalizedRoot || cmdPath.startsWith("$normalizedRoot/"))) {
            return true
        }

        val arguments = try {
            info.arguments().orElse(null)
        } catch (_: Exception) {
            null
        }
        return arguments?.any { arg ->
            val argPath = normalizeProcessPath(arg)
            argPath != null && (argPath == normalizedRoot || argPath.startsWith("$normalizedRoot/"))
        } == true
    }

    private fun normalizeProcessPath(path: String): String? {
        val trimmed = path.trim().trim('"')
        if (trimmed.isEmpty()) return null
        return try {
            File(trimmed).absoluteFile.normalize().path.replace('\\', '/').lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
