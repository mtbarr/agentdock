package unified.llm.mcp

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

class McpBridge(
    private val browser: JBCefBrowser,
    private val scope: CoroutineScope
) {
    private var loadQuery: JBCefJSQuery? = null
    private var saveQuery: JBCefJSQuery? = null

    fun install() {
        loadQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler {
                scope.launch(Dispatchers.IO) {
                    push(McpConfigStore.load())
                }
                JBCefJSQuery.Response("ok")
            }
        }

        saveQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase).apply {
            addHandler { payload ->
                if (!payload.isNullOrBlank()) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val servers = json.decodeFromString<List<McpServerConfig>>(payload)
                            McpConfigStore.save(servers)
                            push(servers)
                        } catch (_: Exception) {}
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
    }

    fun injectApi(cefBrowser: CefBrowser) {
        val loadInject = loadQuery?.inject("") ?: "console.error('[McpBridge] Load query not ready')"
        val saveInject = saveQuery?.inject("json") ?: "console.error('[McpBridge] Save query not ready')"

        val script = """
            (function() {
                window.__onMcpServers = window.__onMcpServers || function(servers) {};
                window.__loadMcpServers = function() {
                    try { $loadInject } catch(e) { console.error('[McpBridge] Load error', e); }
                };
                window.__saveMcpServers = function(json) {
                    if (!json) return;
                    try { $saveInject } catch(e) { console.error('[McpBridge] Save error', e); }
                };
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun push(servers: List<McpServerConfig>) {
        val escaped = Json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers).escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onMcpServers) window.__onMcpServers(JSON.parse('$escaped'));",
                browser.cefBrowser.url, 0
            )
        }
    }
}
