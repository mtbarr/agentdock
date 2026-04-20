package unified.ai.gui.history

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.ZoneId

internal object GeminiCliHistory : AdapterHistory {
    override val adapterId: String = "gemini-cli"

    private val sessionLineRegex = Regex("""^\s*\d+\.\s+(.*?)\s+\((.*?)\)\s+\[([^\]]+)]\s*$""")

    override fun collectSessions(projectPath: String): List<SessionMeta> {
        val output = runAgentHistoryCliCommand(adapterId, projectPath, listOf("--list-sessions")) ?: return emptyList()
        val now = Instant.now()
        return output.lineSequence()
            .mapNotNull { line -> parseSessionLine(projectPath, line, now) }
            .toList()
    }

    private fun parseSessionLine(projectPath: String, rawLine: String, now: Instant): SessionMeta? {
        val match = sessionLineRegex.matchEntire(rawLine.trim()) ?: return null
        val title = fallbackHistoryTitle(match.groupValues[1])
        val relativeTime = match.groupValues[2].trim()
        val sessionId = match.groupValues[3].trim()
        if (sessionId.isBlank()) return null

        val timestamp = parseRelativeTimestamp(relativeTime, now) ?: now.toEpochMilli()
        return SessionMeta(
            sessionId = sessionId,
            adapterName = adapterId,
            projectPath = projectPath,
            title = title,
            filePath = "",
            createdAt = timestamp,
            updatedAt = timestamp
        )
    }

    private fun parseRelativeTimestamp(value: String, now: Instant): Long? {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return null
        if (normalized == "just now") return now.toEpochMilli()

        val simpleMatch = Regex("""^(a|\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago$""")
            .matchEntire(normalized)
            ?: return null

        val amount = if (simpleMatch.groupValues[1] == "a") 1L else simpleMatch.groupValues[1].toLongOrNull() ?: return null
        val instant = when (simpleMatch.groupValues[2]) {
            "second" -> now.minus(amount, ChronoUnit.SECONDS)
            "minute" -> now.minus(amount, ChronoUnit.MINUTES)
            "hour" -> now.minus(amount, ChronoUnit.HOURS)
            "day" -> now.minus(amount, ChronoUnit.DAYS)
            "week" -> now.minus(amount, ChronoUnit.WEEKS)
            "month" -> now.atZone(ZoneId.systemDefault()).minusMonths(amount).toInstant()
            "year" -> now.atZone(ZoneId.systemDefault()).minusYears(amount).toInstant()
            else -> return null
        }
        return instant.toEpochMilli()
    }

    override fun deleteSession(projectPath: String, sessionId: String, sourceFilePath: String?): Boolean {
        return runAgentHistoryCliCommand(adapterId, projectPath, listOf("--delete-session", sessionId)).let { it != null }
    }
}
