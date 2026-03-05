package unified.llm.changes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class ChangesState(
    val sessionId: String,
    val adapterName: String,
    val baseToolCallIndex: Int = 0,
    val processedFiles: List<String> = emptyList(),
    val updatedAt: Long = Instant.now().toEpochMilli()
)

object ChangesStateService {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Check if two file paths refer to the same file.
     * Handles relative vs absolute paths across Windows, Linux, and MacOS.
     */
    private fun pathsMatch(path1: String, path2: String): Boolean {
        val p1 = path1.replace("\\", "/")
        val p2 = path2.replace("\\", "/")

        if (p1 == p2) return true

        // Check if one path ends with the other (handles absolute vs relative)
        if (p1.endsWith("/$p2")) return true
        if (p2.endsWith("/$p1")) return true

        return false
    }

    private val baseDir: File by lazy {
        val homeDir = System.getProperty("user.home")
        File(homeDir, ".unified-llm/sessions").also { it.mkdirs() }
    }

    /**
     * Flat file: ~/.unified-llm/sessions/{adapterName}/{sessionId}.json
     */
    private fun getStateFile(sessionId: String, adapterName: String): File {
        val adapterDir = File(baseDir, adapterName)
        adapterDir.mkdirs()
        return File(adapterDir, "$sessionId.json")
    }

    fun hasState(sessionId: String, adapterName: String): Boolean {
        return getStateFile(sessionId, adapterName).exists()
    }

    fun loadState(sessionId: String, adapterName: String): ChangesState? {
        val file = getStateFile(sessionId, adapterName)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<ChangesState>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveState(state: ChangesState) {
        val file = getStateFile(state.sessionId, state.adapterName)
        try {
            file.writeText(json.encodeToString(ChangesState.serializer(), state.copy(updatedAt = Instant.now().toEpochMilli())))
        } catch (_: Exception) {}
    }

    fun ensureState(sessionId: String, adapterName: String): ChangesState {
        val existing = loadState(sessionId, adapterName)
        if (existing != null) return existing
        val created = ChangesState(sessionId, adapterName)
        saveState(created)
        return created
    }

    fun addProcessedFile(sessionId: String, adapterName: String, filePath: String) {
        val current = loadState(sessionId, adapterName) ?: ChangesState(sessionId, adapterName)
        // Use pathsMatch to avoid duplicates with different path formats (relative vs absolute)
        if (current.processedFiles.any { pathsMatch(it, filePath) }) return
        saveState(current.copy(processedFiles = current.processedFiles + filePath))
    }

    fun removeProcessedFiles(sessionId: String, adapterName: String, filePaths: List<String>) {
        val current = loadState(sessionId, adapterName) ?: return
        // Use pathsMatch for cross-platform absolute/relative comparison
        val updated = current.processedFiles.filter { processedPath ->
            !filePaths.any { pathsMatch(it, processedPath) }
        }
        if (updated.size != current.processedFiles.size) {
            saveState(current.copy(processedFiles = updated))
        }
    }

    fun setBaseIndex(sessionId: String, adapterName: String, index: Int) {
        val current = loadState(sessionId, adapterName) ?: ChangesState(sessionId, adapterName)
        saveState(current.copy(baseToolCallIndex = index, processedFiles = emptyList()))
    }

    fun deleteState(sessionId: String, adapterName: String) {
        getStateFile(sessionId, adapterName).delete()
    }
}
