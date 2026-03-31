package unified.llm.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import unified.llm.acp.AcpAdapterPaths
import unified.llm.gitcommit.GitCommitFeatureRuntimeState
import java.io.File

object GlobalSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun settingsFile(): File = File(AcpAdapterPaths.getBaseRuntimeDir(), "settings.json")

    fun load(): GlobalSettings {
        val file = settingsFile()
        if (!file.isFile) {
            return save(GlobalSettings())
        }

        val loaded = runCatching {
            json.decodeFromString<GlobalSettings>(file.readText())
        }.getOrDefault(GlobalSettings())
        GitCommitFeatureRuntimeState.setEnabled(loaded.gitCommitGeneration.enabled)
        return loaded
    }

    fun save(settings: GlobalSettings): GlobalSettings {
        val normalized = settings.copy(
            wslDistributionName = settings.wslDistributionName.trim(),
            audioTranscription = settings.audioTranscription.copy(
                language = normalizeLanguage(settings.audioTranscription.language)
            ),
            gitCommitGeneration = settings.gitCommitGeneration.copy(
                adapterId = settings.gitCommitGeneration.adapterId.trim(),
                modelId = settings.gitCommitGeneration.modelId.trim(),
                instructions = settings.gitCommitGeneration.instructions.trim()
            )
        )
        val file = settingsFile()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(normalized))
        GitCommitFeatureRuntimeState.setEnabled(normalized.gitCommitGeneration.enabled)
        return normalized
    }

    fun loadAudioTranscriptionSettings(): AudioTranscriptionSettings {
        val settings = load().audioTranscription
        return settings.copy(language = normalizeLanguage(settings.language))
    }

    fun saveAudioTranscriptionSettings(settings: AudioTranscriptionSettings): AudioTranscriptionSettings {
        val current = load()
        return save(
            current.copy(
                audioTranscription = current.audioTranscription.copy(
                    language = normalizeLanguage(settings.language)
                )
            )
        ).audioTranscription
    }

    private fun normalizeLanguage(language: String?): String {
        return language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "auto"
    }
}
