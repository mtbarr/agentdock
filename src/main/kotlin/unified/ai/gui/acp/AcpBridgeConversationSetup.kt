package unified.ai.gui.acp

import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import unified.ai.gui.history.UnifiedHistoryService

private data class PermissionDecisionPayload(
    val requestId: String,
    val decision: String
)

private data class SessionMetadataUpdatePayload(
    val conversationId: String,
    val sessionId: String,
    val adapterName: String,
    val promptCount: Int,
    val title: String?,
    val touchUpdatedAt: Boolean
)

private data class ContinueConversationPayload(
    val previousSessionId: String,
    val previousAdapterName: String,
    val sessionId: String,
    val adapterName: String,
    val title: String?
)

private fun AcpBridge.pushConversationError(chatId: String, error: Throwable) {
    pushContentChunk(chatId, "assistant", "text", text = "[Error: ${formatAcpError(error)}]", isReplay = false)
}

private fun AcpBridge.pushConversationError(chatId: String, message: String) {
    pushContentChunk(chatId, "assistant", "text", text = "[Error: $message]", isReplay = false)
}

private suspend fun AcpBridge.handleScopedConfigChange(
    chatId: String?,
    adapterId: String?,
    valueId: String?,
    kind: String,
    applyChange: suspend (String, String) -> Boolean
) {
    if (chatId == null || adapterId == null || valueId == null) return
    if (service.activeAdapterName(chatId) != adapterId) return

    pushStatus(chatId, "initializing")
    try {
        val ok = applyChange(chatId, valueId)
        if (!ok) {
            pushConversationError(chatId, "Failed to set $kind '$valueId'")
        } else {
            pushAdapters()
        }
    } catch (e: Exception) {
        pushConversationError(chatId, e)
    } finally {
        pushStatus(chatId, service.status(chatId).name.lowercase())
    }
}

private fun parsePermissionDecisionPayload(payload: String?): PermissionDecisionPayload? {
    return runCatching {
        val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
        val requestId = obj["requestId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val decision = obj["decision"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (requestId.isBlank()) null else PermissionDecisionPayload(requestId, decision)
    }.getOrNull()
}

private fun parseSessionMetadataUpdatePayload(payload: String?): SessionMetadataUpdatePayload? {
    return runCatching {
        val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
        val conversationId = obj["conversationId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val sessionId = obj["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val adapterName = obj["adapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (conversationId.isBlank() || sessionId.isBlank() || adapterName.isBlank()) return@runCatching null
        SessionMetadataUpdatePayload(
            conversationId = conversationId,
            sessionId = sessionId,
            adapterName = adapterName,
            promptCount = obj["promptCount"]?.jsonPrimitive?.intOrNull ?: 0,
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            touchUpdatedAt = obj["touchUpdatedAt"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }.getOrNull()
}

private fun parseContinueConversationPayload(payload: String?): ContinueConversationPayload? {
    return runCatching {
        val obj = Json.parseToJsonElement(payload ?: "{}").jsonObject
        val previousSessionId = obj["previousSessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val previousAdapterName = obj["previousAdapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
        val sessionId = obj["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val adapterName = obj["adapterName"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (previousSessionId.isBlank() || previousAdapterName.isBlank() || sessionId.isBlank() || adapterName.isBlank()) {
            return@runCatching null
        }
        ContinueConversationPayload(
            previousSessionId = previousSessionId,
            previousAdapterName = previousAdapterName,
            sessionId = sessionId,
            adapterName = adapterName,
            title = obj["title"]?.jsonPrimitive?.contentOrNull
        )
    }.getOrNull()
}

private fun AcpBridge.refreshDownloadedAdapterInitialization() {
    val target = AcpAdapterPaths.getExecutionTarget()
    AcpAdapterConfig.getAllAdapters().values.forEach { info ->
        if (!AcpAdapterPaths.isDownloaded(info.id, target)) return@forEach
        if (service.isAdapterReady(info.id)) return@forEach
        if (service.adapterInitializationStatus(info.id) == AcpClientService.AdapterInitializationStatus.Initializing) return@forEach
        service.initializeAdapterInBackground(info.id)
    }
}


internal fun AcpBridge.installConversationQueries() {
    startAgentQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val (chatId, adapterName, modelId) = parseStartPayload(payload)
            if (chatId != null) {
                scope.launch(Dispatchers.Default) {
                    pushStatus(chatId, "initializing")
                    try {
                        withTimeout(AcpBridge.START_AGENT_TIMEOUT_MS) {
                            service.startAgent(chatId, adapterName, modelId)
                        }
                        pushAdapters()
                        pushStatus(chatId, service.status(chatId).name.lowercase())
                        pushSessionId(chatId, service.sessionId(chatId))
                        pushMode(chatId, service.activeModeId(chatId))
                    } catch (e: Exception) {
                        pushStatus(chatId, "error")
                        pushContentChunk(chatId, "assistant", "text", text = "[Error: ${formatAcpError(e)}]", isReplay = false)
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
                handleScopedConfigChange(chatId, adapterId, modelId, "model", service::setModel)
            }
            JBCefJSQuery.Response("ok")
        }
    }

    setModeQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            val (chatId, adapterId, modeId) = parseScopedIdPayload(payload, "modeId")
            scope.launch(Dispatchers.Default) {
                handleScopedConfigChange(chatId, adapterId, modeId, "mode", service::setMode)
            }
            JBCefJSQuery.Response("ok")
        }
    }

    listAdaptersQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler {
            scope.launch(Dispatchers.IO) {
                resetAuthStatusRefreshState()
                pushAdapters(includeRuntimeChecks = false)
                refreshDownloadedAdapterInitialization()
                scope.launch(Dispatchers.IO) {
                    pushAdapters(includeRuntimeChecks = true)
                }
            }
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
                                    val fallbackText = "[The AI agent ended the turn without providing a response.]"
                                    if (ensureLivePromptNoResponseFallback(chatId, fallbackText, captureId)) {
                                        pushContentChunk(chatId, "assistant", "text", text = fallbackText, isReplay = false)
                                    }
                                    flushLivePromptCapture(chatId, captureId)?.let {
                                        pushPromptDoneChunk(chatId, it, outcome = "success")
                                    }
                                    pushStatus(chatId, "ready")
                                }
                                is AcpEvent.Error -> {
                                    pushContentChunk(chatId, "assistant", "text", text = "[Error: ${event.message}]", isReplay = false)
                                    appendLivePromptTextEvent(chatId, "[Error: ${event.message}]", captureId)
                                    flushLivePromptCapture(chatId, captureId)?.let {
                                        pushPromptDoneChunk(chatId, it, outcome = "error")
                                    }
                                    pushStatus(chatId, "error")
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        pushStatus(chatId, "ready")
                        throw e
                    } catch (e: Exception) {
                        val message = "[Error: ${formatAcpError(e)}]"
                        pushContentChunk(chatId, "assistant", "text", text = message, isReplay = false)
                        appendLivePromptTextEvent(chatId, message, captureId)
                        flushLivePromptCapture(chatId, captureId)?.let {
                            pushPromptDoneChunk(chatId, it, outcome = "error")
                        }
                        pushStatus(chatId, "error")
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
                promptJobs[chatId]?.cancel()
                scope.launch(Dispatchers.Default) {
                    service.cancel(chatId)
                    pushContentChunk(chatId, "assistant", "text", text = "\n\n[Cancelled]\n\n", isReplay = false)
                    appendLivePromptTextEvent(chatId, "\n\n[Cancelled]\n\n")
                    flushLivePromptCapture(chatId)?.let {
                        pushPromptDoneChunk(chatId, it, outcome = "cancelled")
                    }
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
            parsePermissionDecisionPayload(payload)?.let { request ->
                scope.launch(Dispatchers.Default) {
                    service.respondToPermissionRequest(request.requestId, request.decision)
                }
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
                            pushConversationReplayLoaded(chatId, storedConversation)

                            val lastStoredSession = storedConversation.sessions.lastOrNull()
                                ?: throw IllegalStateException("Conversation replay '$conversationId' is empty")
                            pushSessionId(chatId, lastStoredSession.sessionId)

                            scope.launch(Dispatchers.Default) {
                                try {
                                    suppressReplayForChatIds.add(chatId)
                                    try {
                                        withTimeout(AcpBridge.START_AGENT_TIMEOUT_MS) {
                                            service.loadSession(
                                                chatId = chatId,
                                                adapterName = lastStoredSession.adapterName,
                                                sessionId = lastStoredSession.sessionId,
                                                deliverReplay = false
                                            )
                                        }
                                    } finally {
                                        suppressReplayForChatIds.remove(chatId)
                                    }
                                    pushAdapters()
                                    pushStatus(chatId, service.status(chatId).name.lowercase())
                                    pushSessionId(chatId, service.sessionId(chatId))
                                    pushMode(chatId, service.activeModeId(chatId))
                                } catch (e: Exception) {
                                    pushStatus(chatId, "error")
                                    pushConversationError(chatId, e)
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
                                withTimeout(AcpBridge.START_AGENT_TIMEOUT_MS) {
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
                            pushAdapters()
                            val refreshedConversation = UnifiedHistoryService.loadConversationReplay(projectPath, conversationId)
                            if (refreshedConversation != null) {
                                pushConversationReplayLoaded(chatId, refreshedConversation)
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
                        pushConversationError(chatId, e)
                    }
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }


    updateSessionMetadataQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            scope.launch(Dispatchers.IO) {
                parseSessionMetadataUpdatePayload(payload)?.let { request ->
                    UnifiedHistoryService.upsertRuntimeSessionMetadata(
                        projectPath = service.project.basePath,
                        conversationId = request.conversationId,
                        sessionId = request.sessionId,
                        adapterName = request.adapterName,
                        promptCount = request.promptCount,
                        titleCandidate = request.title,
                        touchUpdatedAt = request.touchUpdatedAt
                    )
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    continueConversationQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
        addHandler { payload ->
            scope.launch(Dispatchers.IO) {
                parseContinueConversationPayload(payload)?.let { request ->
                    UnifiedHistoryService.appendSessionToConversation(
                        projectPath = service.project.basePath,
                        previousSessionId = request.previousSessionId,
                        previousAdapterName = request.previousAdapterName,
                        sessionId = request.sessionId,
                        adapterName = request.adapterName,
                        titleCandidate = request.title
                    )
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
                        error = formatAcpError(error)
                    )
                }
                pushConversationTranscriptSaved(result)
            }
            JBCefJSQuery.Response("ok")
        }
    }
}
