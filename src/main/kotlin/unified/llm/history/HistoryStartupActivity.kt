package unified.llm.history

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import unified.llm.acp.AcpClientService

class HistoryStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val projectPath = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!projectPath.isNullOrBlank()) {
                UnifiedHistoryService.startInitialHistorySync(projectPath)
            }
            AcpClientService.getInstance(project).initializeDownloadedAdaptersInBackground()
        }
    }
}
