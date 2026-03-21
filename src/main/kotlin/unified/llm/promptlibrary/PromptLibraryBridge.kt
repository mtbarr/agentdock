package unified.llm.promptlibrary

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import unified.llm.utils.escapeForJsString

private val json = Json { ignoreUnknownKeys = true }

class PromptLibraryBridge(
    private val browser: JBCefBrowser,
    private val scope: CoroutineScope
) {
    private var loadQuery: JBCefJSQuery? = null
    private var saveQuery: JBCefJSQuery? = null

    fun install() {
        loadQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    push(PromptLibraryStore.load())
                }
                JBCefJSQuery.Response("ok")
            }
        }

        saveQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val prompts = json.decodeFromString(ListSerializer(PromptLibraryItem.serializer()), payload)
                            PromptLibraryStore.save(prompts)
                            push(prompts)
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
    }

    fun injectApi(cefBrowser: CefBrowser) {
        val loadInject = loadQuery?.inject("") ?: "console.error('[PromptLibraryBridge] Load query not ready')"
        val saveInject = saveQuery?.inject("json") ?: "console.error('[PromptLibraryBridge] Save query not ready')"

        val script = """
            (function() {
                window.__onPromptLibrary = window.__onPromptLibrary || function(items) {};
                window.__loadPromptLibrary = function() {
                    try { $loadInject } catch (e) { console.error('[PromptLibraryBridge] Load error', e); }
                };
                window.__savePromptLibrary = function(json) {
                    if (!json) return;
                    try { $saveInject } catch (e) { console.error('[PromptLibraryBridge] Save error', e); }
                };
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun push(prompts: List<PromptLibraryItem>) {
        val payload = Json.encodeToString(ListSerializer(PromptLibraryItem.serializer()), prompts).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onPromptLibrary) window.__onPromptLibrary(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }
}
