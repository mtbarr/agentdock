package unified.llm.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AcpAuthService {
    private const val LOGOUT_COMMAND_TIMEOUT_SECONDS = 15L
    private const val STATUS_COMMAND_TIMEOUT_SECONDS = 15L
    private const val LOGIN_COMMAND_TIMEOUT_MS = 60_000L
    const val INTERACTIVE_LOGIN_TIMEOUT_MS = 300_000L

    private val activeLoginCounts = ConcurrentHashMap<String, Int>()
    private val statusJson = Json { ignoreUnknownKeys = true }

    data class AuthStatus(
        val authenticated: Boolean
    )

    fun incrementActive(adapterId: String) {
        activeLoginCounts.compute(adapterId) { _, count -> (count ?: 0) + 1 }
    }

    fun decrementActive(adapterId: String) {
        activeLoginCounts.compute(adapterId) { _, count ->
            val newCount = (count ?: 0) - 1
            if (newCount <= 0) null else newCount
        }
    }

    fun resetTransientState() {
        activeLoginCounts.clear()
    }

    fun getLoginMode(adapterName: String): String {
        return runCatching { AcpAdapterConfig.getAdapterInfo(adapterName).authConfig?.loginMode }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "background"
    }

    fun buildLoginCommand(adapterName: String): List<String>? {
        val adapterInfo = runCatching { AcpAdapterConfig.getAdapterInfo(adapterName) }.getOrNull() ?: return null
        val authConfig = adapterInfo.authConfig ?: return null
        if (authConfig.loginArgs.isEmpty()) return null
        return buildCommand(adapterInfo, authConfig, authConfig.loginArgs)
    }

    fun getAuthStatus(adapterName: String): AuthStatus {
        val adapterInfo = runCatching { AcpAdapterConfig.getAdapterInfo(adapterName) }.getOrNull()
            ?: return AuthStatus(authenticated = false)
        val authConfig = adapterInfo.authConfig ?: return AuthStatus(authenticated = false)
        if (authConfig.statusArgs.isEmpty()) return AuthStatus(authenticated = false)

        return runCatching {
            val cmd = buildCommand(adapterInfo, authConfig, authConfig.statusArgs).orEmpty()
            if (cmd.isEmpty()) return@runCatching AuthStatus(authenticated = false)

            val proc = ProcessBuilder(cmd)
                .directory(resolveWorkingDir(adapterInfo))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val finished = proc.waitFor(STATUS_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching AuthStatus(authenticated = false)
            }
            if (proc.exitValue() != 0) return@runCatching AuthStatus(authenticated = false)

            AuthStatus(authenticated = parseAuthenticatedFromStatusOutput(output))
        }.getOrElse {
            AuthStatus(authenticated = false)
        }
    }

    fun isAuthenticating(adapterId: String): Boolean = activeLoginCounts.containsKey(adapterId)

    suspend fun login(
        adapterName: String,
        projectPath: String? = null,
        onProgress: (suspend () -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val adapterInfo = AcpAdapterConfig.getAdapterInfo(adapterName)
            val authConfig = adapterInfo.authConfig ?: return@withContext false
            val loginArgs = authConfig.loginArgs
            if (loginArgs.isEmpty()) {
                return@withContext false
            }

            val cmd = buildCommand(adapterInfo, authConfig, loginArgs) ?: return@withContext false
            val process = ProcessBuilder(cmd)
                .directory(resolveWorkingDir(adapterInfo, projectPath))
                .redirectErrorStream(true)
                .start()

            val outputBuilder = java.lang.StringBuilder()
            Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { outputBuilder.appendLine(it) }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; name = "acp-login-drain-$adapterName" }.start()

            val startTime = System.currentTimeMillis()
            var lastPushTime = 0L
            while (System.currentTimeMillis() - startTime < LOGIN_COMMAND_TIMEOUT_MS) {
                if (System.currentTimeMillis() - lastPushTime > 3000L) {
                    onProgress?.invoke()
                    lastPushTime = System.currentTimeMillis()
                }

                if (!process.isAlive) {
                    val exitCode = process.exitValue()
                    if (exitCode != 0) {
                        val raw = outputBuilder.toString().trim()
                        val tail = if (raw.length > 240) raw.takeLast(240) else raw
                        val details = if (tail.isNotBlank()) ": $tail" else ""
                        throw Exception("Login command failed (exit $exitCode)$details")
                    }
                    return@withContext true
                }

                delay(1000L)
            }

            process.destroyForcibly()
            throw Exception("Login timed out")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.isNotBlank() && msg != "null") throw Exception("Login failed: $msg")
            false
        }
    }

    suspend fun logout(adapterName: String): Boolean = withContext(Dispatchers.IO) {
        val adapterInfo = AcpAdapterConfig.getAdapterInfo(adapterName)
        val authConfig = adapterInfo.authConfig ?: return@withContext false

        if (authConfig.logoutArgs.isNotEmpty()) {
            val cmd = buildCommand(adapterInfo, authConfig, authConfig.logoutArgs)
            if (!cmd.isNullOrEmpty()) {
                try {
                    val proc = ProcessBuilder(cmd)
                        .directory(resolveWorkingDir(adapterInfo))
                        .redirectErrorStream(true)
                        .start()
                    val drainer = Thread {
                        try {
                            proc.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { }
                            }
                        } catch (_: Exception) {
                        }
                    }.apply {
                        isDaemon = true
                        name = "acp-auth-logout-drain-$adapterName"
                    }
                    drainer.start()
                    val finished = proc.waitFor(LOGOUT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                    }
                    drainer.join(1000L)
                } catch (_: Exception) {
                }
            }
        }

        true
    }

    private fun buildCommand(
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        authConfig: AcpAdapterConfig.AuthConfig,
        args: List<String>
    ): List<String>? {
        val baseCommand = resolveBaseCommand(adapterInfo, authConfig) ?: return null
        return baseCommand + args
    }

    private fun resolveBaseCommand(
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        authConfig: AcpAdapterConfig.AuthConfig
    ): List<String>? {
        if (authConfig.command.isNotEmpty()) {
            return authConfig.command.toMutableList().also { cmd ->
                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    val first = cmd.firstOrNull().orEmpty()
                    if (first.equals("npx", ignoreCase = true)) {
                        cmd[0] = "npx.cmd"
                    } else if (first.equals("npm", ignoreCase = true)) {
                        cmd[0] = "npm.cmd"
                    }
                }
            }
        }

        val script = resolveScriptPath(adapterInfo, authConfig.authScript) ?: return null
        val cmd = mutableListOf<String>()
        val os = System.getProperty("os.name").lowercase()

        if (os.contains("win") && (script.first.endsWith(".cmd", true) || script.first.endsWith(".bat", true))) {
            cmd.addAll(listOf("cmd.exe", "/c", script.first))
        } else {
            if (script.second) cmd.add(findNodeExecutable())
            cmd.add(script.first)
        }
        return cmd
    }

    private fun resolveScriptPath(
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        authScript: String?
    ): Pair<String, Boolean>? {
        val downloadPath = AcpAdapterPaths.getDownloadPath(adapterInfo.id)
        val adapterRoot = if (downloadPath.isNotEmpty()) File(downloadPath) else null
        if (adapterRoot == null) return null

        if (authScript.isNullOrBlank()) {
            val file = AcpAdapterPaths.resolveLaunchFile(adapterRoot, adapterInfo) ?: return null
            if (!file.isFile) return null
            val path = file.absolutePath
            val useNode = path.endsWith(".js") || path.endsWith(".mjs")
            return path to useNode
        }

        var relPath = authScript
        if (relPath.contains("node_modules/.bin/") || relPath.contains("node_modules\\.bin\\")) {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win") && !relPath.endsWith(".cmd") && !relPath.endsWith(".bat")) {
                relPath += ".cmd"
            }
        }

        val explicitFile = File(relPath)
        if (explicitFile.isAbsolute && explicitFile.isFile) {
            val path = explicitFile.absolutePath
            val useNode = path.endsWith(".js") || path.endsWith(".mjs")
            return path to useNode
        }

        val file = File(adapterRoot, relPath)
        if (!file.isFile) return null
        val path = file.absolutePath
        val useNode = relPath.endsWith(".js") || relPath.endsWith(".mjs")
        return path to useNode
    }

    private fun resolveWorkingDir(
        adapterInfo: AcpAdapterConfig.AdapterInfo,
        projectPath: String? = null
    ): File? {
        if (!projectPath.isNullOrBlank()) {
            return File(projectPath)
        }
        val downloadPath = AcpAdapterPaths.getDownloadPath(adapterInfo.id)
        return downloadPath.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun findNodeExecutable(): String {
        return if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
    }

    private fun parseAuthenticatedFromStatusOutput(output: String): Boolean {
        if (output.isBlank()) return false

        val parsedJson = runCatching { statusJson.parseToJsonElement(output).jsonObject }.getOrNull()
        if (parsedJson != null) {
            val loggedIn = parsedJson["loggedIn"]?.jsonPrimitive?.booleanOrNull
            if (loggedIn != null) return loggedIn
            val authenticated = parsedJson["authenticated"]?.jsonPrimitive?.booleanOrNull
            if (authenticated != null) return authenticated
        }

        val lower = output.lowercase()
        if (lower.contains("not logged in")) return false
        if (lower.contains("logged in")) return true
        if (lower.contains("authenticated")) return true
        return false
    }

}
