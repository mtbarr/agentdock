package unified.llm.acp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import unified.llm.mcp.McpConfigStore

class AcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        McpConfigStore.ensureConfigFileExists()
        AcpClientService.getInstance(project).initializeDownloadedAdaptersInBackground()
    }
}
