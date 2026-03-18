package unified.llm.acp

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import unified.llm.changes.AgentDiffViewer
import unified.llm.changes.ChangesState
import unified.llm.changes.ChangesStateService
import unified.llm.changes.UndoFileHandler
import unified.llm.changes.UndoOperation
import java.io.File


internal fun AcpBridge.installFileChangeQueries() {
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

}

internal fun AcpBridge.installMiscQueries() {
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

}
