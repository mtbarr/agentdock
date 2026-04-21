package agentdock.acp

import com.agentclientprotocol.model.ContentBlock
import kotlinx.serialization.json.*
import agentdock.history.ConversationPromptReplayEntry
import agentdock.history.ConversationReplayData
import agentdock.history.ConversationSessionReplayEntry
import agentdock.history.AgentDockHistoryService

internal fun AcpBridge.startHistoryReplayCapture(
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

internal fun AcpBridge.beginImportedReplaySession(
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

internal fun AcpBridge.discardHistoryReplayCapture(chatId: String) {
    historyReplayCaptures.remove(chatId)
}

internal fun AcpBridge.flushHistoryReplayCapture(chatId: String) {
    val capture = historyReplayCaptures.remove(chatId) ?: return
    val sessions = capture.sessions
        .filter { it.prompts.isNotEmpty() }
        .map { session ->
            ConversationSessionReplayEntry(
                sessionId = session.sessionId,
                adapterName = session.adapterName,
                prompts = session.prompts.map { prompt ->
                    ConversationPromptReplayEntry(
                        blocks = prompt.blocks,
                        events = prompt.events,
                        assistantMeta = prompt.assistantMeta
                    )
                }
            )
        }
    if (sessions.isEmpty()) return
    AgentDockHistoryService.saveConversationReplay(
        projectPath = capture.projectPath,
        conversationId = capture.conversationId,
        data = ConversationReplayData(sessions = sessions)
    )
}

internal fun AcpBridge.recordReplayUserBlock(chatId: String, sessionId: String, adapterName: String, content: ContentBlock) {
    val capture = historyReplayCaptures[chatId] ?: return
    if (sessionId.isBlank() || adapterName.isBlank()) return
    val block = storedReplayPromptBlockFromContentBlock(content) ?: return
    val session = getOrCreateReplaySession(capture, sessionId, adapterName)
    val prompt = getOrCreateReplayPrompt(session, startNewIfNeeded = true)
    prompt.blocks.add(block)
}

internal fun AcpBridge.getOrCreateReplaySession(
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

internal fun AcpBridge.getOrCreateReplayPrompt(
    session: ReplaySessionCapture,
    startNewIfNeeded: Boolean
): ReplayPromptCapture {
    val current = session.prompts.lastOrNull()
    if (current == null) {
        return ReplayPromptCapture().also { session.prompts.add(it) }
    }
    if (startNewIfNeeded && (current.events.isNotEmpty() || current.blocks.isNotEmpty())) {
        return ReplayPromptCapture().also { session.prompts.add(it) }
    }
    return current
}

internal fun AcpBridge.replayStoredConversation(chatId: String, data: ConversationReplayData) {
    data.sessions.forEach { session ->
        session.prompts.forEach { prompt ->
            prompt.blocks.forEach { block ->
                dispatchStoredPromptBlock(chatId, block)
            }
            prompt.events.forEach { event ->
                dispatchStoredContentChunk(chatId, event)
            }
            prompt.assistantMeta?.let { meta ->
                pushPromptDoneChunk(chatId, meta, outcome = "success", isReplay = true)
            }
        }
    }
}

internal fun AcpBridge.dispatchStoredPromptBlock(chatId: String, block: JsonObject) {
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

internal fun AcpBridge.dispatchStoredContentChunk(chatId: String, stored: JsonObject) {
    val replaySeq = nextReplaySeq(chatId, true)
    val payload = buildJsonObject {
        put("chatId", chatId)
        stored.forEach { (key, value) -> put(key, value) }
        put("isReplay", true)
        if (replaySeq != null) put("replaySeq", replaySeq)
    }
    dispatchContentChunkJson(payload.toString())
}
