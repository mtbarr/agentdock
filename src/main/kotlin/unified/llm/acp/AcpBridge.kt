package unified.llm.acp

import com.agentclientprotocol.model.*
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.BufferedInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import org.cef.browser.CefBrowser
import unified.llm.utils.escapeForJsString
import java.util.concurrent.ConcurrentHashMap
import unified.llm.changes.AgentDiffViewer
import unified.llm.changes.ChangesState
import unified.llm.changes.ChangesStateService
import unified.llm.changes.UndoFileHandler
import unified.llm.changes.UndoOperation
import unified.llm.history.ConversationAssistantMetadata
import unified.llm.history.UnifiedHistoryService


@Serializable
private data class AdapterModelPayload(val modelId: String, val name: String)

@Serializable
private data class AdapterModePayload(val id: String, val name: String)

@Serializable
private data class AdapterPayload(
    val id: String,
    val name: String,
    val iconPath: String,
    val defaultModelId: String,
    val models: List<AdapterModelPayload>,
    val defaultModeId: String,
    val modes: List<AdapterModePayload>,
    val downloaded: Boolean,
    val enabled: Boolean,
    val downloadPath: String,
    val hasAuthentication: Boolean,
    val authAuthenticated: Boolean,
    val authPath: String,
    val authenticating: Boolean,
    val downloading: Boolean,
    val downloadStatus: String,
    val cliAvailable: Boolean
)

@Serializable
private data class SaveConversationTranscriptPayload(
    val requestId: String,
    val conversationId: String,
    val text: String
)

@Serializable
private data class SaveConversationTranscriptResultPayload(
    val requestId: String,
    val conversationId: String,
    val success: Boolean,
    val filePath: String? = null,
    val error: String? = null
)

private val adapterJson = Json { encodeDefaults = true }

/**
 * Connects AcpClientService to the JCEF/React UI.
 * Handles: startAgent, sendPrompt, loadSession (from frontend);
 * pushes content chunks, status, adapters, permissions (to frontend).
 */
class AcpBridge(
    private val browser: JBCefBrowser,
    private val service: AcpClientService,
    private val scope: CoroutineScope
) {
    private val logger = Logger.getInstance(AcpBridge::class.java)
    private data class SerializedContentBlock(
        val type: String,
        val text: String? = null,
        val data: String? = null,
        val mimeType: String? = null
    )

    private data class LivePromptCapture(
        val captureId: String,
        val projectPath: String,
        val conversationId: String,
        val sessionId: String,
        val adapterName: String,
        val blocks: List<JsonObject>,
        val startedAtMillis: Long,
        val assistantMeta: ConversationAssistantMetadata?,
        var contextTokensUsed: Long? = null,
        var contextWindowSize: Long? = null,
        val events: MutableList<JsonObject> = mutableListOf()
    )

    private data class ReplaySessionCapture(
        val sessionId: String,
        val adapterName: String,
        val prompts: MutableList<ReplayPromptCapture> = mutableListOf()
    )

    private data class ReplayPromptCapture(
        val blocks: MutableList<JsonObject> = mutableListOf(),
        val events: MutableList<JsonObject> = mutableListOf(),
        var assistantMeta: ConversationAssistantMetadata? = null
    )

    private data class HistoryReplayCapture(
        val projectPath: String,
        val conversationId: String,
        var currentSessionId: String? = null,
        var currentAdapterName: String? = null,
        var currentModelId: String? = null,
        var currentModeId: String? = null,
        val sessions: MutableList<ReplaySessionCapture> = mutableListOf()
    )

    private var sendPromptQuery: JBCefJSQuery? = null
    private var startAgentQuery: JBCefJSQuery? = null
    private var setModelQuery: JBCefJSQuery? = null
    private var setModeQuery: JBCefJSQuery? = null
    private var listAdaptersQuery: JBCefJSQuery? = null
    private var cancelPromptQuery: JBCefJSQuery? = null
    private var stopAgentQuery: JBCefJSQuery? = null
    private var respondPermissionQuery: JBCefJSQuery? = null
    private var readyQuery: JBCefJSQuery? = null
    private var loadConversationQuery: JBCefJSQuery? = null
    private var downloadAgentQuery: JBCefJSQuery? = null
    private var deleteAgentQuery: JBCefJSQuery? = null
    private var toggleAgentEnabledQuery: JBCefJSQuery? = null
    private var loginAgentQuery: JBCefJSQuery? = null
    private var logoutAgentQuery: JBCefJSQuery? = null
    private var undoFileQuery: JBCefJSQuery? = null
    private var undoAllFilesQuery: JBCefJSQuery? = null
    private var processFileQuery: JBCefJSQuery? = null
    private var keepAllQuery: JBCefJSQuery? = null
    private var removeProcessedFilesQuery: JBCefJSQuery? = null
    private var getChangesStateQuery: JBCefJSQuery? = null
    private var showDiffQuery: JBCefJSQuery? = null
    private var openFileQuery: JBCefJSQuery? = null
    private var openUrlQuery: JBCefJSQuery? = null
    private var attachFileQuery: JBCefJSQuery? = null
    private var updateSessionMetadataQuery: JBCefJSQuery? = null
    private var continueConversationQuery: JBCefJSQuery? = null
    private var saveConversationTranscriptQuery: JBCefJSQuery? = null
    private var openAgentCliQuery: JBCefJSQuery? = null
    private var openHistoryConversationCliQuery: JBCefJSQuery? = null

    private val promptJobs = ConcurrentHashMap<String, Job>()
    private val lastStatusByChatId = ConcurrentHashMap<String, String>()
    private val downloadStatuses = ConcurrentHashMap<String, String>()
    private val replaySeqByChatId = ConcurrentHashMap<String, Int>()
    private val livePromptCaptures = ConcurrentHashMap<String, LivePromptCapture>()
    private val historyReplayCaptures = ConcurrentHashMap<String, HistoryReplayCapture>()
    private val suppressReplayForChatIds = ConcurrentHashMap.newKeySet<String>()

    companion object {
        const val START_AGENT_TIMEOUT_MS = 45_000L
    }

    fun install() {
        service.setOnLogEntry { pushLogEntry(it) }
        // Each JS query below: frontend calls window.__* ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ JBCefJSQuery ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ addHandler ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Kotlin logic ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Response
        service.setOnPermissionRequest { pushPermissionRequest(it) }
        service.setOnSessionUpdate { chatId: String, update: SessionUpdate, isReplay: Boolean, _meta: JsonElement? ->
            if (isReplay && suppressReplayForChatIds.contains(chatId)) {
                return@setOnSessionUpdate
            }
            val captureOnlyReplay = isReplay && historyReplayCaptures.containsKey(chatId)
            val sessionId = if (captureOnlyReplay) {
                historyReplayCaptures[chatId]?.currentSessionId.orEmpty()
            } else {
                service.sessionId(chatId).orEmpty()
            }
            val adapterName = if (captureOnlyReplay) {
                historyReplayCaptures[chatId]?.currentAdapterName.orEmpty()
            } else {
                service.activeAdapterName(chatId).orEmpty()
            }
            when (update) {
                is SessionUpdate.UserMessageChunk -> {
                    // During live prompting the frontend already adds the user message,
                    // so only push the echo during replay to reconstruct the conversation.
                    if (isReplay) {
                        recordReplayUserBlock(chatId, sessionId, adapterName, update.content)
                        if (!captureOnlyReplay) {
                            pushContentBlock(chatId, "user", update.content, isThought = false, isReplay = true)
                        }
                    }
                }
                is SessionUpdate.AgentMessageChunk -> {
                    recordContentBlock(chatId, sessionId, adapterName, "assistant", update.content, isThought = false, isReplay = isReplay)
                    if (!captureOnlyReplay) {
                        pushContentBlock(chatId, "assistant", update.content, isThought = false, isReplay = isReplay)
                    }
                }
                is SessionUpdate.AgentThoughtChunk -> {
                    recordContentBlock(chatId, sessionId, adapterName, "assistant", update.content, isThought = true, isReplay = isReplay)
                    if (!captureOnlyReplay) {
                        pushContentBlock(chatId, "assistant", update.content, isThought = true, isReplay = isReplay)
                    }
                }
                is SessionUpdate.CurrentModeUpdate -> {
                    if (!captureOnlyReplay) {
                        pushMode(chatId, update.currentModeId.value)
                    }
                }
                is SessionUpdate.ToolCall -> {
                    if (!isReplay) removeProcessedFilesForDiffs(chatId, update.content)
                    val json = try { Json.encodeToString(update) } catch (_: Exception) { update.toString() }
                    recordStoredEvent(
                        chatId,
                        sessionId,
                        adapterName,
                        buildStoredToolCallChunk(json),
                        isReplay
                    )
                    if (!captureOnlyReplay) {
                        pushToolCallChunk(chatId, json, isReplay)
                    }
                }
                is SessionUpdate.ToolCallUpdate -> {
                    if (!isReplay) removeProcessedFilesForDiffs(chatId, update.content)
                    val json = try { Json.encodeToString(update) } catch (_: Exception) { update.toString() }
                    recordStoredEvent(
                        chatId,
                        sessionId,
                        adapterName,
                        buildStoredToolCallUpdateChunk(update.toolCallId.value, json),
                        isReplay
                    )
                    if (!captureOnlyReplay) {
                        pushToolCallUpdateChunk(chatId, update.toolCallId.value, json, isReplay)
                    }
                }
                else -> {
                    val usage = extractUsageUpdate(update, _meta)
                    if (usage != null) {
                        recordUsageUpdate(chatId, sessionId, adapterName, usage.first, usage.second, isReplay)
                    } else if (isPlanUpdate(update, _meta)) {
                        buildStoredPlanChunk(update, _meta)?.let { recordStoredEvent(chatId, sessionId, adapterName, it, isReplay) }
                        if (!captureOnlyReplay) {
                            pushPlanChunk(chatId, update, isReplay, _meta)
                        }
                    }
                }
            }
        }

        readyQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler {
                runOnEdt {
                    injectDebugApi(browser.cefBrowser)
                    pushAdapters()
                }
                JBCefJSQuery.Response("ok")
            }
        }

        downloadAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val adapterId = parseIdOnlyPayload(payload)
                if (adapterId != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            downloadStatuses[adapterId] = "Starting download..."
                            pushAdapters()
                            
                            service.stopSharedProcess(adapterId)
                            val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterId)
                            val targetDir = File(AcpAdapterPaths.getDependenciesDir(), adapterInfo.id)
                            
                            val statusCallback = { status: String ->
                                downloadStatuses[adapterId] = status
                                pushAdapters()
                            }
                            
                            val success = AcpAdapterPaths.installAdapterRuntime(targetDir, adapterInfo, statusCallback)
                            
                            if (success) {
                                downloadStatuses.remove(adapterId)
                                pushAdapters()
                            } else {
                                downloadStatuses[adapterId] = "Error: Download failed"
                                pushAdapters()
                            }
                        } catch (e: Exception) {
                            downloadStatuses[adapterId] = "Error: ${e.message}"
                            pushAdapters()
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        deleteAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val adapterId = parseIdOnlyPayload(payload)
                if (adapterId != null) {
                    scope.launch(Dispatchers.IO) {
                        service.stopSharedProcess(adapterId)
                        AcpAdapterPaths.deleteAdapter(adapterId)
                        pushAdapters()
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        toggleAgentEnabledQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val adapterId = obj["adapterId"]?.jsonPrimitive?.content ?: ""
                    val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                    if (adapterId.isNotEmpty()) {
                        AcpAgentSettings.setEnabled(adapterId, enabled)
                        pushAdapters()
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        loginAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val adapterId = parseIdOnlyPayload(payload)
                if (adapterId != null) {
                    scope.launch(Dispatchers.Default) {
                        try {
                            AcpAuthService.incrementActive(adapterId)
                            pushAdapters() // Show loading state immediately
                            val projectPath = service.project.basePath
                            AcpAuthService.login(adapterId, projectPath) {
                                pushAdapters()
                            }
                        } finally {
                            AcpAuthService.decrementActive(adapterId)
                            pushAdapters() // Refresh UI after attempt
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        logoutAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val adapterId = parseIdOnlyPayload(payload)
                if (adapterId != null) {
                    scope.launch(Dispatchers.Default) {
                        try {
                            AcpAuthService.incrementActive(adapterId)
                            pushAdapters() // Show loading state immediately
                            AcpAuthService.logout(adapterId)
                        } finally {
                            AcpAuthService.decrementActive(adapterId)
                            pushAdapters() // Refresh UI after attempt
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        openAgentCliQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val adapterId = parseIdOnlyPayload(payload)
                if (adapterId != null) {
                    scope.launch(Dispatchers.Default) {
                        openAgentCliInTerminal(adapterId)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        openHistoryConversationCliQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val (projectPath, conversationId) = parseHistoryConversationCliPayload(payload)
                if (projectPath != null && conversationId != null) {
                    scope.launch(Dispatchers.Default) {
                        openHistoryConversationCliInTerminal(projectPath, conversationId)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        startAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val (chatId, adapterName, modelId) = parseStartPayload(payload)
                if (chatId != null) {
                    scope.launch(Dispatchers.Default) {
                        val authStatus = AcpAuthService.getAuthStatus(adapterName ?: "")
                        if (!authStatus.authenticated) {
                            pushStatus(chatId, "error")
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: Agent is not authenticated. Please login in settings.]", isReplay = false)
                            return@launch
                        }

                        pushStatus(chatId, "initializing")
                        try {
                            withTimeout(START_AGENT_TIMEOUT_MS) {
                                service.startAgent(chatId, adapterName, modelId)
                            }
                            pushStatus(chatId, service.status(chatId).name.lowercase())
                            pushSessionId(chatId, service.sessionId(chatId))
                            pushMode(chatId, service.activeModeId(chatId))
                        } catch (e: Exception) {
                            pushStatus(chatId, "error")
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: ${e.message ?: e.toString()}]", isReplay = false)
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        setModelQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val (chatId, adapterId, modelId) = parseScopedIdPayload(payload, "modelId")
                scope.launch(Dispatchers.Default) {
                    if (chatId == null || adapterId == null || modelId == null) return@launch
                    if (service.activeAdapterName(chatId) != adapterId) return@launch
                    pushStatus(chatId, "initializing")
                    try {
                        val ok = service.setModel(chatId, modelId)
                        if (!ok) {
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: Failed to set model '$modelId']", isReplay = false)
                        }
                    } finally {
                        pushStatus(chatId, service.status(chatId).name.lowercase())
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        setModeQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val (chatId, adapterId, modeId) = parseScopedIdPayload(payload, "modeId")
                scope.launch(Dispatchers.Default) {
                    if (chatId == null || adapterId == null || modeId == null) return@launch
                    if (service.activeAdapterName(chatId) != adapterId) return@launch
                    pushStatus(chatId, "initializing")
                    try {
                        val ok = service.setMode(chatId, modeId)
                        if (!ok) {
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: Failed to set mode '$modeId']", isReplay = false)
                        }
                    } finally {
                        pushStatus(chatId, service.status(chatId).name.lowercase())
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        listAdaptersQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler {
                pushAdapters()
                JBCefJSQuery.Response("ok")
            }
        }

        sendPromptQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val parsed = parseBlocksPayload(payload)
                val chatId = parsed.chatId
                val blocks = parsed.blocks
                if (chatId != null && blocks.isNotEmpty()) {
                    val job = scope.launch(Dispatchers.Default) {
                        pushStatus(chatId, "prompting")
                        val captureId = beginLivePromptCapture(chatId, parsed.rawBlocks)
                        try {
                            service.prompt(chatId, blocks).collect { event ->
                                when (event) {
                                    is AcpEvent.PromptDone -> {
                                        flushLivePromptCapture(chatId, captureId)?.let { pushAssistantMetaChunk(chatId, it) }
                                        pushStatus(chatId, "ready")
                                    }
                                    is AcpEvent.Error -> {
                                        pushContentChunk(chatId, "assistant", "text", text = "[Error: ${event.message}]", isReplay = false)
                                        appendLivePromptTextEvent(chatId, "[Error: ${event.message}]", captureId)
                                        flushLivePromptCapture(chatId, captureId)?.let { pushAssistantMetaChunk(chatId, it) }
                                    }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: ${e.message ?: e.toString()}]", isReplay = false)
                            appendLivePromptTextEvent(chatId, "[Error: ${e.message ?: e.toString()}]", captureId)
                            flushLivePromptCapture(chatId, captureId)?.let { pushAssistantMetaChunk(chatId, it) }
                            pushStatus(chatId, service.status(chatId).name.lowercase())
                        } finally {
                            promptJobs.remove(chatId)
                        }
                    }
                    promptJobs[chatId] = job
                }
                JBCefJSQuery.Response("ok")
            }
        }

        cancelPromptQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { chatIdPayload ->
                val chatId = chatIdPayload?.trim().orEmpty()
                if (chatId.isNotEmpty()) {
                    scope.launch(Dispatchers.Default) {
                        service.cancel(chatId)
                        pushContentChunk(chatId, "assistant", "text", text = "\n\n[Cancelled]\n\n", isReplay = false)
                        appendLivePromptTextEvent(chatId, "\n\n[Cancelled]\n\n")
                        flushLivePromptCapture(chatId)?.let { pushAssistantMetaChunk(chatId, it) }
                        pushStatus(chatId, "ready")
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        stopAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { chatIdPayload ->
                val chatId = chatIdPayload?.trim().orEmpty()
                if (chatId.isNotEmpty()) {
                    scope.launch(Dispatchers.Default) {
                        service.stopAgent(chatId)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
        
        respondPermissionQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                     val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                     val requestId = obj["requestId"]?.jsonPrimitive?.content ?: ""
                     val decision = obj["decision"]?.jsonPrimitive?.content ?: ""
                     if (requestId.isNotEmpty()) {
                        scope.launch(Dispatchers.Default) {
                            service.respondToPermissionRequest(requestId, decision)
                        }
                     }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        loadConversationQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                val (chatId, projectPath, conversationId) = parseConversationLoadPayload(payload)
                if (chatId != null && projectPath != null && conversationId != null) {
                    scope.launch(Dispatchers.Default) {
                        replaySeqByChatId[chatId] = 0
                        try {
                            val storedConversation = UnifiedHistoryService.loadConversationReplay(projectPath, conversationId)
                            if (storedConversation != null) {
                                replayStoredConversation(chatId, storedConversation)

                                val lastStoredSession = storedConversation.sessions.lastOrNull()
                                    ?: throw IllegalStateException("Conversation replay '$conversationId' is empty")
                                pushSessionId(chatId, lastStoredSession.sessionId)

                                scope.launch(Dispatchers.Default) {
                                    try {
                                        suppressReplayForChatIds.add(chatId)
                                        try {
                                            withTimeout(START_AGENT_TIMEOUT_MS) {
                                                service.loadSession(
                                                    chatId = chatId,
                                                    adapterName = lastStoredSession.adapterName,
                                                    sessionId = lastStoredSession.sessionId
                                                )
                                            }
                                        } finally {
                                            suppressReplayForChatIds.remove(chatId)
                                        }
                                        pushStatus(chatId, service.status(chatId).name.lowercase())
                                        pushSessionId(chatId, service.sessionId(chatId))
                                        pushMode(chatId, service.activeModeId(chatId))
                                    } catch (e: Exception) {
                                        pushStatus(chatId, "error")
                                        pushContentChunk(chatId, "assistant", "text", text = "[Error: ${e.message ?: e.toString()}]", isReplay = false)
                                    }
                                }
                            } else {
                                val sessionsChain = UnifiedHistoryService.getConversationSessions(projectPath, conversationId)
                                if (sessionsChain.isEmpty()) {
                                    throw IllegalStateException("Conversation '$conversationId' not found")
                                }
                                pushStatus(chatId, "initializing")
                                startHistoryReplayCapture(chatId, projectPath, conversationId)
                                sessionsChain.forEach { session ->
                                    beginImportedReplaySession(chatId, session.sessionId, session.adapterName, session.modelId, session.modeId)
                                    withTimeout(START_AGENT_TIMEOUT_MS) {
                                        service.loadSession(
                                            chatId,
                                            session.adapterName,
                                            session.sessionId,
                                            session.modelId,
                                            session.modeId
                                        )
                                    }
                                }
                                flushHistoryReplayCapture(chatId)
                                val refreshedConversation = UnifiedHistoryService.loadConversationReplay(projectPath, conversationId)
                                if (refreshedConversation != null) {
                                    replayStoredConversation(chatId, refreshedConversation)
                                }

                                val lastSession = sessionsChain.last()
                                pushStatus(chatId, service.status(chatId).name.lowercase())
                                pushSessionId(chatId, service.sessionId(chatId))
                                pushMode(chatId, service.activeModeId(chatId))
                            }
                        } catch (e: Exception) {
                            discardHistoryReplayCapture(chatId)
                            replaySeqByChatId.remove(chatId)
                            pushStatus(chatId, "error")
                            pushContentChunk(chatId, "assistant", "text", text = "[Error: ${e.message ?: e.toString()}]", isReplay = false)
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        undoFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val raw = payload ?: "{}"
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    val chatId = obj["chatId"]?.jsonPrimitive?.content ?: ""
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: ""
                    val status = obj["status"]?.jsonPrimitive?.content ?: "M"
                    val ops = obj["operations"]?.jsonArray?.map { opEl ->
                        val opObj = opEl.jsonObject
                        UndoOperation(
                            opObj["oldText"]?.jsonPrimitive?.content ?: "",
                            opObj["newText"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList()

                    if (chatId.isNotEmpty() && filePath.isNotEmpty()) {
                        runOnEdt {
                            val result = UndoFileHandler.undoSingleFile(service.project, filePath, status, ops)
                            pushUndoResult(chatId, result)
                        }
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        undoAllFilesQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val chatId = obj["chatId"]?.jsonPrimitive?.content ?: ""
                    val filesArr = obj["files"]?.jsonArray ?: return@addHandler JBCefJSQuery.Response("ok")
                    val files = filesArr.map { fEl ->
                        val fObj = fEl.jsonObject
                        val path = fObj["filePath"]?.jsonPrimitive?.content ?: ""
                        val st = fObj["status"]?.jsonPrimitive?.content ?: "M"
                        val ops = fObj["operations"]?.jsonArray?.map { opEl ->
                            val opObj = opEl.jsonObject
                            UndoOperation(
                                opObj["oldText"]?.jsonPrimitive?.content ?: "",
                                opObj["newText"]?.jsonPrimitive?.content ?: ""
                            )
                        } ?: emptyList()
                        Triple(path, st, ops)
                    }
                    if (chatId.isNotEmpty()) {
                        runOnEdt {
                            val result = UndoFileHandler.undoAllFiles(service.project, files)
                            pushUndoResult(chatId, result)
                        }
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        processFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: ""
                    val adapterName = obj["adapterName"]?.jsonPrimitive?.content ?: ""
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: ""
                    if (sessionId.isNotEmpty() && adapterName.isNotEmpty() && filePath.isNotEmpty()) {
                        ChangesStateService.addProcessedFile(service.project.basePath.orEmpty(), sessionId, adapterName, filePath)
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        keepAllQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: ""
                    val adapterName = obj["adapterName"]?.jsonPrimitive?.content ?: ""
                    val toolCallIndex = obj["toolCallIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    if (sessionId.isNotEmpty() && adapterName.isNotEmpty()) {
                        ChangesStateService.setBaseIndex(service.project.basePath.orEmpty(), sessionId, adapterName, toolCallIndex)
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        removeProcessedFilesQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: ""
                    val adapterName = obj["adapterName"]?.jsonPrimitive?.content ?: ""
                    val filePaths = obj["filePaths"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
                    if (sessionId.isNotEmpty() && adapterName.isNotEmpty() && filePaths.isNotEmpty()) {
                        ChangesStateService.removeProcessedFiles(service.project.basePath.orEmpty(), sessionId, adapterName, filePaths)
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        getChangesStateQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val chatId = obj["chatId"]?.jsonPrimitive?.content ?: ""
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: ""
                    val adapterName = obj["adapterName"]?.jsonPrimitive?.content ?: ""
                    if (chatId.isNotEmpty() && sessionId.isNotEmpty() && adapterName.isNotEmpty()) {
                        val state = ChangesStateService.loadState(service.project.basePath.orEmpty(), sessionId, adapterName)
                        val hasPluginEdits = state != null
                        pushChangesState(chatId, state ?: ChangesState(sessionId, adapterName), hasPluginEdits)
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        showDiffQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: ""
                    val status = obj["status"]?.jsonPrimitive?.content ?: "M"
                    val ops = obj["operations"]?.jsonArray?.map { opEl ->
                        val opObj = opEl.jsonObject
                        UndoOperation(
                            opObj["oldText"]?.jsonPrimitive?.content ?: "",
                            opObj["newText"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList()
                    if (filePath.isNotEmpty()) {
                        runOnEdt {
                            AgentDiffViewer.showAgentDiff(service.project, filePath, status, ops)
                        }
                    }
                } catch (e: Exception) {
                }
                JBCefJSQuery.Response("ok")
            }
        }

        openFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: ""
                    if (filePath.isNotEmpty()) {
                        runOnEdt {
                            try {
                                val resolved = UndoFileHandler.resolveFilePath(service.project, filePath)
                                val resolvedFile = File(resolved)
                                val base = service.project.basePath
                                val finalFile = if (resolvedFile.isAbsolute) {
                                    resolvedFile
                                } else if (!base.isNullOrBlank()) {
                                    File(base, resolved)
                                } else {
                                    resolvedFile
                                }
                                
                                val canonical = try { finalFile.canonicalPath } catch (_: Exception) { finalFile.path }
                                
                                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(canonical))
                                if (vf != null && vf.exists()) {
                                    val line = obj["line"]?.jsonPrimitive?.intOrNull ?: -1
                                    if (line >= 0) {
                                        val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(service.project, vf, line, 0)
                                        FileEditorManager.getInstance(service.project).openEditor(descriptor, true)
                                    } else {
                                        FileEditorManager.getInstance(service.project).openFile(vf, true)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                JBCefJSQuery.Response("ok")
            }
        }

        openUrlQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { url ->
                if (url != null && url.isNotBlank()) {
                    runOnEdt {
                        try {
                            com.intellij.ide.BrowserUtil.browse(url)
                        } catch (_: Exception) {}
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        attachFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { chatId ->
                val normalizedChatId = chatId?.trim().orEmpty()
                if (normalizedChatId.isNotEmpty()) {
                    runOnEdt {
                        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
                        descriptor.title = "Select Files to Attach"
                        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, service.project, null) { files ->
                            val results = files.map { file ->
                                val ioFile = File(file.path)
                                val size = ioFile.length()
                                val name = file.name
                                val mimeType = java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                                val base64 = if (size < 2 * 1024 * 1024) {
                                    try {
                                        java.util.Base64.getEncoder().encodeToString(ioFile.readBytes())
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else {
                                    null
                                }

                                val fileId = java.util.UUID.randomUUID().toString().substring(0, 8)
                                buildJsonObject {
                                    put("id", fileId)
                                    put("name", name)
                                    put("mimeType", mimeType)
                                    if (base64 != null) {
                                        put("data", base64)
                                    } else {
                                        put("path", file.path)
                                    }
                                }.toString()
                            }
                            
                            val jsonArrayStr = results.joinToString(",")
                            browser.cefBrowser.executeJavaScript(
                                "if(window.__onAttachmentsAdded) window.__onAttachmentsAdded(${jsStringLiteral(normalizedChatId)}, [$jsonArrayStr]);",
                                browser.cefBrowser.url, 0
                            )
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        updateSessionMetadataQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                        val sessionId = obj["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val adapterName = obj["adapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val promptCount = obj["promptCount"]?.jsonPrimitive?.intOrNull ?: 0
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        val touchUpdatedAt = obj["touchUpdatedAt"]?.jsonPrimitive?.booleanOrNull ?: false
                        val conversationId = obj["conversationId"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (conversationId.isNotEmpty() && sessionId.isNotEmpty() && adapterName.isNotEmpty()) {
                            UnifiedHistoryService.upsertRuntimeSessionMetadata(
                                projectPath = service.project.basePath,
                                conversationId = conversationId,
                                sessionId = sessionId,
                                adapterName = adapterName,
                                promptCount = promptCount,
                                titleCandidate = title,
                                touchUpdatedAt = touchUpdatedAt
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        continueConversationQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
                        val previousSessionId = obj["previousSessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val previousAdapterName = obj["previousAdapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val sessionId = obj["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val adapterName = obj["adapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        if (
                            previousSessionId.isNotEmpty() &&
                            previousAdapterName.isNotEmpty() &&
                            sessionId.isNotEmpty() &&
                            adapterName.isNotEmpty()
                        ) {
                            UnifiedHistoryService.appendSessionToConversation(
                                projectPath = service.project.basePath,
                                previousSessionId = previousSessionId,
                                previousAdapterName = previousAdapterName,
                                sessionId = sessionId,
                                adapterName = adapterName,
                                titleCandidate = title
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        saveConversationTranscriptQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                scope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val request = Json.decodeFromString<SaveConversationTranscriptPayload>(payload ?: "{}")
                        val filePath = UnifiedHistoryService.saveConversationTranscript(
                            projectPath = service.project.basePath,
                            conversationId = request.conversationId,
                            transcriptText = request.text
                        )
                        if (filePath.isNullOrBlank()) {
                            SaveConversationTranscriptResultPayload(
                                requestId = request.requestId,
                                conversationId = request.conversationId,
                                success = false,
                                error = "Failed to persist transcript file."
                            )
                        } else {
                            SaveConversationTranscriptResultPayload(
                                requestId = request.requestId,
                                conversationId = request.conversationId,
                                success = true,
                                filePath = filePath
                            )
                        }
                    }.getOrElse { error ->
                        val request = runCatching { Json.decodeFromString<SaveConversationTranscriptPayload>(payload ?: "{}") }.getOrNull()
                        SaveConversationTranscriptResultPayload(
                            requestId = request?.requestId.orEmpty(),
                            conversationId = request?.conversationId.orEmpty(),
                            success = false,
                            error = error.message ?: error.toString()
                        )
                    }
                    pushConversationTranscriptSaved(result)
                }
                JBCefJSQuery.Response("ok")
            }
        }

    }

    /**
     * Injects the real JS bridge: window.__startAgent, __sendPrompt, __undoFile, etc.
     * Called from the ready handler after React has called __notifyReady() and set window.__on* callbacks.
     * (injectReadySignal runs first on page load and only sets no-op stubs for __on* and __downloadAgent etc.)
     */
    fun injectDebugApi(cefBrowser: CefBrowser) {
        val startAgentInject = startAgentQuery?.inject("JSON.stringify({ chatId: chatId, adapterId: (adapterId || ''), modelId: (modelId || '') })") ?: ""
        val setModelInject = setModelQuery?.inject("JSON.stringify({ chatId: chatId, adapterId: (adapterId || ''), modelId: modelId })") ?: ""
        val setModeInject = setModeQuery?.inject("JSON.stringify({ chatId: chatId, adapterId: (adapterId || ''), modeId: modeId })") ?: ""
        val listAdaptersInject = listAdaptersQuery?.inject("") ?: ""
        val sendPromptInject = sendPromptQuery?.inject("JSON.stringify({ chatId: chatId, text: message })") ?: ""
        val cancelPromptInject = cancelPromptQuery?.inject("chatId") ?: ""
        val stopAgentInject = stopAgentQuery?.inject("chatId") ?: ""
        val respondPermissionInject = respondPermissionQuery?.inject("JSON.stringify({ requestId: requestId, decision: decision })") ?: ""
        val loadConversationInject = loadConversationQuery?.inject("JSON.stringify({ chatId: chatId, projectPath: (projectPath || ''), conversationId: (conversationId || '') })") ?: ""
        val downloadAgentInject = downloadAgentQuery?.inject("adapterId") ?: ""
        val deleteAgentInject = deleteAgentQuery?.inject("adapterId") ?: ""
        val toggleAgentEnabledInject = toggleAgentEnabledQuery?.inject("JSON.stringify({ adapterId: adapterId, enabled: enabled })") ?: ""
        val loginAgentInject = loginAgentQuery?.inject("adapterId") ?: ""
        val logoutAgentInject = logoutAgentQuery?.inject("adapterId") ?: ""
        val openAgentCliInject = openAgentCliQuery?.inject("adapterId") ?: ""
        val openHistoryConversationCliInject = openHistoryConversationCliQuery?.inject("JSON.stringify(payload)") ?: ""
        val undoFileInject = undoFileQuery?.inject("payload") ?: ""
        val undoAllFilesInject = undoAllFilesQuery?.inject("payload") ?: ""
        val processFileInject = processFileQuery?.inject("payload") ?: ""
        val keepAllInject = keepAllQuery?.inject("payload") ?: ""
        val removeProcessedFilesInject = removeProcessedFilesQuery?.inject("payload") ?: ""
        val getChangesStateInject = getChangesStateQuery?.inject("payload") ?: ""
        val showDiffInject = showDiffQuery?.inject("payload") ?: ""
        val openFileInject = openFileQuery?.inject("payload") ?: ""
        val openUrlInject = openUrlQuery?.inject("url") ?: ""
        val attachFileInject = attachFileQuery?.inject("chatId") ?: ""
        val updateSessionMetadataInject = updateSessionMetadataQuery?.inject("JSON.stringify(payload)") ?: ""
        val continueConversationInject = continueConversationQuery?.inject("JSON.stringify(payload)") ?: ""
        val saveConversationTranscriptInject = saveConversationTranscriptQuery?.inject("payload") ?: ""

        val script = """
            (function() {
                window.__requestAdapters = function() {
                    try { $listAdaptersInject } catch (e) { }
                };
                window.__startAgent = function(chatId, adapterId, modelId) {
                    try {
                        if (window.__onStatus) window.__onStatus(chatId, 'initializing');
                        $startAgentInject
                    } catch (e) { }
                };
                window.__setModel = function(chatId, adapterId, modelId) {
                    try { $setModelInject } catch (e) { }
                };
                window.__setMode = function(chatId, adapterId, modeId) {
                    try { $setModeInject } catch (e) { }
                };
                window.__sendPrompt = function(chatId, message) {
                    try {
                        if (window.__onStatus) window.__onStatus(chatId, 'prompting');
                        $sendPromptInject
                    } catch (e) { }
                };
                window.__cancelPrompt = function(chatId) {
                    try { $cancelPromptInject } catch (e) { }
                };
                window.__stopAgent = function(chatId) {
                    try { $stopAgentInject } catch (e) { }
                };
                window.__respondPermission = function(requestId, decision) {
                    try { $respondPermissionInject } catch (e) { }
                };
                window.__loadHistoryConversation = function(chatId, projectPath, conversationId) {
                    try { $loadConversationInject } catch (e) { }
                };
                window.__downloadAgent = function(adapterId) {
                    try { $downloadAgentInject } catch (e) { }
                };
                window.__deleteAgent = function(adapterId) {
                    try { $deleteAgentInject } catch (e) { }
                };
                window.__toggleAgentEnabled = function(adapterId, enabled) {
                    try { $toggleAgentEnabledInject } catch (e) { }
                };
                window.__loginAgent = function(adapterId) {
                    try { $loginAgentInject } catch (e) { }
                };
                window.__logoutAgent = function(adapterId) {
                    try { $logoutAgentInject } catch (e) { }
                };
                window.__openAgentCli = function(adapterId) {
                    try { $openAgentCliInject } catch (e) { }
                };
                window.__openHistoryConversationCli = function(payload) {
                    try { $openHistoryConversationCliInject } catch (e) { }
                };
                window.__undoFile = function(payload) {
                    try { $undoFileInject } catch (e) { }
                };
                window.__undoAllFiles = function(payload) {
                    try { $undoAllFilesInject } catch (e) { }
                };
                window.__processFile = function(payload) {
                    try { $processFileInject } catch (e) { }
                };
                window.__keepAll = function(payload) {
                    try { $keepAllInject } catch (e) { }
                };
                window.__removeProcessedFiles = function(payload) {
                    try { $removeProcessedFilesInject } catch (e) { }
                };
                window.__getChangesState = function(payload) {
                    try { $getChangesStateInject } catch (e) { }
                };
                window.__showDiff = function(payload) {
                    try { $showDiffInject } catch (e) { }
                };
                window.__openFile = function(payload) {
                    try { $openFileInject } catch (e) { }
                };
                window.__openUrl = function(url) {
                    try { $openUrlInject } catch (e) { }
                };
                window.__attachFile = function(chatId) {
                    try { $attachFileInject } catch (e) { }
                };
                window.__updateSessionMetadata = function(payload) {
                    try { $updateSessionMetadataInject } catch (e) { }
                };
                window.__continueConversationWithSession = function(payload) {
                    try { $continueConversationInject } catch (e) { }
                };
                window.__saveConversationTranscript = function(payload) {
                    try { $saveConversationTranscriptInject } catch (e) { }
                };

                // Try prime
                try { window.__requestAdapters(); } catch (e) {}
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    /**
     * Injected on first page load (onLoadEnd). Registers no-op stubs for all __on* and action callbacks
     * so the page does not break before React mounts. When React is ready it calls __notifyReady(),
     * which triggers injectDebugApi() to replace these with the real implementations.
     */
    fun injectReadySignal(cefBrowser: CefBrowser) {
        val readyInject = readyQuery?.inject("") ?: ""
        val script = """
            // No-op stubs until injectDebugApi runs after __notifyReady()
            window.__onAcpLog = window.__onAcpLog || function(payload) {};
            window.__onContentChunk = window.__onContentChunk || function(payload) {};
            window.__onStatus = window.__onStatus || function(chatId, status) {};
            window.__onSessionId = window.__onSessionId || function(chatId, id) {};
            window.__onAdapters = window.__onAdapters || function(adapters) {};
            window.__onMode = window.__onMode || function(chatId, modeId) {};
            window.__onPermissionRequest = window.__onPermissionRequest || function(request) {};
            window.__respondPermission = window.__respondPermission || function(requestId, decision) {};
            window.__stopAgent = window.__stopAgent || function(chatId) {};
            window.__onToolCall = window.__onToolCall || function(chatId, payload) {};
            window.__onToolCallUpdate = window.__onToolCallUpdate || function(chatId, payload) {};
            window.__onPlan = window.__onPlan || function(chatId, payload) {};
            window.__onUndoResult = window.__onUndoResult || function(chatId, result) {};
            window.__onChangesState = window.__onChangesState || function(chatId, state) {};
            window.__onConversationTranscriptSaved = window.__onConversationTranscriptSaved || function(payload) {};

            window.__notifyReady = function() {
                try { $readyInject } catch (e) { }
            };
            window.__downloadAgent = window.__downloadAgent || function(id) {};
            window.__deleteAgent = window.__deleteAgent || function(id) {};
            window.__toggleAgentEnabled = window.__toggleAgentEnabled || function(id, e) {};
            window.__loginAgent = window.__loginAgent || function(id) {};
            window.__logoutAgent = window.__logoutAgent || function(id) {};
            window.__openAgentCli = window.__openAgentCli || function(id) {};
            window.__openHistoryConversationCli = window.__openHistoryConversationCli || function(payload) {};
            window.__attachFile = window.__attachFile || function(chatId) {};
            window.__updateSessionMetadata = window.__updateSessionMetadata || function(payload) {};
            window.__continueConversationWithSession = window.__continueConversationWithSession || function(payload) {};
            window.__saveConversationTranscript = window.__saveConversationTranscript || function(payload) {};
            window.__loadHistoryConversation = window.__loadHistoryConversation || function(chatId, projectPath, conversationId) {};
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun openAgentCliInTerminal(adapterId: String) {
        val (adapterInfo, command) = buildCliCommand(adapterId, emptyList()) ?: return
        if (command.isBlank()) return

        val adapterRoot = File(AcpAdapterPaths.getDownloadPath(adapterId))
        val workingDir = service.project.basePath ?: adapterRoot.absolutePath
        openInIdeTerminal(workingDir, "${adapterInfo.name} CLI", command)
    }

    private fun openHistoryConversationCliInTerminal(projectPath: String, conversationId: String) {
        val latestSession = runBlocking {
            UnifiedHistoryService.getConversationSessions(projectPath, conversationId)
                .maxByOrNull { it.updatedAt }
        } ?: return

        val adapterInfo = runCatching { AcpAdapterConfig.getAdapterInfo(latestSession.adapterName) }.getOrNull() ?: return
        val resumeArgs = adapterInfo.cli?.resumeArgs.orEmpty()
        if (resumeArgs.isEmpty()) return

        val placeholders = mapOf(
            "sessionId" to latestSession.sessionId,
            "conversationId" to conversationId,
            "projectPath" to projectPath,
            "adapterId" to latestSession.adapterName
        )
        val (_, command) = buildCliCommand(latestSession.adapterName, applyCliPlaceholders(resumeArgs, placeholders)) ?: return
        if (command.isBlank()) return

        val workingDir = service.project.basePath ?: projectPath
        openInIdeTerminal(workingDir, "${adapterInfo.name} CLI", command)
    }

    private fun buildCliCommand(adapterId: String, extraArgs: List<String>): Pair<AcpAdapterConfig.AdapterInfo, String>? {
        val adapterInfo = runCatching { AcpAdapterConfig.getAdapterInfo(adapterId) }.getOrNull() ?: return null
        val cli = adapterInfo.cli ?: return null
        val adapterRoot = File(AcpAdapterPaths.getDownloadPath(adapterId))
        if (!adapterRoot.isDirectory) return null

        val os = System.getProperty("os.name").lowercase()
        val executable = if (os.contains("win")) cli.executable.win else cli.executable.unix
        val entryPath = cli.entryPath?.takeIf { it.isNotBlank() }
        if (executable.isNullOrBlank()) return null

        val commandParts = mutableListOf<String>()
        commandParts += resolveCliPath(adapterRoot, executable)
        if (entryPath != null) {
            commandParts += resolveCliPath(adapterRoot, entryPath)
        }
        commandParts += cli.args
        commandParts += extraArgs

        return adapterInfo to toShellCommand(commandParts)
    }

    private fun isIdeTerminalAvailable(): Boolean {
        return loadIdeTerminalManagerClass() != null
    }

    private fun openInIdeTerminal(workingDir: String, title: String, command: String): Boolean {
        val managerClass = loadIdeTerminalManagerClass() ?: return false
        return runCatching {
            runOnEdt {
                val getInstance = managerClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                val terminalManager = getInstance.invoke(null, service.project) ?: return@runOnEdt
                val createShellWidget = managerClass.getMethod(
                    "createShellWidget",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                createShellWidget.isAccessible = true
                val widget = createShellWidget.invoke(terminalManager, workingDir, title, true, true) ?: return@runOnEdt
                val sendCommand = widget.javaClass.methods.firstOrNull { method ->
                    method.name == "sendCommandToExecute" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == String::class.java
                } ?: return@runOnEdt
                sendCommand.isAccessible = true
                sendCommand.invoke(widget, command)
            }
            true
        }.getOrElse {
            logger.warn("Failed to open IDE terminal", it)
            false
        }
    }

    private fun loadIdeTerminalManagerClass(): Class<*>? {
        val className = "org.jetbrains.plugins.terminal.TerminalToolWindowManager"
        val terminalPluginId = PluginId.getId("org.jetbrains.plugins.terminal")
        val pluginDescriptor = PluginManagerCore.getPlugin(terminalPluginId)
        val pluginClassLoader = runCatching {
            pluginDescriptor
                ?.javaClass
                ?.methods
                ?.firstOrNull { it.name == "getPluginClassLoader" && it.parameterCount == 0 }
                ?.invoke(pluginDescriptor) as? ClassLoader
        }.getOrNull()

        val classLoaders = listOfNotNull(
            pluginClassLoader,
            javaClass.classLoader,
            service.project.javaClass.classLoader,
            ApplicationManager::class.java.classLoader,
            Thread.currentThread().contextClassLoader
        ).distinct()

        classLoaders.forEach { classLoader ->
            val loaded = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (loaded != null) {
                return loaded
            }
        }

        return null
    }

    private fun applyCliPlaceholders(values: List<String>, placeholders: Map<String, String>): List<String> {
        return values.map { value ->
            placeholders.entries.fold(value) { acc, (key, replacement) ->
                acc.replace("{$key}", replacement)
            }
        }
    }

    private fun resolveCliPath(adapterRoot: File, raw: String): String {
        val path = raw.trim()
        if (path.isEmpty()) return path
        val file = File(path)
        if (file.isAbsolute) return file.absolutePath
        val relative = File(adapterRoot, path.replace("/", File.separator).replace("\\", File.separator))
        return if (relative.exists()) relative.absolutePath else path
    }

    private fun toShellCommand(parts: List<String>): String {
        val filtered = parts.filter { it.isNotBlank() }
        if (filtered.isEmpty()) return ""

        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            val executable = filtered.first()
            val args = filtered.drop(1).joinToString(" ") { quoteShellArg(it) }
            buildString {
                append("& ")
                append(quoteShellArg(executable))
                if (args.isNotBlank()) {
                    append(" ")
                    append(args)
                }
            }
        } else {
            filtered.joinToString(" ") { quoteShellArg(it) }
        }
    }

    private fun quoteShellArg(value: String): String {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            "'" + value.replace("'", "''") + "'"
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
        }
    }
    fun pushLogEntry(entry: AcpLogEntry) {
        val payload = """{"direction":"${entry.direction}","json":${escapeJsonString(entry.json)},"timestamp":${entry.timestampMillis}}"""
        val escaped = payload.escapeForJsString()
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onAcpLog) window.__onAcpLog(JSON.parse('$escaped'));",
                browser.cefBrowser.url, 0
            )
        }
    }

    /**
     * Unified content delivery: ALL content (live streaming + history replay) goes
     * through this single method ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ single __onContentChunk JS callback.
     * The frontend processes every chunk with one and the same code path.
     */
    fun pushContentChunk(chatId: String, role: String, type: String, text: String? = null, data: String? = null, mimeType: String? = null, isReplay: Boolean = false) {
        val replaySeq = nextReplaySeq(chatId, isReplay)
        val parts = mutableListOf<String>()
        parts.add("\"chatId\":${escapeJsonString(chatId)}")
        parts.add("\"role\":${escapeJsonString(role)}")
        parts.add("\"type\":${escapeJsonString(type)}")
        if (text != null) parts.add("\"text\":${escapeJsonString(text)}")
        if (data != null) parts.add("\"data\":${escapeJsonString(data)}")
        if (mimeType != null) parts.add("\"mimeType\":${escapeJsonString(mimeType)}")
        parts.add("\"isReplay\":$isReplay")
        if (replaySeq != null) parts.add("\"replaySeq\":$replaySeq")
        val json = "{${parts.joinToString(",")}}"
        dispatchContentChunkJson(json)
    }

    /** Convenience: send a ContentBlock from the ACP SDK through the unified pipeline. */
    private fun pushContentBlock(chatId: String, role: String, content: ContentBlock, isThought: Boolean, isReplay: Boolean) {
        val serialized = serializeContentBlock(content, if (isThought) "thinking" else "text") ?: return
        pushContentChunk(
            chatId = chatId,
            role = role,
            type = serialized.type,
            text = serialized.text,
            data = serialized.data,
            mimeType = serialized.mimeType,
            isReplay = isReplay
        )
    }

    fun pushToolCallChunk(chatId: String, rawJson: String, isReplay: Boolean = false) {
        val replaySeq = nextReplaySeq(chatId, isReplay)
        val parsed = try { Json.parseToJsonElement(rawJson).jsonObject } catch (_: Exception) { null }
        val toolCallId = parsed?.get("toolCallId")?.jsonPrimitive?.contentOrNull ?: ""
        val kind = parsed?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
        val title = parsed?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
        val status = parsed?.get("status")?.jsonPrimitive?.contentOrNull ?: ""

        val parts = mutableListOf<String>()
        parts.add("\"chatId\":${escapeJsonString(chatId)}")
        parts.add("\"role\":\"assistant\"")
        parts.add("\"type\":\"tool_call\"")
        parts.add("\"isReplay\":$isReplay")
        parts.add("\"toolCallId\":${escapeJsonString(toolCallId)}")
        parts.add("\"toolKind\":${escapeJsonString(kind)}")
        parts.add("\"toolTitle\":${escapeJsonString(title)}")
        parts.add("\"toolStatus\":${escapeJsonString(status)}")
        parts.add("\"toolRawJson\":${escapeJsonString(rawJson)}")
        if (replaySeq != null) parts.add("\"replaySeq\":$replaySeq")
        val json = "{${parts.joinToString(",")}}"
        dispatchContentChunkJson(json)
    }

    fun pushToolCallUpdateChunk(chatId: String, toolCallId: String, rawJson: String, isReplay: Boolean = false) {
        val replaySeq = nextReplaySeq(chatId, isReplay)
        val parts = mutableListOf<String>()
        parts.add("\"chatId\":${escapeJsonString(chatId)}")
        parts.add("\"role\":\"assistant\"")
        parts.add("\"type\":\"tool_call_update\"")
        parts.add("\"isReplay\":$isReplay")
        parts.add("\"toolCallId\":${escapeJsonString(toolCallId)}")
        parts.add("\"toolRawJson\":${escapeJsonString(rawJson)}")
        if (replaySeq != null) parts.add("\"replaySeq\":$replaySeq")
        val json = "{${parts.joinToString(",")}}"
        dispatchContentChunkJson(json)
    }


    private fun recordUsageUpdate(
        chatId: String,
        sessionId: String,
        adapterName: String,
        used: Long?,
        size: Long?,
        isReplay: Boolean
    ) {
        if (isReplay) {
            val capture = historyReplayCaptures[chatId] ?: return
            if (sessionId.isBlank() || adapterName.isBlank()) return
            val session = getOrCreateReplaySession(capture, sessionId, adapterName)
            val prompt = getOrCreateReplayPrompt(session, startNewIfNeeded = false)
            val current = prompt.assistantMeta ?: buildAssistantMetadata(
                adapterName = adapterName,
                modelId = capture.currentModelId,
                modeId = capture.currentModeId
            )
            prompt.assistantMeta = current?.copy(
                contextTokensUsed = used ?: current.contextTokensUsed,
                contextWindowSize = size ?: current.contextWindowSize
            )
            return
        }

        val capture = livePromptCaptures[chatId] ?: return
        if (used != null) capture.contextTokensUsed = used
        if (size != null) capture.contextWindowSize = size
    }

    private fun extractUsageUpdate(update: SessionUpdate, meta: JsonElement?): Pair<Long?, Long?>? {
        val updateObj = when {
            meta is JsonObject -> meta["update"]?.jsonObject ?: meta
            else -> try {
                Json.parseToJsonElement(Json.encodeToString(update)).jsonObject
            } catch (_: Exception) {
                null
            }
        } ?: return null

        if (updateObj["sessionUpdate"]?.jsonPrimitive?.contentOrNull != "usage_update") {
            return null
        }

        val used = updateObj["used"]?.jsonPrimitive?.longOrNull
        val size = updateObj["size"]?.jsonPrimitive?.longOrNull
        return used to size
    }

    private fun isPlanUpdate(update: SessionUpdate, _meta: JsonElement?): Boolean {
        // Check via raw JSON-RPC metadata (available during replay)
        if (_meta is JsonObject) {
            val updateObj = _meta["update"]?.jsonObject ?: _meta
            if (updateObj["sessionUpdate"]?.jsonPrimitive?.contentOrNull == "plan") return true
        }
        // Check via serialized update object (available during prompting)
        return try {
            val parsed = Json.parseToJsonElement(Json.encodeToString(update)).jsonObject
            parsed["sessionUpdate"]?.jsonPrimitive?.contentOrNull == "plan"
        } catch (_: Exception) { false }
    }

    private fun extractPlanEntries(plan: SessionUpdate, _meta: JsonElement?): JsonArray? {
        // 1. Try _meta (raw JSON-RPC params, available during replay)
        if (_meta is JsonObject) {
            val updateObj = _meta["update"]?.jsonObject ?: _meta
            updateObj["entries"]?.jsonArray?.let { return it }
        }
        // 2. Fallback: serialize the plan object (during prompting, _meta is null)
        return try {
            Json.parseToJsonElement(Json.encodeToString(plan)).jsonObject["entries"]?.jsonArray
        } catch (_: Exception) { null }
    }

    fun pushPlanChunk(chatId: String, plan: SessionUpdate, isReplay: Boolean = false, _meta: JsonElement? = null) {
        val replaySeq = nextReplaySeq(chatId, isReplay)
        val entries = try {
            extractPlanEntries(plan, _meta)
        } catch (e: Exception) {
            null
        }

        if (entries == null || entries.isEmpty()) {
            return
        }

        val chunk = buildJsonObject {
            put("chatId", chatId)
            put("role", "assistant")
            put("type", "plan")
            put("isReplay", isReplay)
            if (replaySeq != null) put("replaySeq", replaySeq)
            put("planEntries", entries)
        }

        val json = chunk.toString()
        dispatchContentChunkJson(json)
    }

    private fun nextReplaySeq(chatId: String, isReplay: Boolean): Int? {
        if (!isReplay) return null
        return replaySeqByChatId.compute(chatId) { _, prev -> (prev ?: 0) + 1 }
    }

    private fun dispatchContentChunkJson(json: String) {
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


    fun pushStatus(chatId: String, status: String) {
        val previousStatus = lastStatusByChatId.put(chatId, status)
        val escapedStatus = jsStringLiteral(status)
        val escapedChatId = jsStringLiteral(chatId)
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onStatus) window.__onStatus($escapedChatId, $escapedStatus);",
                browser.cefBrowser.url, 0
            )
        }
        if (previousStatus == "prompting" && status == "ready") {
            playResponseCompleteSound()
        }
    }

    fun pushMode(chatId: String, modeId: String?) {
        if (modeId == null) return
        val escapedModeId = jsStringLiteral(modeId)
        val escapedChatId = jsStringLiteral(chatId)
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onMode) window.__onMode($escapedChatId, $escapedModeId);",
                browser.cefBrowser.url, 0
            )
        }
    }

    fun pushSessionId(chatId: String, sid: String?) {
        if (sid == null) return
        val escapedSessionId = jsStringLiteral(sid)
        val escapedChatId = jsStringLiteral(chatId)
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onSessionId) window.__onSessionId($escapedChatId, $escapedSessionId);",
                browser.cefBrowser.url, 0
            )
        }
    }

    fun pushPermissionRequest(request: PermissionRequest) {
        val requestIdLiteral = jsStringLiteral(request.requestId)
        val chatIdLiteral = jsStringLiteral(request.chatId)
        val titleLiteral = jsStringLiteral(request.title)
        val optionsJson = request.options.joinToString(",") { opt ->
            "{optionId: ${jsStringLiteral(opt.optionId.value)}, label: ${jsStringLiteral(opt.name)}}"
        }
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onPermissionRequest) window.__onPermissionRequest({ requestId: $requestIdLiteral, chatId: $chatIdLiteral, title: $titleLiteral, options: [$optionsJson] });",
                browser.cefBrowser.url, 0
            )
        }
        playPermissionRequestSound()
    }

    private fun playResponseCompleteSound() {
        playSound("/sounds/notification.wav")
    }

    private fun playPermissionRequestSound() {
        playSound("/sounds/request.wav")
    }

    private fun playSound(resourcePath: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val resourceStream = AcpBridge::class.java.getResourceAsStream(resourcePath)
                if (resourceStream == null) {
                    return@launch
                }
                BufferedInputStream(resourceStream).use { bufferedStream ->
                    AudioSystem.getAudioInputStream(bufferedStream).use { sourceStream ->
                        val sourceFormat = sourceStream.format
                        val decodedFormat = AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sourceFormat.sampleRate,
                            16,
                            sourceFormat.channels,
                            sourceFormat.channels * 2,
                            sourceFormat.sampleRate,
                            false
                        )

                        val playableStream =
                            if (sourceFormat.encoding == AudioFormat.Encoding.PCM_SIGNED && sourceFormat.sampleSizeInBits == 16) {
                                sourceStream
                            } else {
                                AudioSystem.getAudioInputStream(decodedFormat, sourceStream)
                            }

                        playableStream.use { audioStream ->
                            val clip = AudioSystem.getClip()
                            clip.addLineListener { event ->
                                if (event.type == LineEvent.Type.STOP) {
                                    clip.close()
                                }
                            }
                            clip.open(audioStream)
                            clip.start()
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    fun pushUndoResult(chatId: String, result: unified.llm.changes.UndoResult) {
        val successStr = if (result.success) "true" else "false"
        val messageLiteral = jsStringLiteral(result.message)
        val chatIdLiteral = jsStringLiteral(chatId)
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onUndoResult) window.__onUndoResult($chatIdLiteral, {success:$successStr,message:$messageLiteral});",
                browser.cefBrowser.url, 0
            )
        }
    }

    private fun pushConversationTranscriptSaved(result: SaveConversationTranscriptResultPayload) {
        val payloadJson = adapterJson.encodeToString(result)
        val escaped = payloadJson.escapeForJsString()
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onConversationTranscriptSaved) window.__onConversationTranscriptSaved(JSON.parse('$escaped'));",
                browser.cefBrowser.url, 0
            )
        }
    }

    fun pushChangesState(chatId: String, state: ChangesState, hasPluginEdits: Boolean) {
        val hasPluginEditsStr = if (hasPluginEdits) "true" else "false"
        val processedJson = state.processedFiles.joinToString(",") { escapeJsonString(it) }
        val payload = """{"sessionId":${escapeJsonString(state.sessionId)},"adapterName":${escapeJsonString(state.adapterName)},"baseToolCallIndex":${state.baseToolCallIndex},"processedFiles":[$processedJson],"hasPluginEdits":$hasPluginEditsStr}"""
        val chatIdLiteral = jsStringLiteral(chatId)
        val escaped = payload.escapeForJsString()
        runOnEdt {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onChangesState) window.__onChangesState($chatIdLiteral, JSON.parse('$escaped'));",
                browser.cefBrowser.url, 0
            )
        }
    }

    /**
     * When the agent modifies files in a live (non-replay) tool call, remove those paths from
     * processedFiles so they show again in Edits. Only called when isReplay == false.
     */
    private fun removeProcessedFilesForDiffs(chatId: String, content: List<ToolCallContent>?) {
        val sessionId = service.sessionId(chatId) ?: return
        val adNameValue = service.activeAdapterName(chatId) ?: return
        val diffs = content?.filterIsInstance<ToolCallContent.Diff>() ?: return
        if (diffs.isEmpty()) return

        // First live diff from this plugin should persist state file and mark Edits panel as plugin-owned.
        val projectPath = service.project.basePath.orEmpty()
        if (!ChangesStateService.hasState(projectPath, sessionId, adNameValue)) {
            ChangesStateService.ensureState(projectPath, sessionId, adNameValue)
        }

        val paths = diffs.map { it.path }
        ChangesStateService.removeProcessedFiles(projectPath, sessionId, adNameValue, paths)
        val state = ChangesStateService.loadState(projectPath, sessionId, adNameValue) ?: ChangesStateService.ensureState(projectPath, sessionId, adNameValue)
        pushChangesState(chatId, state, true)
    }

    private fun beginLivePromptCapture(chatId: String, blocks: List<JsonObject>): String? {
        val projectPath = service.project.basePath.orEmpty()
        val sessionId = service.sessionId(chatId).orEmpty()
        val adapterName = service.activeAdapterName(chatId).orEmpty()
        if (projectPath.isBlank() || sessionId.isBlank() || adapterName.isBlank()) return null
        val captureId = "prompt-${System.nanoTime()}"
        livePromptCaptures[chatId] = LivePromptCapture(
            captureId = captureId,
            projectPath = projectPath,
            conversationId = chatId,
            sessionId = sessionId,
            adapterName = adapterName,
            blocks = blocks,
            startedAtMillis = System.currentTimeMillis(),
            assistantMeta = buildAssistantMetadata(
                adapterName = adapterName,
                modelId = service.activeModelId(chatId),
                modeId = service.activeModeId(chatId)
            )
        )
        return captureId
    }

    private fun appendLivePromptTextEvent(chatId: String, text: String, expectedCaptureId: String? = null) {
        val capture = livePromptCaptures[chatId] ?: return
        if (expectedCaptureId != null && capture.captureId != expectedCaptureId) return
        capture.events.add(buildStoredContentChunk("assistant", "text", text = text))
    }

    private fun flushLivePromptCapture(chatId: String, expectedCaptureId: String? = null): ConversationAssistantMetadata? {
        val capture = livePromptCaptures[chatId] ?: return null
        if (expectedCaptureId != null && capture.captureId != expectedCaptureId) return null
        livePromptCaptures.remove(chatId)
        if (capture.blocks.isEmpty() && capture.events.isEmpty()) return null
        val durationSeconds = ((System.currentTimeMillis() - capture.startedAtMillis).coerceAtLeast(0L)) / 1000.0
        val assistantMeta = capture.assistantMeta?.copy(
            promptStartedAtMillis = capture.startedAtMillis,
            durationSeconds = durationSeconds,
            contextTokensUsed = capture.contextTokensUsed,
            contextWindowSize = capture.contextWindowSize
        ) ?: buildAssistantMetadata(
            adapterName = capture.adapterName,
            promptStartedAtMillis = capture.startedAtMillis,
            durationSeconds = durationSeconds,
            contextTokensUsed = capture.contextTokensUsed,
            contextWindowSize = capture.contextWindowSize
        )
        UnifiedHistoryService.appendConversationPrompt(
            projectPath = capture.projectPath,
            conversationId = capture.conversationId,
            sessionId = capture.sessionId,
            adapterName = capture.adapterName,
            blocks = capture.blocks,
            events = capture.events,
            assistantMeta = assistantMeta
        )
        return assistantMeta
    }

    private fun startHistoryReplayCapture(
        chatId: String,
        projectPath: String,
        conversationId: String
    ) {
        if (projectPath.isBlank() || conversationId.isBlank()) return
        historyReplayCaptures[chatId] = HistoryReplayCapture(
            projectPath = projectPath,
            conversationId = conversationId
        )
    }

    private fun beginImportedReplaySession(
        chatId: String,
        sessionId: String,
        adapterName: String,
        modelId: String?,
        modeId: String?
    ) {
        val capture = historyReplayCaptures[chatId] ?: return
        capture.currentSessionId = sessionId.takeIf { it.isNotBlank() }
        capture.currentAdapterName = adapterName.takeIf { it.isNotBlank() }
        capture.currentModelId = modelId?.takeIf { it.isNotBlank() }
        capture.currentModeId = modeId?.takeIf { it.isNotBlank() }
    }

    private fun discardHistoryReplayCapture(chatId: String) {
        historyReplayCaptures.remove(chatId)
    }

    private fun flushHistoryReplayCapture(chatId: String) {
        val capture = historyReplayCaptures.remove(chatId) ?: return
        val sessions = capture.sessions
            .filter { it.prompts.isNotEmpty() }
            .map { session ->
                unified.llm.history.ConversationSessionReplayEntry(
                    sessionId = session.sessionId,
                    adapterName = session.adapterName,
                    prompts = session.prompts.map { prompt ->
                        unified.llm.history.ConversationPromptReplayEntry(
                            blocks = prompt.blocks,
                            events = prompt.events,
                            assistantMeta = prompt.assistantMeta
                        )
                    }
                )
            }
        if (sessions.isEmpty()) return
        UnifiedHistoryService.saveConversationReplay(
            projectPath = capture.projectPath,
            conversationId = capture.conversationId,
            data = unified.llm.history.ConversationReplayData(sessions = sessions)
        )
    }

    private fun recordReplayUserBlock(chatId: String, sessionId: String, adapterName: String, content: ContentBlock) {
        val capture = historyReplayCaptures[chatId] ?: return
        if (sessionId.isBlank() || adapterName.isBlank()) return
        val block = storedPromptBlockFromContentBlock(content) ?: return
        val session = getOrCreateReplaySession(capture, sessionId, adapterName)
        val prompt = getOrCreateReplayPrompt(session, startNewIfNeeded = true)
        prompt.blocks.add(block)
    }

    private fun recordContentBlock(
        chatId: String,
        sessionId: String,
        adapterName: String,
        role: String,
        content: ContentBlock,
        isThought: Boolean,
        isReplay: Boolean
    ) {
        val stored = storedEventFromContentBlock(role, content, isThought) ?: return
        recordStoredEvent(chatId, sessionId, adapterName, stored, isReplay)
    }

    private fun recordStoredEvent(
        chatId: String,
        sessionId: String,
        adapterName: String,
        event: JsonObject,
        isReplay: Boolean
    ) {
        if (isReplay) {
            val capture = historyReplayCaptures[chatId] ?: return
            if (sessionId.isBlank() || adapterName.isBlank()) return
            val session = getOrCreateReplaySession(capture, sessionId, adapterName)
            val prompt = getOrCreateReplayPrompt(session, startNewIfNeeded = false)
            val role = event["role"]?.jsonPrimitive?.contentOrNull
            if (role == "assistant" && prompt.assistantMeta == null) {
                prompt.assistantMeta = buildAssistantMetadata(
                    adapterName = adapterName,
                    modelId = capture.currentModelId,
                    modeId = capture.currentModeId
                )
            }
            prompt.events.add(event)
            return
        }

        val capture = livePromptCaptures[chatId] ?: return
        capture.events.add(event)
    }

    private fun getOrCreateReplaySession(
        capture: HistoryReplayCapture,
        sessionId: String,
        adapterName: String
    ): ReplaySessionCapture {
        val existing = capture.sessions.firstOrNull {
            it.sessionId == sessionId && it.adapterName == adapterName
        }
        if (existing != null) return existing
        return ReplaySessionCapture(sessionId = sessionId, adapterName = adapterName).also {
            capture.sessions.add(it)
        }
    }

    private fun getOrCreateReplayPrompt(
        session: ReplaySessionCapture,
        startNewIfNeeded: Boolean
    ): ReplayPromptCapture {
        val current = session.prompts.lastOrNull()
        if (current == null) {
            return ReplayPromptCapture().also { session.prompts.add(it) }
        }
        if (startNewIfNeeded && current.events.isNotEmpty()) {
            return ReplayPromptCapture().also { session.prompts.add(it) }
        }
        return current
    }

    private fun storedPromptBlockFromContentBlock(content: ContentBlock): JsonObject? {
        val serialized = serializeContentBlock(content) ?: return null
        return buildJsonObject {
            put("type", serialized.type)
            serialized.text?.let { put("text", it) }
            serialized.data?.let { put("data", it) }
            serialized.mimeType?.let { put("mimeType", it) }
        }
    }

    private fun storedEventFromContentBlock(role: String, content: ContentBlock, isThought: Boolean): JsonObject? {
        val serialized = serializeContentBlock(content, if (isThought) "thinking" else "text") ?: return null
        return buildStoredContentChunk(
            role = role,
            type = serialized.type,
            text = serialized.text,
            data = serialized.data,
            mimeType = serialized.mimeType
        )
    }

    private fun buildStoredContentChunk(
        role: String,
        type: String,
        text: String? = null,
        data: String? = null,
        mimeType: String? = null
    ): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("type", type)
            if (text != null) put("text", text)
            if (data != null) put("data", data)
            if (mimeType != null) put("mimeType", mimeType)
        }
    }

    private fun buildAssistantMetadata(
        adapterName: String,
        modelId: String? = null,
        modeId: String? = null,
        promptStartedAtMillis: Long? = null,
        durationSeconds: Double? = null,
        contextTokensUsed: Long? = null,
        contextWindowSize: Long? = null
    ): ConversationAssistantMetadata? {
        val cleanAdapterName = adapterName.trim()
        if (cleanAdapterName.isBlank()) return null

        val adapterInfo = runCatching { AcpAdapterPaths.getAdapterInfo(cleanAdapterName) }.getOrNull()
        val cleanModelId = modelId?.trim()?.takeIf { it.isNotBlank() }
        val cleanModeId = modeId?.trim()?.takeIf { it.isNotBlank() }

        return ConversationAssistantMetadata(
            agentId = cleanAdapterName,
            agentName = adapterInfo?.name ?: cleanAdapterName,
            modelId = cleanModelId,
            modelName = cleanModelId?.let { model ->
                adapterInfo?.models?.firstOrNull { it.modelId == model }?.name ?: model
            },
            modeId = cleanModeId,
            modeName = cleanModeId?.let { mode ->
                adapterInfo?.modes?.firstOrNull { it.id == mode }?.name ?: mode
            },
            promptStartedAtMillis = promptStartedAtMillis,
            durationSeconds = durationSeconds,
            contextTokensUsed = contextTokensUsed,
            contextWindowSize = contextWindowSize
        )
    }

    fun pushAssistantMetaChunk(
        chatId: String,
        metadata: ConversationAssistantMetadata,
        isReplay: Boolean = false
    ) {
        val replaySeq = nextReplaySeq(chatId, isReplay)
        val parts = mutableListOf<String>()
        parts.add("\"chatId\":${escapeJsonString(chatId)}")
        parts.add("\"role\":\"assistant\"")
        parts.add("\"type\":\"assistant_meta\"")
        parts.add("\"isReplay\":$isReplay")
        metadata.agentId?.let { parts.add("\"agentId\":${escapeJsonString(it)}") }
        metadata.agentName?.let { parts.add("\"agentName\":${escapeJsonString(it)}") }
        metadata.modelId?.let { parts.add("\"modelId\":${escapeJsonString(it)}") }
        metadata.modelName?.let { parts.add("\"modelName\":${escapeJsonString(it)}") }
        metadata.modeId?.let { parts.add("\"modeId\":${escapeJsonString(it)}") }
        metadata.modeName?.let { parts.add("\"modeName\":${escapeJsonString(it)}") }
        metadata.promptStartedAtMillis?.let { parts.add("\"promptStartedAtMillis\":$it") }
        metadata.durationSeconds?.let { parts.add("\"durationSeconds\":$it") }
        metadata.contextTokensUsed?.let { parts.add("\"contextTokensUsed\":$it") }
        metadata.contextWindowSize?.let { parts.add("\"contextWindowSize\":$it") }
        if (replaySeq != null) parts.add("\"replaySeq\":$replaySeq")
        val json = "{${parts.joinToString(",")}}"
        dispatchContentChunkJson(json)
    }

    private fun buildStoredToolCallChunk(rawJson: String): JsonObject {
        val parsed = try { Json.parseToJsonElement(rawJson).jsonObject } catch (_: Exception) { null }
        return buildJsonObject {
            put("role", "assistant")
            put("type", "tool_call")
            put("toolCallId", parsed?.get("toolCallId")?.jsonPrimitive?.contentOrNull ?: "")
            put("toolKind", parsed?.get("kind")?.jsonPrimitive?.contentOrNull ?: "")
            put("toolTitle", parsed?.get("title")?.jsonPrimitive?.contentOrNull ?: "")
            put("toolStatus", parsed?.get("status")?.jsonPrimitive?.contentOrNull ?: "")
            put("toolRawJson", truncateStoredToolRawJson(rawJson))
        }
    }

    private fun buildStoredToolCallUpdateChunk(toolCallId: String, rawJson: String): JsonObject {
        return buildJsonObject {
            put("role", "assistant")
            put("type", "tool_call_update")
            put("toolCallId", toolCallId)
            put("toolRawJson", truncateStoredToolRawJson(rawJson))
        }
    }

    private fun truncateStoredToolRawJson(rawJson: String, maxChars: Int = 2_000): String {
        if (rawJson.length <= maxChars) return rawJson
        val omitted = rawJson.length - maxChars
        return rawJson.take(maxChars) + "\n\n[Stored history truncated; $omitted chars omitted]"
    }

    private fun buildStoredPlanChunk(plan: SessionUpdate, meta: JsonElement?): JsonObject? {
        val entries = extractPlanEntries(plan, meta) ?: return null
        if (entries.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            put("type", "plan")
            put("planEntries", entries)
        }
    }

    private fun replayStoredConversation(chatId: String, data: unified.llm.history.ConversationReplayData) {
        data.sessions.forEach { session ->
            session.prompts.forEach { prompt ->
                prompt.blocks.forEach { block ->
                    dispatchStoredPromptBlock(chatId, block)
                }
                prompt.events.forEach { event ->
                    dispatchStoredContentChunk(chatId, event)
                }
                prompt.assistantMeta?.let { meta ->
                    pushAssistantMetaChunk(chatId, meta, isReplay = true)
                }
            }
        }
    }

    private fun dispatchStoredPromptBlock(chatId: String, block: JsonObject) {
        val type = block["type"]?.jsonPrimitive?.contentOrNull ?: "text"
        when (type) {
            "image", "audio", "video", "file" -> {
                dispatchStoredContentChunk(
                    chatId,
                    buildJsonObject {
                        put("role", "user")
                        put("type", type)
                        block["data"]?.let { put("data", it) }
                        block["text"]?.let { put("text", it) }
                        block["mimeType"]?.let { put("mimeType", it) }
                    }
                )
            }
            "code_ref" -> {
                val text = codeRefBlockToText(block).text
                dispatchStoredContentChunk(chatId, buildStoredContentChunk("user", "text", text = text))
            }
            else -> {
                val text = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                dispatchStoredContentChunk(chatId, buildStoredContentChunk("user", "text", text = text))
            }
        }
    }

    private fun dispatchStoredContentChunk(chatId: String, stored: JsonObject) {
        val replaySeq = nextReplaySeq(chatId, true)
        val payload = buildJsonObject {
            put("chatId", chatId)
            stored.forEach { (key, value) -> put(key, value) }
            put("isReplay", true)
            if (replaySeq != null) put("replaySeq", replaySeq)
        }
        dispatchContentChunkJson(payload.toString())
    }

    fun pushAdapters() {
        try {
            val unique = linkedMapOf<String, AcpAdapterConfig.AdapterInfo>()
            AcpAdapterConfig.getAllAdapters().values.forEach { info -> unique[info.id] = info }

            val adapters = unique.values.sortedBy { it.name.lowercase() }.map { info ->
                val downloaded = AcpAdapterPaths.isDownloaded(info.id)
                val authStatus = AcpAuthService.getAuthStatus(info.id)
                val dlStatus = downloadStatuses[info.id] ?: ""
                val cliAvailable = downloaded && info.cli != null && isIdeTerminalAvailable()

                // Inline SVG icons as base64 so they load correctly in JCEF
                val iconBase64 = info.iconPath?.let { path ->
                    try {
                        val stream = AcpAdapterConfig::class.java.getResourceAsStream(path)
                        if (stream != null) {
                            val bytes = stream.use { it.readBytes() }
                            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                            "data:image/svg+xml;base64,$b64"
                        } else ""
                    } catch (e: Exception) {
                        ""
                    }
                } ?: ""

                AdapterPayload(
                    id = info.id,
                    name = info.name,
                    iconPath = iconBase64,
                    defaultModelId = info.defaultModelId ?: "",
                    models = info.models.map { AdapterModelPayload(it.modelId, it.name) },
                    defaultModeId = info.defaultModeId ?: "",
                    modes = info.modes.map { AdapterModePayload(it.id, it.name) },
                    downloaded = downloaded,
                    enabled = AcpAgentSettings.isEnabled(info.id),
                    downloadPath = if (downloaded) AcpAdapterPaths.getDownloadPath(info.id) else "",
                    hasAuthentication = info.authConfig != null,
                    authAuthenticated = authStatus.authenticated,
                    authPath = authStatus.authPath ?: "",
                    authenticating = AcpAuthService.isAuthenticating(info.id),
                    downloading = dlStatus.isNotEmpty() && !dlStatus.startsWith("Error"),
                    downloadStatus = dlStatus,
                    cliAvailable = cliAvailable
                )
            }

            val payload = adapterJson.encodeToString(adapters)
            val escaped = payload.escapeForJsString()
            runOnEdt {
                browser.cefBrowser.executeJavaScript(
                    "if(window.__onAdapters) window.__onAdapters(JSON.parse('$escaped'));",
                    browser.cefBrowser.url, 0
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun runOnEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action)

    private fun escapeJsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun jsStringLiteral(value: String) = "'${value.escapeForJsString()}'"

    private fun parseIdOnlyPayload(payload: String?): String? {
        return payload?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseStartPayload(payload: String?): Triple<String?, String?, String?> {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return Triple(null, null, null)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val chatId = obj["chatId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val adapterId = obj["adapterId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val modelId = obj["modelId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            Triple(chatId, adapterId, modelId)
        } catch (_: Exception) { Triple(null, null, null) }
    }

    private fun parseScopedIdPayload(payload: String?, idKey: String): Triple<String?, String?, String?> {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return Triple(null, null, null)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val chatId = obj["chatId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val adapterId = obj["adapterId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val idVal = obj[idKey]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            Triple(chatId, adapterId, idVal)
        } catch (_: Exception) { Triple(null, null, null) }
    }
    
    /** Converts a file/video frontend block into an ACP ContentBlock. */
    private fun fileOrVideoBlock(name: String, mimeType: String, data: String?, path: String?): ContentBlock {
        if (data != null) {
            val uri = if (path != null) "file:///${path.replace("\\", "/").trimStart('/')}" else "file:///$name"
            return ContentBlock.Resource(
                resource = EmbeddedResourceResource.BlobResourceContents(blob = data, uri = uri, mimeType = mimeType)
            )
        }
        if (path != null) {
            return ContentBlock.ResourceLink(
                name = name, uri = "file:///${path.replace("\\", "/").trimStart('/')}", mimeType = mimeType
            )
        }
        return ContentBlock.Text("[File: $name]")
    }

    private fun codeRefBlockToText(blockObj: JsonObject): ContentBlock.Text {
        val path = blockObj["path"]?.jsonPrimitive?.content ?: ""
        val startLine = blockObj["startLine"]?.jsonPrimitive?.intOrNull
        val endLine = blockObj["endLine"]?.jsonPrimitive?.intOrNull ?: startLine
        val text = if (path.isNotBlank() && startLine != null && startLine > 0) {
            if (startLine == endLine) "@${path}#L${startLine}" else "@${path}#L${startLine}-${endLine}"
        } else if (path.isNotBlank()) {
            "@${path}"
        } else {
            blockObj["text"]?.jsonPrimitive?.content ?: ""
        }
        return ContentBlock.Text(text)
    }

    private data class ParsedBlocksPayload(
        val chatId: String?,
        val blocks: List<ContentBlock>,
        val rawBlocks: List<JsonObject>
    )

    private fun parseBlocksPayload(payload: String?): ParsedBlocksPayload {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return ParsedBlocksPayload(null, emptyList(), emptyList())
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val chatId = obj["chatId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            // 1. Try to get blocks directly if present
            val blocksElement = obj["blocks"]
            if (blocksElement != null) {
                return ParsedBlocksPayload(
                    chatId = chatId,
                    blocks = parseContentBlocks(blocksElement),
                    rawBlocks = blocksElement.jsonArray.mapNotNull { it as? JsonObject }
                )
            }

            // 2. Fallback to text field
            val textValue = obj["text"]?.jsonPrimitive?.content ?: ""

            // 3. If textValue looks like a JSON array of blocks, try to parse it (compatibility with current UI)
            if (textValue.startsWith("[") && textValue.endsWith("]")) {
                try {
                    val rawBlocks = Json.parseToJsonElement(textValue).jsonArray.mapNotNull { it as? JsonObject }
                    val blocks = parseContentBlocks(JsonArray(rawBlocks))
                    if (blocks.isNotEmpty()) {
                        return ParsedBlocksPayload(chatId = chatId, blocks = blocks, rawBlocks = rawBlocks)
                    }
                } catch (_: Exception) {}
            }
            
            // 4. Final fallback: treat as plain text
            ParsedBlocksPayload(
                chatId = chatId,
                blocks = listOf(ContentBlock.Text(textValue)),
                rawBlocks = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", textValue)
                    }
                )
            )
        } catch (_: Exception) { ParsedBlocksPayload(null, emptyList(), emptyList()) }
    }

    private fun parseContentBlocks(blocksElement: JsonElement): List<ContentBlock> {
        return blocksElement.jsonArray.map { blockEl ->
            val blockObj = blockEl.jsonObject
            val type = blockObj["type"]?.jsonPrimitive?.content
            when (type) {
                "image" -> {
                    val data = blockObj["data"]?.jsonPrimitive?.content ?: ""
                    val mimeType = blockObj["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                    ContentBlock.Image(data, mimeType)
                }
                "audio" -> {
                    val data = blockObj["data"]?.jsonPrimitive?.content ?: ""
                    val mimeType = blockObj["mimeType"]?.jsonPrimitive?.content ?: "audio/wav"
                    ContentBlock.Audio(data, mimeType)
                }
                "video", "file" -> {
                    val data = blockObj["data"]?.jsonPrimitive?.content
                    val path = blockObj["path"]?.jsonPrimitive?.content
                    val defaultMime = if (type == "video") "video/mp4" else "application/octet-stream"
                    val mimeType = blockObj["mimeType"]?.jsonPrimitive?.content ?: defaultMime
                    val name = blockObj["name"]?.jsonPrimitive?.content ?: type!!
                    fileOrVideoBlock(name, mimeType, data, path)
                }
                "code_ref" -> codeRefBlockToText(blockObj)
                else -> {
                    val text = blockObj["text"]?.jsonPrimitive?.content ?: ""
                    ContentBlock.Text(text)
                }
            }
        }
    }

    private fun parseConversationLoadPayload(payload: String?): Triple<String?, String?, String?> {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return Triple(null, null, null)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val chatId = obj["chatId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val projectPath = obj["projectPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val conversationId = obj["conversationId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            Triple(chatId, projectPath, conversationId)
        } catch (_: Exception) { Triple(null, null, null) }
    }

    private fun parseHistoryConversationCliPayload(payload: String?): Pair<String?, String?> {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return null to null
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val projectPath = obj["projectPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val conversationId = obj["conversationId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            projectPath to conversationId
        } catch (_: Exception) {
            null to null
        }
    }

    private fun serializeContentBlock(content: ContentBlock, textType: String = "text"): SerializedContentBlock? {
        return when (content) {
            is ContentBlock.Text -> SerializedContentBlock(type = textType, text = content.text)
            is ContentBlock.Image -> SerializedContentBlock(type = "image", data = content.data, mimeType = content.mimeType)
            is ContentBlock.Audio -> SerializedContentBlock(type = "audio", data = content.data, mimeType = content.mimeType)
            is ContentBlock.ResourceLink -> {
                if (content.uri.startsWith("data:")) {
                    SerializedContentBlock(type = "file", data = content.uri.substringAfter("base64,"), mimeType = content.mimeType)
                } else {
                    SerializedContentBlock(type = "file", text = "[File: ${content.uri}]", mimeType = content.mimeType)
                }
            }
            is ContentBlock.Resource -> {
                when (val res = content.resource) {
                    is EmbeddedResourceResource.BlobResourceContents -> SerializedContentBlock(
                        type = "file",
                        data = res.blob,
                        mimeType = res.mimeType
                    )
                    is EmbeddedResourceResource.TextResourceContents -> SerializedContentBlock(
                        type = "file",
                        text = res.text,
                        mimeType = res.mimeType
                    )
                }
            }
        }
    }
}





