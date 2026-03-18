package unified.llm.acp

import kotlinx.serialization.json.*
import java.io.File

/**
 * Fetches usage/quota data from different AI provider adapters.
 */
internal object AcpUsageDataFetcher {

    fun fetchGeminiUsageData(adapterId: String): String {
        return try {
            val adapterDir = File(AcpAdapterPaths.getDownloadPath(adapterId))
            if (!adapterDir.exists()) return ""

            val process = ProcessBuilder("node", "node_modules/@google/gemini-cli/dist/index.js", "--usage-json")
                .directory(adapterDir).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()

            val jsonLine = output.lines().find { it.startsWith("__GEMINI_USAGE_JSON__") }
            if (jsonLine != null) jsonLine.substring("__GEMINI_USAGE_JSON__".length).trim()
            else output.trim()
        } catch (_: Exception) { "" }
    }

    fun fetchClaudeUsageData(): String {
        val accessToken = try {
            val credentialsFile = File(System.getProperty("user.home"), ".claude/.credentials.json")
            if (!credentialsFile.exists()) null
            else Json.parseToJsonElement(credentialsFile.readText()).jsonObject
                .get("claudeAiOauth")?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
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
            val authFile = File(System.getProperty("user.home"), ".codex/auth.json")
            if (!authFile.exists()) return ""
            Json.parseToJsonElement(authFile.readText()).jsonObject
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
}
