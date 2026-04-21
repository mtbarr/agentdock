package agentdock.history

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import agentdock.acp.AcpAdapterPaths
import agentdock.utils.atomicWriteText
import java.io.File

internal object HistoryStorage {
    private val conversationIdPattern = Regex("[A-Za-z0-9_-]{1,256}")

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun projectIndexFile(projectPath: String): File {
        val baseDir = File(AcpAdapterPaths.getBaseRuntimeDir(), "projects")
        val slug = historyProjectPathSlug(projectPath.replace("/", "\\"))
        return File(File(baseDir, slug), "index.json")
    }

    fun projectConversationsDir(projectPath: String): File {
        return File(projectIndexFile(projectPath).parentFile, "conversations")
    }

    fun ephemeralSessionsFile(projectPath: String): File {
        return File(projectIndexFile(projectPath).parentFile, "ephemeral-sessions.json")
    }

    fun ensureProjectConversationsDir(projectPath: String): File {
        val dir = projectConversationsDir(projectPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun conversationDataFile(projectPath: String, conversationId: String): File {
        val safeConversationId = requireSafeConversationId(conversationId)
        return File(ensureProjectConversationsDir(projectPath), "$safeConversationId.json")
    }

    fun conversationTranscriptFile(projectPath: String, conversationId: String): File {
        val safeConversationId = requireSafeConversationId(conversationId)
        return File(ensureProjectConversationsDir(projectPath), "$safeConversationId.transcript.txt")
    }

    internal fun requireSafeConversationId(conversationId: String): String {
        val clean = conversationId.trim()
        require(conversationIdPattern.matches(clean)) {
            "Invalid history conversation id"
        }
        return clean
    }

    fun ensureProjectIndexFile(projectPath: String): File {
        val indexFile = projectIndexFile(projectPath)
        if (!indexFile.parentFile.exists()) {
            indexFile.parentFile.mkdirs()
        }
        if (!indexFile.exists()) {
            indexFile.atomicWriteText("[]")
        }
        return indexFile
    }

    fun readProjectIndex(indexFile: File): MutableList<HistoryConversationIndexEntry> {
        return runCatching {
            json.decodeFromString<List<HistoryConversationIndexEntry>>(indexFile.readText()).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun readExistingProjectIndex(projectPath: String): List<HistoryConversationIndexEntry> {
        if (projectPath.isBlank()) return emptyList()
        val indexFile = projectIndexFile(projectPath)
        if (!indexFile.exists() || !indexFile.isFile) return emptyList()
        return readProjectIndex(indexFile)
    }

    fun writeProjectIndex(indexFile: File, conversations: List<HistoryConversationIndexEntry>) {
        indexFile.atomicWriteText(json.encodeToString(conversations))
    }

    fun readEphemeralSessions(projectPath: String): MutableList<EphemeralSessionEntry> {
        if (projectPath.isBlank()) return mutableListOf()
        val file = ephemeralSessionsFile(projectPath)
        if (!file.exists() || !file.isFile) return mutableListOf()
        return runCatching {
            json.decodeFromString<List<EphemeralSessionEntry>>(file.readText()).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun writeEphemeralSessions(projectPath: String, entries: List<EphemeralSessionEntry>) {
        if (projectPath.isBlank()) return
        val file = ephemeralSessionsFile(projectPath)
        if (entries.isEmpty()) {
            deleteHistoryFileIfExists(file)
            return
        }
        val parent = file.parentFile
        if (!parent.exists()) parent.mkdirs()
        file.atomicWriteText(json.encodeToString(entries))
    }

    fun removeEphemeralSession(projectPath: String, adapterName: String, sessionId: String) {
        if (projectPath.isBlank() || adapterName.isBlank() || sessionId.isBlank()) return
        val remaining = readEphemeralSessions(projectPath)
            .filterNot { it.adapterName == adapterName && it.sessionId == sessionId }
        writeEphemeralSessions(projectPath, remaining)
    }
}
