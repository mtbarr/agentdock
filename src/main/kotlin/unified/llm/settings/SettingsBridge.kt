package unified.llm.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import unified.llm.acp.AcpClientService
import unified.llm.acp.AcpExecutionMode
import unified.llm.acp.AcpExecutionTarget
import unified.llm.acp.switchExecutionTarget
import unified.llm.history.UnifiedHistoryService
import unified.llm.utils.escapeForJsString

class SettingsBridge(
    private val browser: JBCefBrowser,
    private val scope: CoroutineScope,
    private val project: Project,
    private val acpService: AcpClientService
) {
    private val settingsSaveMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var loadQuery: JBCefJSQuery? = null
    private var installWhisperQuery: JBCefJSQuery? = null
    private var uninstallWhisperQuery: JBCefJSQuery? = null
    private var transcribeAudioQuery: JBCefJSQuery? = null
    private var startRecordingQuery: JBCefJSQuery? = null
    private var stopRecordingQuery: JBCefJSQuery? = null
    private var loadTranscriptionSettingsQuery: JBCefJSQuery? = null
    private var saveTranscriptionSettingsQuery: JBCefJSQuery? = null
    private var loadGlobalSettingsQuery: JBCefJSQuery? = null
    private var saveGlobalSettingsQuery: JBCefJSQuery? = null

    fun install() {
        val browserBase = browser as com.intellij.ui.jcef.JBCefBrowserBase

        loadQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    push(WhisperFeatureManager.currentState())
                }
                JBCefJSQuery.Response("ok")
            }
        }

        installWhisperQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    push(WhisperFeatureManager.currentState(statusOverride = "Installing...", installing = true))
                    runCatching {
                        WhisperFeatureManager.install { status ->
                            push(WhisperFeatureManager.currentState(statusOverride = status, installing = true))
                        }
                    }.onSuccess { state ->
                        push(state)
                    }.onFailure { error ->
                        push(WhisperFeatureManager.currentState(statusOverride = "Error", installing = false).copy(detail = error.message.orEmpty()))
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        uninstallWhisperQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    push(WhisperFeatureManager.currentState(statusOverride = "Uninstalling...", installing = true))
                    runCatching {
                        WhisperFeatureManager.uninstall { status ->
                            push(WhisperFeatureManager.currentState(statusOverride = status, installing = true))
                        }
                    }.onSuccess { state ->
                        push(state)
                    }.onFailure { error ->
                        push(WhisperFeatureManager.currentState(statusOverride = "Error", installing = false).copy(detail = error.message.orEmpty()))
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        transcribeAudioQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            val request = json.decodeFromString<AudioTranscriptionRequest>(payload)
                            val text = WhisperFeatureManager.transcribeAudioBase64(request.audioBase64)
                            AudioTranscriptionResultPayload(
                                requestId = request.requestId,
                                success = true,
                                text = text
                            )
                        }.getOrElse { error ->
                            val requestId = runCatching {
                                json.decodeFromString<AudioTranscriptionRequest>(payload).requestId
                            }.getOrDefault("")
                            AudioTranscriptionResultPayload(
                                requestId = requestId,
                                success = false,
                                error = error.message.orEmpty()
                            )
                        }
                        pushResult(result)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        startRecordingQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    val state = runCatching {
                        AudioCaptureManager.startRecording()
                        AudioRecordingStatePayload(recording = true)
                    }.getOrElse { error ->
                        AudioRecordingStatePayload(recording = false, error = error.message.orEmpty())
                    }
                    pushRecordingState(state)
                }
                JBCefJSQuery.Response("ok")
            }
        }

        stopRecordingQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val requestId = runCatching {
                            json.decodeFromString<StopRecordingRequest>(payload).requestId
                        }.getOrDefault("")
                        val result = runCatching {
                            val recordedFile = AudioCaptureManager.stopRecording()
                            try {
                                val text = WhisperFeatureManager.transcribeAudioFile(recordedFile)
                                AudioTranscriptionResultPayload(
                                    requestId = requestId,
                                    success = true,
                                    text = text
                                )
                            } finally {
                                recordedFile.delete()
                            }
                        }.getOrElse { error ->
                            AudioTranscriptionResultPayload(
                                requestId = requestId,
                                success = false,
                                error = error.message.orEmpty()
                            )
                        }
                        pushRecordingState(AudioRecordingStatePayload(recording = false))
                        pushResult(result)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        loadTranscriptionSettingsQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    pushTranscriptionSettings(GlobalSettingsStore.loadAudioTranscriptionSettings())
                }
                JBCefJSQuery.Response("ok")
            }
        }

        saveTranscriptionSettingsQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val settings = runCatching {
                            json.decodeFromString<AudioTranscriptionSettings>(payload)
                        }.getOrDefault(AudioTranscriptionSettings())
                        pushTranscriptionSettings(GlobalSettingsStore.saveAudioTranscriptionSettings(settings))
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }

        loadGlobalSettingsQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    pushGlobalSettings(
                        GlobalSettingsPayload(
                            settings = GlobalSettingsStore.load(),
                            host = HostSettingsInfo.resolve()
                        )
                    )
                }
                JBCefJSQuery.Response("ok")
            }
        }

        saveGlobalSettingsQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        settingsSaveMutex.withLock {
                            val requested = runCatching {
                                json.decodeFromString<GlobalSettings>(payload)
                            }.getOrDefault(GlobalSettings())
                            val current = GlobalSettingsStore.load()
                            if (requested.useWslForAcpAdapters != current.useWslForAcpAdapters) {
                                handleExecutionTargetSwitch(current, requested)
                            } else {
                                val saved = GlobalSettingsStore.save(requested)
                                if (saved.wslDistributionName != current.wslDistributionName) {
                                    AcpExecutionMode.resetWslRuntimeCaches()
                                }
                                pushGlobalSettings(
                                    GlobalSettingsPayload(
                                        settings = saved,
                                        host = HostSettingsInfo.resolve()
                                    )
                                )
                            }
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
    }

    fun injectApi(cefBrowser: CefBrowser) {
        val loadInject = loadQuery?.inject("") ?: "console.error('[SettingsBridge] Load query not ready')"
        val installInject = installWhisperQuery?.inject("") ?: "console.error('[SettingsBridge] Install query not ready')"
        val uninstallInject = uninstallWhisperQuery?.inject("") ?: "console.error('[SettingsBridge] Uninstall query not ready')"
        val transcribeInject = transcribeAudioQuery?.inject("payload") ?: "console.error('[SettingsBridge] Transcribe query not ready')"
        val startRecordingInject = startRecordingQuery?.inject("") ?: "console.error('[SettingsBridge] Start recording query not ready')"
        val stopRecordingInject = stopRecordingQuery?.inject("payload") ?: "console.error('[SettingsBridge] Stop recording query not ready')"
        val loadSettingsInject = loadTranscriptionSettingsQuery?.inject("") ?: "console.error('[SettingsBridge] Load transcription settings query not ready')"
        val saveSettingsInject = saveTranscriptionSettingsQuery?.inject("payload") ?: "console.error('[SettingsBridge] Save transcription settings query not ready')"
        val loadGlobalSettingsInject = loadGlobalSettingsQuery?.inject("") ?: "console.error('[SettingsBridge] Load global settings query not ready')"
        val saveGlobalSettingsInject = saveGlobalSettingsQuery?.inject("payload") ?: "console.error('[SettingsBridge] Save global settings query not ready')"

        val script = """
            (function() {
                window.__settingsBridgeReady = true;
                window.__onAudioTranscriptionFeature = window.__onAudioTranscriptionFeature || function(state) {};
                window.__onAudioTranscriptionResult = window.__onAudioTranscriptionResult || function(result) {};
                window.__onAudioRecordingState = window.__onAudioRecordingState || function(state) {};
                window.__onAudioTranscriptionSettings = window.__onAudioTranscriptionSettings || function(settings) {};
                window.__onGlobalSettings = window.__onGlobalSettings || function(payload) {};
                window.__onExecutionTargetSwitched = window.__onExecutionTargetSwitched || function(payload) {};
                window.__loadAudioTranscriptionFeature = function() {
                    try { $loadInject } catch (e) { console.error('[SettingsBridge] Load error', e); }
                };
                window.__installAudioTranscriptionFeature = function() {
                    try { $installInject } catch (e) { console.error('[SettingsBridge] Install error', e); }
                };
                window.__uninstallAudioTranscriptionFeature = function() {
                    try { $uninstallInject } catch (e) { console.error('[SettingsBridge] Uninstall error', e); }
                };
                window.__transcribeAudioInput = function(payload) {
                    if (!payload) return;
                    try { $transcribeInject } catch (e) { console.error('[SettingsBridge] Transcribe error', e); }
                };
                window.__startAudioRecording = function() {
                    try { $startRecordingInject } catch (e) { console.error('[SettingsBridge] Start recording error', e); }
                };
                window.__stopAudioRecording = function(payload) {
                    if (!payload) return;
                    try { $stopRecordingInject } catch (e) { console.error('[SettingsBridge] Stop recording error', e); }
                };
                window.__loadAudioTranscriptionSettings = function() {
                    try { $loadSettingsInject } catch (e) { console.error('[SettingsBridge] Load transcription settings error', e); }
                };
                window.__saveAudioTranscriptionSettings = function(payload) {
                    if (!payload) return;
                    try { $saveSettingsInject } catch (e) { console.error('[SettingsBridge] Save transcription settings error', e); }
                };
                window.__loadGlobalSettings = function() {
                    try { $loadGlobalSettingsInject } catch (e) { console.error('[SettingsBridge] Load global settings error', e); }
                };
                window.__saveGlobalSettings = function(payload) {
                    if (!payload) return;
                    try { $saveGlobalSettingsInject } catch (e) { console.error('[SettingsBridge] Save global settings error', e); }
                };
                window.dispatchEvent(new CustomEvent('settings-bridge-ready'));
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun push(state: AudioTranscriptionFeatureState) {
        val payload = json.encodeToString(state).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onAudioTranscriptionFeature) window.__onAudioTranscriptionFeature(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun pushResult(result: AudioTranscriptionResultPayload) {
        val payload = json.encodeToString(result).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onAudioTranscriptionResult) window.__onAudioTranscriptionResult(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun pushRecordingState(state: AudioRecordingStatePayload) {
        val payload = json.encodeToString(state).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onAudioRecordingState) window.__onAudioRecordingState(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun pushTranscriptionSettings(settings: AudioTranscriptionSettings) {
        val payload = json.encodeToString(settings).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onAudioTranscriptionSettings) window.__onAudioTranscriptionSettings(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun pushGlobalSettings(settings: GlobalSettingsPayload) {
        val payload = json.encodeToString(settings).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onGlobalSettings) window.__onGlobalSettings(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private suspend fun handleExecutionTargetSwitch(current: GlobalSettings, requested: GlobalSettings) {
        val normalizedRequested = requested.copy(
            wslDistributionName = AcpExecutionMode.selectedWslDistributionName(requested.wslDistributionName)
        )
        if (normalizedRequested.useWslForAcpAdapters && normalizedRequested.wslDistributionName.isBlank()) {
            showErrorDialog(
                title = "WSL Distribution Is Not Available",
                message = "Unable to switch agent execution to WSL because no WSL distribution is available."
            )
            pushGlobalSettings(
                GlobalSettingsPayload(
                    settings = current,
                    host = HostSettingsInfo.resolve()
                )
            )
            return
        }
        val previousTarget = if (current.useWslForAcpAdapters) AcpExecutionTarget.WSL else AcpExecutionTarget.LOCAL
        val saved = GlobalSettingsStore.save(normalizedRequested)
        val nextTarget = if (saved.useWslForAcpAdapters) AcpExecutionTarget.WSL else AcpExecutionTarget.LOCAL
        AcpExecutionMode.resetWslRuntimeCaches()

        pushExecutionTargetSwitched(nextTarget)

        val switchSucceeded = runCatching {
            acpService.switchExecutionTarget(previousTarget)
        }.onFailure { error ->
            GlobalSettingsStore.save(current)
            requestAdaptersRefresh()
            showErrorDialog(
                title = "Unable To Switch Agent Environment",
                message = error.message ?: "The execution environment switch failed."
            )
        }.isSuccess

        if (switchSucceeded) {
            requestHistoryRefresh()
        }

        pushGlobalSettings(
            GlobalSettingsPayload(
                settings = GlobalSettingsStore.load(),
                host = HostSettingsInfo.resolve()
            )
        )
    }

    private fun showErrorDialog(title: String, message: String) {
        ApplicationManager.getApplication().invokeAndWait {
            Messages.showErrorDialog(project, message, title)
        }
    }

    private fun pushExecutionTargetSwitched(target: AcpExecutionTarget) {
        val payload = json.encodeToString(
            ExecutionTargetSwitchPayload(
                executionTarget = target.name.lowercase()
            )
        ).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                """
                if(window.__onExecutionTargetSwitched) window.__onExecutionTargetSwitched(JSON.parse('$payload'));
                try { window.__requestAdapters && window.__requestAdapters(); } catch (e) {}
                """.trimIndent(),
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun requestAdaptersRefresh() {
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "try { window.__requestAdapters && window.__requestAdapters(); } catch (e) {}",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun requestHistoryRefresh() {
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "try { window.__requestHistoryList && window.__requestHistoryList(); } catch (e) {}",
                browser.cefBrowser.url,
                0
            )
        }
    }
}
