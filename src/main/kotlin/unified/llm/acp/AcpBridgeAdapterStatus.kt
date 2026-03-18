package unified.llm.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import unified.llm.utils.escapeForJsString


internal fun AcpBridge.pushAdapters() {
    try {
        val unique = linkedMapOf<String, AcpAdapterConfig.AdapterInfo>()
        AcpAdapterConfig.getAllAdapters().values.forEach { info -> unique[info.id] = info }

        val idsToFetch = mutableListOf<String>()

        val adapters = unique.values.sortedBy { it.name.lowercase() }.map { info ->
            val downloaded = AcpAdapterPaths.isDownloaded(info.id)
            val dlStatus = downloadStatuses[info.id] ?: ""
            val isDownloading = dlStatus.isNotEmpty() && !dlStatus.startsWith("Error")
            val hasAuthentication = info.authConfig != null
            val authUiMode = info.authConfig?.uiMode ?: "login_logout"
            val isAuthenticating = AcpAuthService.isAuthenticating(info.id)
            val initStatus = service.adapterInitializationStatus(info.id)
            val isInitializing = initStatus == AcpClientService.AdapterInitializationStatus.Initializing
            val isInitialized = service.isAdapterReady(info.id)
            val cliAvailable = downloaded && info.cli != null && cli.isIdeTerminalAvailable()
            val initError = service.adapterInitializationError(info.id) ?: ""

            val shouldFetchAuth = downloaded && hasAuthentication && authUiMode == "login_logout" &&
                !isDownloading && !isAuthenticating && isInitialized

            val needsAuthFetch = shouldFetchAuth && !authStates.containsKey(info.id)
            if (needsAuthFetch) idsToFetch.add(info.id)

            val authAuthenticated = if (shouldFetchAuth && !needsAuthFetch) authStates[info.id] == true else false
            val authLoading = needsAuthFetch || authFetchJobs[info.id]?.isActive == true

            val isReady = when {
                !isInitialized -> false
                !hasAuthentication -> true
                authUiMode != "login_logout" -> true
                else -> authAuthenticated
            }

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

            val runtimeMetadata = service.adapterRuntimeMetadata(info.id) ?: run {
                val fallbackModel = info.defaultModel?.let {
                    AdapterModelPayload(it.modelId, it.name, it.description.orEmpty())
                }
                val fallbackMode = info.defaultMode?.let {
                    AdapterModePayload(it.modeId, it.name, it.description.orEmpty())
                }
                AcpClientService.AdapterRuntimeMetadata(
                    currentModelId = fallbackModel?.modelId,
                    availableModels = info.defaultModel?.let {
                        listOf(
                            AcpAdapterConfig.ModelInfo(
                                modelId = it.modelId,
                                name = it.name,
                                description = it.description
                            )
                        )
                    } ?: emptyList(),
                    currentModeId = fallbackMode?.id,
                    availableModes = info.defaultMode?.let {
                        listOf(
                            AcpAdapterConfig.ModeInfo(
                                id = it.modeId,
                                name = it.name,
                                description = it.description
                            )
                        )
                    } ?: emptyList()
                )
            }

            AdapterPayload(
                id = info.id,
                name = info.name,
                iconPath = iconBase64,
                currentModelId = runtimeMetadata?.currentModelId ?: "",
                availableModels = runtimeMetadata?.availableModels?.map {
                    AdapterModelPayload(it.modelId, it.name, it.description.orEmpty())
                } ?: emptyList(),
                currentModeId = runtimeMetadata?.currentModeId ?: "",
                availableModes = runtimeMetadata?.availableModes?.map {
                    AdapterModePayload(it.id, it.name, it.description.orEmpty())
                } ?: emptyList(),
                downloaded = downloaded,
                downloadPath = if (downloaded) AcpAdapterPaths.getDownloadPath(info.id) else "",
                hasAuthentication = hasAuthentication,
                authAuthenticated = authAuthenticated,
                authLoading = authLoading,
                authError = "",
                authenticating = isAuthenticating,
                authUiMode = authUiMode,
                initializing = isInitializing,
                initializationError = initError,
                ready = isReady,
                downloading = isDownloading,
                downloadStatus = dlStatus,
                disabledModels = info.disabledModels,
                cliAvailable = cliAvailable,
                usageStrategy = info.usageStrategy
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

        for (id in idsToFetch) {
            if (authFetchJobs[id]?.isActive == true) continue
            authFetchJobs[id] = scope.launch(Dispatchers.IO) {
                val authenticated = try { AcpAuthService.getAuthStatus(id).authenticated } catch (_: Exception) { false }
                authStates[id] = authenticated
                authFetchJobs.remove(id)
                pushAdapters()
            }
        }
    } catch (e: Exception) {
    }
}

internal fun AcpBridge.resetAuthStatusRefreshState() {
    authFetchJobs.values.forEach { it.cancel() }
    authFetchJobs.clear()
    authStates.clear()
    authActionJobs.values.forEach { it.cancel() }
    authActionJobs.clear()
    downloadStatuses.clear()
    AcpAuthService.resetTransientState()
}
