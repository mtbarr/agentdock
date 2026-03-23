package unified.llm.acp

import kotlinx.serialization.json.*
import java.io.File

/**
 * Fetches usage/quota data from different AI provider adapters.
 */
internal object AcpUsageDataFetcher {

    fun fetchGeminiUsageData(adapterId: String): String {
        return try {
            when (AcpAdapterPaths.getExecutionTarget()) {
                AcpExecutionTarget.LOCAL -> {
                    val adapterDir = File(AcpAdapterPaths.getDownloadPath(adapterId))
                    if (!adapterDir.exists()) return ""
                    val process = ProcessBuilder("node", "node_modules/@google/gemini-cli/dist/index.js", "--usage-json")
                        .directory(adapterDir)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    process.errorStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                    val jsonLine = output.lines().find { it.startsWith("__GEMINI_USAGE_JSON__") }
                    if (jsonLine != null) jsonLine.substring("__GEMINI_USAGE_JSON__".length).trim() else output.trim()
                }
                AcpExecutionTarget.WSL -> {
                    val adapterDir = AcpAdapterPaths.getDownloadPath(adapterId, AcpExecutionTarget.WSL)
                    val result = AcpExecutionMode.runWslShell(
                        script = "cd ${quoteUnixShellArg(adapterDir)} && node node_modules/@google/gemini-cli/dist/index.js --usage-json",
                        timeoutSeconds = 60
                    ) ?: return ""
                    val output = result.stdout.trim()
                    val jsonLine = output.lines().find { it.startsWith("__GEMINI_USAGE_JSON__") }
                    if (jsonLine != null) jsonLine.substring("__GEMINI_USAGE_JSON__".length).trim() else output
                }
            }
        } catch (_: Exception) { "" }
    }

    fun fetchClaudeUsageData(): String {
        val accessToken = try {
            readTargetFile("~/.claude/.credentials.json")
                ?.let { Json.parseToJsonElement(it).jsonObject.get("claudeAiOauth")?.jsonObject?.get("accessToken")?.jsonPrimitive?.content }
        } catch (_: Exception) { null }

        if (accessToken == null) return """{"authType":"api_key"}"""

        return try {
            val conn = java.net.URI("https://api.anthropic.com/api/oauth/usage").toURL()
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "claude-code/2.1.71")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = Json.parseToJsonElement(body).jsonObject
                JsonObject(obj + ("authType" to JsonPrimitive("subscription"))).toString()
            } else """{"authType":"subscription"}"""
        } catch (_: Exception) { """{"authType":"subscription"}""" }
    }

    fun fetchCodexUsageData(): String {
        val authJson = try {
            val text = readTargetFile("~/.codex/auth.json") ?: return ""
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) { return "" }

        if (authJson["auth_mode"]?.jsonPrimitive?.content == "apikey") return """{"authType":"api_key"}"""

        val accessToken = authJson["tokens"]?.jsonObject?.get("access_token")?.jsonPrimitive?.content ?: return ""

        return try {
            val conn = java.net.URI("https://chatgpt.com/backend-api/wham/usage").toURL()
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = Json.parseToJsonElement(body).jsonObject
                JsonObject(obj + ("authType" to JsonPrimitive("subscription"))).toString()
            } else """{"authType":"subscription"}"""
        } catch (_: Exception) { """{"authType":"subscription"}""" }
    }

    private fun readTargetFile(rawPath: String): String? {
        return when (AcpAdapterPaths.getExecutionTarget()) {
            AcpExecutionTarget.LOCAL -> {
                val resolved = rawPath.replace("~", System.getProperty("user.home"))
                val file = File(resolved)
                if (!file.exists()) null else file.readText()
            }
            AcpExecutionTarget.WSL -> {
                val wslHome = AcpExecutionMode.wslHomeDir() ?: return null
                val resolved = rawPath.replace("~", wslHome)
                val result = AcpExecutionMode.runWslShell("cat ${quoteUnixShellArg(resolved)}", timeoutSeconds = 15) ?: return null
                if (result.exitCode != 0) null else result.stdout
            }
        }
    }
}
