package unified.llm.acp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class AcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AcpClientService.getInstance(project).initializeDownloadedAdaptersInBackground()
    }
}
