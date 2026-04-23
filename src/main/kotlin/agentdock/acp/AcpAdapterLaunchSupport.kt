package agentdock.acp

import java.io.File

private const val DEFAULT_NPM_LAUNCH_PATH = "dist/index.js"

internal fun isWindowsLocalTarget(target: AcpExecutionTarget): Boolean =
    target == AcpExecutionTarget.LOCAL && AcpExecutionMode.isWindowsHost()

internal fun platformBinaryForTarget(
    binary: AcpAdapterConfig.PlatformBinary?,
    target: AcpExecutionTarget
): String? {
    return if (isWindowsLocalTarget(target)) binary?.win else binary?.unix
}

internal fun resolveTargetDependenciesPath(
    target: AcpExecutionTarget,
    wslHomeDirOverride: String? = null
): String? {
    return when (target) {
        AcpExecutionTarget.LOCAL -> AcpAdapterPaths.getDependenciesDir().absolutePath
        AcpExecutionTarget.WSL -> {
            val base = wslAdapterBaseRuntimeDir(wslHomeDirOverride) ?: return null
            "$base/dependencies"
        }
    }
}

internal fun resolveDownloadPath(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    target: AcpExecutionTarget,
    wslHomeDirOverride: String? = null
): String {
    return when (target) {
        AcpExecutionTarget.LOCAL -> File(AcpAdapterPaths.getDependenciesDir(), adapterInfo.id).absolutePath
        AcpExecutionTarget.WSL -> {
            val depsPath = resolveTargetDependenciesPath(target, wslHomeDirOverride)
                ?: throw IllegalStateException("Unable to resolve WSL dependencies path for adapter '${adapterInfo.id}'")
            "$depsPath/${adapterInfo.id}"
        }
    }
}

internal fun resolveAdapterLaunchFile(
    adapterRoot: File,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    target: AcpExecutionTarget
): File? {
    if (target == AcpExecutionTarget.WSL) return null
    return when (adapterInfo.distribution.type) {
        AcpAdapterConfig.DistributionType.ARCHIVE -> {
            val binName = platformBinaryForTarget(adapterInfo.distribution.binaryName, target)
            if (binName.isNullOrBlank()) null else File(adapterRoot, binName)
        }
        AcpAdapterConfig.DistributionType.NPM -> {
            val launchPath = resolveNpmLaunchRelativePath(adapterInfo, target)
            File(adapterRoot, launchPath.replace("/", File.separator).replace("\\", File.separator))
        }
    }
}

internal fun resolveAdapterLaunchPath(
    adapterRootPath: String,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    target: AcpExecutionTarget
): String? {
    return when (adapterInfo.distribution.type) {
        AcpAdapterConfig.DistributionType.ARCHIVE -> {
            val binName = platformBinaryForTarget(adapterInfo.distribution.binaryName, target)
            binName?.takeIf { it.isNotBlank() }?.let { joinAdapterPath(adapterRootPath, it, target) }
        }
        AcpAdapterConfig.DistributionType.NPM -> {
            val launchPath = resolveNpmLaunchRelativePath(adapterInfo, target)
            joinAdapterPath(adapterRootPath, launchPath, target)
        }
    }
}

internal fun buildAdapterLaunchCommand(
    adapterRootPath: String,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    projectPath: String?,
    target: AcpExecutionTarget
): List<String> {
    val launchPath = resolveAdapterLaunchPath(adapterRootPath, adapterInfo, target)
        ?: throw IllegalStateException("Missing launch target for adapter '${adapterInfo.id}'")
    return when (target) {
        AcpExecutionTarget.LOCAL -> {
            val launchFile = File(launchPath)
            val name = launchFile.name.lowercase()
            val base = when {
                name.endsWith(".cmd") || name.endsWith(".bat") -> mutableListOf("cmd.exe", "/c", launchFile.absolutePath)
                name.endsWith(".ps1") -> mutableListOf(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launchFile.absolutePath
                )
                name.endsWith(".js") || name.endsWith(".mjs") -> {
                    mutableListOf(if (AcpExecutionMode.isWindowsHost()) "node.exe" else "node", launchFile.absolutePath)
                }
                else -> mutableListOf(launchFile.absolutePath)
            }
            base.addAll(adapterInfo.args)
            base
        }
        AcpExecutionTarget.WSL -> {
            val base = when {
                launchPath.endsWith(".js") || launchPath.endsWith(".mjs") -> mutableListOf("node", launchPath)
                else -> mutableListOf(launchPath)
            }
            base.addAll(adapterInfo.args)
            AcpExecutionMode.buildWslExecCommand(base, AcpExecutionMode.toWslPath(projectPath) ?: adapterRootPath)
        }
    }
}

internal fun resolvePatchRoot(adapterRoot: File, adapterInfo: AcpAdapterConfig.AdapterInfo): File {
    return when (adapterInfo.distribution.type) {
        AcpAdapterConfig.DistributionType.ARCHIVE -> adapterRoot
        AcpAdapterConfig.DistributionType.NPM -> resolveNpmPackageRoot(adapterRoot, adapterInfo)
    }
}

internal fun wslPathToWindowsFile(wslPath: String, distroNameOverride: String? = null): File? {
    val uncPath = AcpExecutionMode.wslPathToWindowsUnc(wslPath, distroNameOverride) ?: return null
    return File(uncPath)
}

private fun resolveNpmPackageRoot(adapterRoot: File, adapterInfo: AcpAdapterConfig.AdapterInfo): File {
    val packageName = adapterInfo.distribution.packageName
        ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
    return File(adapterRoot, "node_modules${File.separator}$packageName")
}

internal fun resolveNpmPackageRootPath(
    adapterRootPath: String,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    target: AcpExecutionTarget
): String {
    val packageName = adapterInfo.distribution.packageName
        ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
    return joinAdapterPath(adapterRootPath, "node_modules/$packageName", target)
}

private fun resolveNpmLaunchRelativePath(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    target: AcpExecutionTarget
): String {
    val launchBinary = platformBinaryForTarget(adapterInfo.launchBinary, target).orEmpty().trim()
    if (launchBinary.isNotEmpty()) return launchBinary

    val launchPath = adapterInfo.launchPath.ifBlank { DEFAULT_NPM_LAUNCH_PATH }
    val packageName = adapterInfo.distribution.packageName
        ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
    return "node_modules/$packageName/$launchPath"
}

private fun joinAdapterPath(base: String, relative: String, target: AcpExecutionTarget): String {
    val separator = if (target == AcpExecutionTarget.WSL) "/" else File.separator
    val normalizedRelative = if (target == AcpExecutionTarget.WSL) {
        relative.replace("\\", "/")
    } else {
        relative.replace("/", File.separator).replace("\\", File.separator)
    }
    return if (base.endsWith(separator)) base + normalizedRelative else base + separator + normalizedRelative
}

private fun wslAdapterBaseRuntimeDir(wslHomeDirOverride: String? = null): String? {
    val homeDir = wslHomeDirOverride ?: AcpExecutionMode.wslHomeDir() ?: return null
    return "$homeDir/.agent-dock"
}
