package agentdock.acp

import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.SessionModeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("OPT_IN_USAGE")
internal suspend fun AcpClientService.setModel(chatId: String, modelId: String): Boolean {
    val context = sessions[chatId] ?: return false
    val trimmedModelId = modelId.trim()
    val adapterName = context.activeAdapterNameRef.get() ?: return false
    if (context.activeModelIdRef.get() == trimmedModelId) {
        AcpAgentPreferencesStore.rememberModel(adapterName, trimmedModelId)
        return true
    }

    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    return when (adapterInfo.modelChangeStrategy) {
        "restart-resume" -> runCatching {
            startAgent(chatId, adapterName, trimmedModelId, context.sessionIdRef.get())
            context.activeModelIdRef.set(trimmedModelId)
            AcpAgentPreferencesStore.rememberModel(adapterName, trimmedModelId)
            adapterRuntimeMetadataMap[adapterName]?.let { metadata ->
                adapterRuntimeMetadataMap[adapterName] = metadata.copy(currentModelId = trimmedModelId)
            }
            true
        }.getOrDefault(false)
        else -> {
            val session = context.session ?: return false
            runCatching {
                withContext(Dispatchers.IO) {
                    session.setModel(ModelId(trimmedModelId))
                }
                context.activeModelIdRef.set(trimmedModelId)
                AcpAgentPreferencesStore.rememberModel(adapterName, trimmedModelId)
                adapterRuntimeMetadataMap[adapterName]?.let { metadata ->
                    adapterRuntimeMetadataMap[adapterName] = metadata.copy(currentModelId = trimmedModelId)
                }
                true
            }.getOrDefault(false)
        }
    }
}

@Suppress("OPT_IN_USAGE")
internal suspend fun AcpClientService.setMode(chatId: String, modeId: String): Boolean {
    val context = sessions[chatId] ?: return false
    val trimmedModeId = modeId.trim()
    val adapterName = context.activeAdapterNameRef.get()
    if (context.activeModeIdRef.get() == trimmedModeId) {
        if (!adapterName.isNullOrBlank()) {
            AcpAgentPreferencesStore.rememberMode(adapterName, trimmedModeId)
        }
        return true
    }

    val session = context.session ?: return false
    return runCatching {
        withContext(Dispatchers.IO) {
            session.setMode(SessionModeId(trimmedModeId))
        }
        context.activeModeIdRef.set(trimmedModeId)
        if (!adapterName.isNullOrBlank()) {
            AcpAgentPreferencesStore.rememberMode(adapterName, trimmedModeId)
            adapterRuntimeMetadataMap[adapterName]?.let { metadata ->
                adapterRuntimeMetadataMap[adapterName] = metadata.copy(currentModeId = trimmedModeId)
            }
        }
        true
    }.getOrDefault(false)
}
