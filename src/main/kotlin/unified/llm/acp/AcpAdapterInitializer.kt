package unified.llm.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.atomicfu.AtomicRef
import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.asSink
import java.io.File

internal fun AcpClientService.initializeDownloadedAdaptersInBackground() {
    if (!startupInitializationStarted.compareAndSet(false, true)) return

    AcpAdapterConfig.getAllAdapters().values.forEach { adapterInfo ->
        if (!AcpAdapterPaths.isDownloaded(adapterInfo.id)) return@forEach
        initializeAdapterInBackground(adapterInfo.id)
    }
}

internal fun AcpClientService.initializeAdapterInBackground(adapterName: String) {
    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    if (!AcpAdapterPaths.isDownloaded(adapterInfo.id)) return

    adapterInitializationJobs.remove(adapterInfo.id)?.cancel()
    adapterInitializationScopes.remove(adapterInfo.id)?.coroutineContext?.cancel()
    adapterInitialization.remove(adapterInfo.id)
    val deferred = CompletableDeferred<Unit>()
    adapterInitialization[adapterInfo.id] = deferred
    updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Initializing)

    val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    adapterInitializationScopes[adapterInfo.id] = initScope
    val job = initScope.launch {
        try {
            val sharedProc = replaceSharedProcess(adapterInfo.id)
            initializeSharedProcessAtStartup(sharedProc, adapterInfo)
            if (!deferred.isCompleted) deferred.complete(Unit)
            updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
        } catch (_: CancellationException) {
            if (!deferred.isCompleted) deferred.cancel()
            activeProcesses[adapterInfo.id]?.stop()
            updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.NotStarted)
        } catch (e: Exception) {
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
        updateAdapterInitializationState(
            adapterInfo.id,
            AcpClientService.AdapterInitializationStatus.Failed,
            e.message ?: e.toString()
        )
        } finally {
            adapterInitializationJobs.remove(adapterInfo.id)
            adapterInitializationScopes.remove(adapterInfo.id)?.coroutineContext?.cancel()
        }
    }
    adapterInitializationJobs[adapterInfo.id] = job
}

internal suspend fun AcpClientService.initializeAdapterIfEligible(adapterName: String) {
    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    if (!AcpAdapterPaths.isDownloaded(adapterInfo.id)) {
        throw IllegalStateException("Agent '${adapterInfo.id}' is not downloaded")
    }

    val deferred = adapterInitialization.computeIfAbsent(adapterInfo.id) { CompletableDeferred<Unit>() }
    updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Initializing)
    try {
        val sharedProc = replaceSharedProcess(adapterInfo.id)
        ensureSharedProcessStarted(sharedProc, adapterInfo)
        if (!deferred.isCompleted) deferred.complete(Unit)
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    } catch (e: Exception) {
        if (!deferred.isCompleted) deferred.completeExceptionally(e)
        updateAdapterInitializationState(
            adapterInfo.id,
            AcpClientService.AdapterInitializationStatus.Failed,
            e.message ?: e.toString()
        )
        throw e
    }
}

internal suspend fun AcpClientService.awaitAdapterInitialization(adapterInfo: AcpAdapterConfig.AdapterInfo) {
    val deferred = adapterInitialization[adapterInfo.id]
        ?: throw IllegalStateException("Adapter '${adapterInfo.id}' was not initialized at plugin startup")
    deferred.await()
}

internal fun AcpClientService.resolveModelToApply(
    pref: String?,
    available: List<AcpAdapterConfig.ModelInfo>,
    default: String?
): String? {
    val p = pref?.trim().takeUnless { it.isNullOrEmpty() }
    if (p != null && (available.isEmpty() || available.any { it.modelId == p })) {
        return p
    }
    return default
}

/**
 * Runtime path initializes the shared ACP process on demand when needed.
 */
internal suspend fun AcpClientService.ensureSharedProcessStarted(
    sharedProc: AcpClientService.SharedProcess,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    forceRestart: Boolean = false
) {
    if (forceRestart) {
        sharedProc.stop()
    }

    val isHealthy = sharedProc.process != null &&
        sharedProc.process!!.isAlive &&
        sharedProc.client != null &&
        sharedProc.isInitialized

    if (!isHealthy) {
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Initializing)

        // Ensure all patches are applied before starting the process
        AcpAdapterPaths.ensurePatched(adapterInfo.id)

        initializeSharedProcessAtStartup(sharedProc, adapterInfo)
        adapterInitialization.computeIfAbsent(adapterInfo.id) { CompletableDeferred<Unit>() }.also { deferred ->
            if (!deferred.isCompleted) deferred.complete(Unit)
        }
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    } else {
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    }

    ensureAsyncSessionUpdates(sharedProc)
}

/**
 * Startup-only path that initializes ACP adapter process and protocol client exactly once.
 */
@OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
internal suspend fun AcpClientService.initializeSharedProcessAtStartup(
    sharedProc: AcpClientService.SharedProcess,
    adapterInfo: AcpAdapterConfig.AdapterInfo
) {
    val requestedAdapterName = adapterInfo.id
    sharedProc.mutex.withLock {
        val alreadyHealthy = sharedProc.process != null &&
            sharedProc.process!!.isAlive &&
            sharedProc.client != null &&
            sharedProc.isInitialized
        if (alreadyHealthy) return

        if (sharedProc.process != null) {
            sharedProc.stop()
        }

        val adapterRoot = AcpAdapterPaths.getAdapterRoot(requestedAdapterName)
            ?: throw IllegalStateException("ACP adapter directory not found: $requestedAdapterName")

        val launchFile = AcpAdapterPaths.resolveLaunchFile(adapterRoot, adapterInfo)
            ?: throw IllegalStateException("Missing launch target for adapter: ${adapterInfo.id}")
        if (!launchFile.isFile) throw IllegalStateException("Missing launch target: ${launchFile.absolutePath}")

        val command = AcpAdapterPaths.buildLaunchCommand(adapterRoot, adapterInfo).toMutableList()
        command.addAll(adapterInfo.args)

        val pb = ProcessBuilder(command).directory(resolveAdapterProcessWorkingDirectory(adapterRoot)).redirectErrorStream(false)
        val env = pb.environment()
        env.putAll(System.getenv())

        val proc = withContext(Dispatchers.IO) { pb.start() }
        sharedProc.process = proc

        Thread {
            proc.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onLogEntry(AcpLogEntry(AcpLogEntry.Direction.RECEIVED, line, AcpLogEntry.Category.STDERR))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val input = LineLoggingInputStream(proc.inputStream) { line ->
            onLogEntry(AcpLogEntry(AcpLogEntry.Direction.RECEIVED, line))
        }.asSource().buffered()

        val output = LineLoggingOutputStream(proc.outputStream) { line ->
            onLogEntry(AcpLogEntry(AcpLogEntry.Direction.SENT, line))
        }.asSink().buffered()

        val protocolScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        sharedProc.protocolScope = protocolScope
        val transport = StdioTransport(protocolScope, Dispatchers.IO, input, output)
        val prot = Protocol(protocolScope, transport)
        sharedProc.protocol = prot

        val c = Client(prot)
        sharedProc.client = c
        prot.start()
        c.initialize(ClientInfo(LATEST_PROTOCOL_VERSION, ClientCapabilities()))
        adapterRuntimeMetadataMap[requestedAdapterName] = fetchAdapterRuntimeMetadata(c, adapterInfo)
        sharedProc.isInitialized = true
        runCatching { ensureAsyncSessionUpdates(sharedProc) }
    }
}

@OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
internal suspend fun AcpClientService.fetchAdapterRuntimeMetadata(
    client: Client,
    adapterInfo: AcpAdapterConfig.AdapterInfo
): AcpClientService.AdapterRuntimeMetadata {
    val cwd = project.basePath ?: System.getProperty("user.dir")
    val params = SessionCreationParameters(cwd = cwd, mcpServers = emptyList())
    val factory = object : ClientOperationsFactory {
        override suspend fun createClientOperations(
            sessionId: SessionId,
            sessionResponse: AcpCreatedSessionResponse
        ): ClientSessionOperations {
            return createSharedSessionOperations(sessionId.value, adapterInfo.id)
        }
    }

    val session = client.newSession(params, factory)
    val models = if (session.modelsSupported) {
        session.availableModels.map { model ->
            AcpAdapterConfig.ModelInfo(
                modelId = model.modelId.value,
                name = model.name,
                description = model.description
            )
        }
    } else {
        emptyList()
    }
    val modes = if (session.modesSupported) {
        session.availableModes.map { mode ->
            AcpAdapterConfig.ModeInfo(
                id = mode.id.value,
                name = mode.name,
                description = mode.description
            )
        }
    } else {
        emptyList()
    }

    return applyAdapterRuntimePreferences(
        adapterInfo = adapterInfo,
        currentModelId = if (session.modelsSupported) session.currentModel.value.value else null,
        availableModels = models,
        currentModeId = if (session.modesSupported) session.currentMode.value.value else null,
        availableModes = modes
    )
}

internal fun AcpClientService.applyAdapterRuntimePreferences(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    currentModelId: String?,
    availableModels: List<AcpAdapterConfig.ModelInfo>,
    currentModeId: String?,
    availableModes: List<AcpAdapterConfig.ModeInfo>
): AcpClientService.AdapterRuntimeMetadata {
    val filteredModels = availableModels.filterNot { model ->
        adapterInfo.disabledModels.any { disabled -> disabled.isNotBlank() && model.modelId.contains(disabled) }
    }
    val filteredModes = availableModes.filterNot { mode ->
        adapterInfo.disabledModes.any { disabled -> disabled == mode.id }
    }

    val preferredModelId = adapterInfo.defaultModel?.modelId
        ?.takeIf { preferred -> filteredModels.any { it.modelId == preferred } }
        ?: currentModelId?.takeIf { current -> filteredModels.any { it.modelId == current } }
        ?: filteredModels.firstOrNull()?.modelId

    val preferredModeId = adapterInfo.defaultMode?.modeId
        ?.takeIf { preferred -> filteredModes.any { it.id == preferred } }
        ?: currentModeId?.takeIf { current -> filteredModes.any { it.id == current } }
        ?: filteredModes.firstOrNull()?.id

    return AcpClientService.AdapterRuntimeMetadata(
        currentModelId = preferredModelId,
        availableModels = filteredModels,
        currentModeId = preferredModeId,
        availableModes = filteredModes
    )
}

internal fun AcpClientService.resolveAdapterProcessWorkingDirectory(adapterRoot: File): File {
    val projectBase = project.basePath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.exists() && it.isDirectory }
    return projectBase ?: adapterRoot
}

internal fun AcpClientService.ensureAsyncSessionUpdates(sharedProc: AcpClientService.SharedProcess) {
    if (sharedProc.sessionUpdateWrapped) return
    val protocol = sharedProc.protocol ?: return
    try {
        val field = Protocol::class.java.getDeclaredField("notificationHandlers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val handlers = field.get(protocol) as AtomicRef<PersistentMap<MethodName, suspend (JsonRpcNotification) -> Unit>>
        val methodName = AcpMethod.ClientMethods.SessionUpdate.methodName
        val original = handlers.value[methodName] ?: return
        val updateScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))
        sharedProc.sessionUpdateScope = updateScope
        val queue = Channel<JsonRpcNotification>(Channel.UNLIMITED)
        sharedProc.sessionUpdateQueue = queue
        sharedProc.sessionUpdateWorker = updateScope.launch {
            for (notification in queue) {
                try {
                    original(notification)
                } catch (_: Exception) {}
            }
        }
        val wrapped: suspend (JsonRpcNotification) -> Unit = { notification ->
            val result = queue.trySend(notification)
            if (!result.isSuccess) {
                updateScope.launch {
                    runCatching { queue.send(notification) }
                        .onFailure { }
                }
            }
        }
        handlers.value = handlers.value.put(methodName, wrapped)
        sharedProc.sessionUpdateWrapped = true
    } catch (_: Exception) {}
}
