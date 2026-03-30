package unified.llm.gitcommit

import com.intellij.openapi.project.Project
import unified.llm.acp.AcpAdapterConfig
import unified.llm.acp.AcpAdapterPaths
import unified.llm.settings.GlobalSettingsStore

data class GitCommitGenerationConfig(
    val adapterId: String,
    val modelId: String,
    val instructions: String
)

object GitCommitGenerationSettingsFacade {
    fun resolve(project: Project): GitCommitGenerationConfig? {
        if (project.basePath.isNullOrBlank()) {
            return null
        }

        val settings = GlobalSettingsStore.load().gitCommitGeneration
        if (!settings.enabled) {
            return null
        }

        val installedAdapters = AcpAdapterConfig.getAllAdapters().values
            .filter { AcpAdapterPaths.isDownloaded(it.id) }
        if (installedAdapters.isEmpty()) {
            return null
        }

        val selectedAdapter = installedAdapters.firstOrNull { it.id == settings.adapterId }
            ?: return null

        return GitCommitGenerationConfig(
            adapterId = selectedAdapter.id,
            modelId = settings.modelId.trim(),
            instructions = settings.instructions.trim()
        )
    }
}
