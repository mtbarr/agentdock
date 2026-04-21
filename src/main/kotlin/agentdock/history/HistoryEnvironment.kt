package agentdock.history

import agentdock.acp.AcpAdapterPaths
import agentdock.acp.AcpExecutionMode
import agentdock.acp.AcpExecutionTarget

internal object HistoryEnvironment {
    fun currentWslDistributionName(): String? {
        if (AcpAdapterPaths.getExecutionTarget() != AcpExecutionTarget.WSL) return null
        return AcpExecutionMode.selectedWslDistributionName().trim().takeIf { it.isNotEmpty() }
    }

    fun historySyncKey(projectPath: String, wslDistributionName: String? = currentWslDistributionName()): String {
        return if (wslDistributionName.isNullOrBlank()) projectPath else "$projectPath||wsl:$wslDistributionName"
    }

    fun matchesCurrentHistoryEnvironment(
        conversation: HistoryConversationIndexEntry,
        wslDistributionName: String? = currentWslDistributionName()
    ): Boolean {
        return if (wslDistributionName.isNullOrBlank()) {
            conversation.wslDistributionName.isNullOrBlank()
        } else {
            conversation.wslDistributionName == wslDistributionName
        }
    }

    fun conversationId(adapterName: String, sessionId: String): String {
        val wslDistributionName = currentWslDistributionName()
        val suffix = if (wslDistributionName.isNullOrBlank()) "" else ":wsl:$wslDistributionName"
        return "conv_" + historyHashMd5("$adapterName:$sessionId$suffix")
    }
}
