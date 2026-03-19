package unified.llm.acp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import unified.llm.utils.escapeForJsString
import java.util.concurrent.ConcurrentHashMap


/**
 * Connects AcpClientService to the JCEF/React UI.
 * Handles: startAgent, sendPrompt, loadSession (from frontend);
 * pushes content chunks, status, adapters, permissions (to frontend).
 */
class AcpBridge(
    internal val browser: JBCefBrowser,
    internal val service: AcpClientService,
    internal val scope: CoroutineScope
) {
    internal val logger = Logger.getInstance(AcpBridge::class.java)

    internal var sendPromptQuery: JBCefJSQuery? = null
    internal var startAgentQuery: JBCefJSQuery? = null
    internal var setModelQuery: JBCefJSQuery? = null
    internal var setModeQuery: JBCefJSQuery? = null
    internal var listAdaptersQuery: JBCefJSQuery? = null
    internal var cancelPromptQuery: JBCefJSQuery? = null
    internal var stopAgentQuery: JBCefJSQuery? = null
    internal var respondPermissionQuery: JBCefJSQuery? = null
    internal var readyQuery: JBCefJSQuery? = null
    internal var loadConversationQuery: JBCefJSQuery? = null
    internal var downloadAgentQuery: JBCefJSQuery? = null
    internal var deleteAgentQuery: JBCefJSQuery? = null
    internal var toggleAgentEnabledQuery: JBCefJSQuery? = null
    internal var loginAgentQuery: JBCefJSQuery? = null
    internal var logoutAgentQuery: JBCefJSQuery? = null
    internal var fetchUsageQuery: JBCefJSQuery? = null
    internal var undoFileQuery: JBCefJSQuery? = null
    internal var undoAllFilesQuery: JBCefJSQuery? = null
    internal var processFileQuery: JBCefJSQuery? = null
    internal var keepAllQuery: JBCefJSQuery? = null
    internal var removeProcessedFilesQuery: JBCefJSQuery? = null
    internal var getChangesStateQuery: JBCefJSQuery? = null
    internal var showDiffQuery: JBCefJSQuery? = null
    internal var openFileQuery: JBCefJSQuery? = null
    internal var openUrlQuery: JBCefJSQuery? = null
    internal var attachFileQuery: JBCefJSQuery? = null
    internal var updateSessionMetadataQuery: JBCefJSQuery? = null
    internal var continueConversationQuery: JBCefJSQuery? = null
    internal var saveConversationTranscriptQuery: JBCefJSQuery? = null
    internal var openAgentCliQuery: JBCefJSQuery? = null
    internal var openHistoryConversationCliQuery: JBCefJSQuery? = null
    internal var searchFilesQuery: JBCefJSQuery? = null

    internal val promptJobs = ConcurrentHashMap<String, Job>()
    internal val lastStatusByChatId = ConcurrentHashMap<String, String>()
    internal val downloadStatuses = ConcurrentHashMap<String, String>()
    internal val authActionJobs = ConcurrentHashMap<String, Job>()
    internal val authFetchJobs = ConcurrentHashMap<String, Job>()
    internal val authStates = ConcurrentHashMap<String, Boolean>()
    internal val replaySeqByChatId = ConcurrentHashMap<String, Int>()
    internal val livePromptCaptures = ConcurrentHashMap<String, LivePromptCapture>()
    internal val historyReplayCaptures = ConcurrentHashMap<String, HistoryReplayCapture>()
    internal val suppressReplayForChatIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    internal val cli = AcpBridgeCli(service.project) { action -> runOnEdt(action) }
    internal val audio = AcpAudioPlayer(scope)

    companion object {
        const val START_AGENT_TIMEOUT_MS = 45_000L
    }

    fun install() {
        installServiceCallbacks()
        installAdapterQueries()
        installConversationQueries()
        installFileChangeQueries()
        installMiscQueries()
    }

    internal fun nextReplaySeq(chatId: String, isReplay: Boolean): Int? {
        if (!isReplay) return null
        return replaySeqByChatId.compute(chatId) { _, prev -> (prev ?: 0) + 1 }
    }

    internal fun runOnEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action)

    internal fun escapeJsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    internal fun jsStringLiteral(value: String) = "'${value.escapeForJsString()}'"

    internal fun dispatchContentChunkJson(json: String) {
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                """
                if(window.__onContentChunk){
                    var __chunk = $json;
                    window.__onContentChunk(__chunk);
                }
                """.trimIndent(),
                browser.cefBrowser.url, 0
            )
        }
    }
}
