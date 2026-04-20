package unified.ai.gui.gitcommit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NotNull
import unified.ai.gui.acp.AcpClientService

class GenerateGitCommitMessageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: @NotNull AnActionEvent) {
        val project = e.project
        e.presentation.text = "Generate Commit Message"
        e.presentation.description = "Generate a commit message with the configured AI agent"
        e.presentation.isEnabledAndVisible = project != null && GitCommitFeatureRuntimeState.isEnabled()
    }

    override fun actionPerformed(e: @NotNull AnActionEvent) {
        val project = e.project ?: return
        if (!GitCommitFeatureRuntimeState.isEnabled()) {
            return
        }
        val commitContext = resolveCommitContext(e)
        val commitMessageTarget = commitContext.commitMessageTarget
        if (commitMessageTarget == null) {
            showWarning(project, "Git Commit Generation", "Unable to access the commit message field.")
            return
        }
        if (commitContext.changes.isEmpty()) {
            showWarning(project, "Git Commit Generation", "Select changes in the commit view first.")
            return
        }

        val previousMessage = commitMessageTarget.read()
        commitMessageTarget.startLoading()
        commitMessageTarget.write("Generating commit message...")

        val acpService = AcpClientService.getInstance(project)
        acpService.scope.launch {
            val result = runCatching {
                val config = GitCommitGenerationSettingsFacade.resolve(project)
                    ?: error("Git commit generation is disabled or not configured.")
                GitCommitAcpExecutor(project, acpService).generateMessage(config, commitContext.changes)
            }

            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { message ->
                    commitMessageTarget.write(message)
                    commitMessageTarget.stopLoading()
                }.onFailure { error ->
                    commitMessageTarget.write(previousMessage)
                    commitMessageTarget.stopLoading()
                    Messages.showErrorDialog(
                        project,
                        error.message ?: error.toString(),
                        "Git Commit Generation"
                    )
                }
            }
        }
    }

    private fun resolveCommitContext(e: AnActionEvent): CommitActionContext {
        var commitMessageTarget: CommitMessageTarget? = null
        var changes: Collection<Change> = emptyList()

        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (workflowUi != null) {
            commitMessageTarget = ReflectionCommitMessageTarget.fromWorkflowUi(workflowUi)
            changes = readWorkflowUiIncludedChanges(workflowUi)
        }

        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        if (commitMessageTarget == null && workflowHandler is CommitMessageI) {
            commitMessageTarget = LegacyCommitMessageTarget(workflowHandler)
        }

        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (messageControl is CheckinProjectPanel) {
            if (changes.isEmpty()) {
                changes = messageControl.selectedChanges
            }
            if (commitMessageTarget == null) {
                commitMessageTarget = LegacyCommitMessageTarget(messageControl)
            }
        }

        if (commitMessageTarget == null && messageControl is CommitMessageI) {
            commitMessageTarget = LegacyCommitMessageTarget(messageControl)
        }

        if (changes.isEmpty()) {
            val selectedChanges = e.getData(VcsDataKeys.SELECTED_CHANGES)
            if (!selectedChanges.isNullOrEmpty()) {
                changes = selectedChanges.toList()
            }
        }

        if (changes.isEmpty()) {
            val selectedChanges = e.getData(VcsDataKeys.CHANGES)
            if (!selectedChanges.isNullOrEmpty()) {
                changes = selectedChanges.toList()
            }
        }

        return CommitActionContext(commitMessageTarget = commitMessageTarget, changes = changes)
    }

    private fun showWarning(project: com.intellij.openapi.project.Project, title: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, message, title)
        }
    }

    private fun readCommitMessage(panel: CommitMessageI): String {
        val methodNames = listOf("getCommitMessage", "getComment", "getText")
        methodNames.forEach { methodName ->
            val value = runCatching {
                panel.javaClass.getMethod(methodName).invoke(panel) as? String
            }.getOrNull()
            if (value != null) {
                return value
            }
        }
        return ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun readWorkflowUiIncludedChanges(workflowUi: Any): Collection<Change> {
        return runCatching {
            workflowUi.javaClass.getMethod("getIncludedChanges").invoke(workflowUi) as? Collection<Change>
        }.getOrNull().orEmpty()
    }

    private data class CommitActionContext(
        val commitMessageTarget: CommitMessageTarget?,
        val changes: Collection<Change>
    )

    private interface CommitMessageTarget {
        fun read(): String
        fun write(message: String)
        fun startLoading() {}
        fun stopLoading() {}
    }

    private inner class LegacyCommitMessageTarget(
        private val panel: CommitMessageI
    ) : CommitMessageTarget {
        override fun read(): String = readCommitMessage(panel)
        override fun write(message: String) {
            panel.setCommitMessage(message)
        }
    }

    private class ReflectionCommitMessageTarget(
        private val messageUi: Any
    ) : CommitMessageTarget {
        companion object {
            fun fromWorkflowUi(workflowUi: Any): ReflectionCommitMessageTarget? {
                val messageUi = runCatching {
                    workflowUi.javaClass.getMethod("getCommitMessageUi").invoke(workflowUi)
                }.getOrNull() ?: return null
                return ReflectionCommitMessageTarget(messageUi)
            }
        }

        override fun read(): String {
            return runCatching {
                messageUi.javaClass.getMethod("getText").invoke(messageUi) as? String
            }.getOrNull().orEmpty()
        }

        override fun write(message: String) {
            runCatching {
                messageUi.javaClass.getMethod("setText", String::class.java).invoke(messageUi, message)
            }
        }

        override fun startLoading() {
            runCatching {
                messageUi.javaClass.getMethod("startLoading").invoke(messageUi)
            }
        }

        override fun stopLoading() {
            runCatching {
                messageUi.javaClass.getMethod("stopLoading").invoke(messageUi)
            }
        }
    }
}
