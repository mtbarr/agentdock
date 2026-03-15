package unified.llm.acp

import com.agentclientprotocol.model.SessionUpdate
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File


internal fun AcpBridge.installServiceCallbacks() {
    service.setOnLogEntry { pushLogEntry(it) }
    service.setOnPermissionRequest { pushPermissionRequest(it) }
    service.setOnAdapterInitializationStateChanged { _, _, _ ->
        scope.launch(Dispatchers.IO) { pushAdapters() }
    }
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
}

internal fun AcpBridge.installAdapterQueries() {
    readyQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler {
            runOnEdt {
                injectDebugApi(browser.cefBrowser)
            }
            scope.launch(Dispatchers.IO) {
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
                            service.initializeAdapterInBackground(adapterId)
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
                    val deleted = AcpAdapterPaths.deleteAdapter(adapterId)
                    if (deleted) {
                        downloadStatuses.remove(adapterId)
                    } else {
                        downloadStatuses[adapterId] = "Error: Unable to remove adapter files"
                    }
                    pushAdapters()
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    loginAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val adapterId = parseIdOnlyPayload(payload)
            if (adapterId != null) {
                val existingJob = authActionJobs.remove(adapterId)
                existingJob?.cancel()
                val job = scope.launch(Dispatchers.Default) {
                    try {
                        downloadStatuses.remove(adapterId)
                        AcpAuthService.incrementActive(adapterId)
                        pushAdapters()
                        when (AcpAuthService.getLoginMode(adapterId)) {
                            "manage_terminal" -> {
                                if (!cli.isIdeTerminalAvailable()) {
                                    throw Exception("IDE terminal is required for auth management")
                                }
                                cli.openAgentCliInTerminal(adapterId)
                            }
                            "ide_terminal" -> {
                                if (!cli.isIdeTerminalAvailable()) {
                                    throw Exception("IDE terminal is required for login")
                                }
                                if (!cli.openLoginInTerminal(adapterId)) {
                                    throw Exception("Unable to open IDE terminal for login")
                                }
                            }
                            else -> {
                                val projectPath = service.project.basePath
                                AcpAuthService.login(adapterId, projectPath) {
                                    pushAdapters()
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                    } catch (_: Exception) {
                    } finally {
                        AcpAuthService.decrementActive(adapterId)
                        authActionJobs.remove(adapterId)
                        authStates.remove(adapterId)
                        pushAdapters()
                    }
                }
                authActionJobs[adapterId] = job
            }
            JBCefJSQuery.Response("ok")
        }
    }

    logoutAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val adapterId = parseIdOnlyPayload(payload)
            if (adapterId != null) {
                val existingJob = authActionJobs.remove(adapterId)
                existingJob?.cancel()
                val job = scope.launch(Dispatchers.Default) {
                    try {
                        AcpAuthService.incrementActive(adapterId)
                        pushAdapters()
                        AcpAuthService.logout(adapterId)
                    } catch (_: CancellationException) {
                    } catch (_: Exception) {
                    } finally {
                        AcpAuthService.decrementActive(adapterId)
                        authActionJobs.remove(adapterId)
                        authStates.remove(adapterId)
                        pushAdapters()
                    }
                }
                authActionJobs[adapterId] = job
            }
            JBCefJSQuery.Response("ok")
        }
    }

    fetchUsageQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val adapterId = parseIdOnlyPayload(payload) ?: payload?.trim() ?: ""
            scope.launch(Dispatchers.IO) {
                val info = AcpAdapterConfig.getAllAdapters()[adapterId]
                val strategy = info?.usageStrategy
                val result = when (strategy) {
                    "claude-oauth" -> AcpUsageDataFetcher.fetchClaudeUsageData()
                    "codex-chatgpt" -> AcpUsageDataFetcher.fetchCodexUsageData()
                    "gemini-cli" -> AcpUsageDataFetcher.fetchGeminiUsageData(adapterId)
                    else -> ""
                }
                val escapedAdapterId = jsStringLiteral(adapterId)
                val escapedResult = jsStringLiteral(result)
                runOnEdt {
                    browser.cefBrowser.executeJavaScript(
                        "if(window.__onUsageData) window.__onUsageData($escapedAdapterId, $escapedResult);",
                        browser.cefBrowser.url, 0
                    )
                }
            }
            JBCefJSQuery.Response(null)
        }
    }

    openAgentCliQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val adapterId = parseIdOnlyPayload(payload)
            if (adapterId != null) {
                scope.launch(Dispatchers.Default) {
                    cli.openAgentCliInTerminal(adapterId)
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
                    cli.openHistoryConversationCliInTerminal(projectPath, conversationId)
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

}
