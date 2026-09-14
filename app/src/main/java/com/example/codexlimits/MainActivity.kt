package com.example.codexlimits

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener

/** A local-only feasibility probe. It neither copies cookies nor persists account data. */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var snapshotSummary: TextView
    private val requestPaths = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val usageHeaderNames = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observedHeadersBeforeRead = "None observed."
    private var lastProbeResult = ""
    private var lastDataResult = ""
    private var widgetRefreshStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        snapshotSummary = findViewById(R.id.snapshot_summary)
        result.movementMethod = android.text.method.ScrollingMovementMethod()
        renderSnapshotSummary()
        RefreshScheduler.cancel(this)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            SnapshotStore.markRefreshRequested(this)
            WidgetRenderer.updateAll(this)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (intent.action == ACTION_WIDGET_REFRESH && !widgetRefreshStarted && isChatGptPage()) {
                    widgetRefreshStarted = true
                    readStructuredData()
                } else {
                    status.text = "Page loaded. If signed in, tap Probe."
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.host == "chatgpt.com") {
                    val path = uri.path.orEmpty()
                    if (DATA_PATH_PATTERN.containsMatchIn(path) &&
                        !STATIC_ASSET_PATTERN.containsMatchIn(path) &&
                        requestPaths.size < 40
                    ) {
                        // Never collect query strings, headers, bodies, or cookies.
                        requestPaths.add(path.replace(OPAQUE_SEGMENT_PATTERN, "/[id]"))
                    }
                    if (path == "/backend-api/wham/usage") {
                        // Header names help diagnose the 401; values are never read or stored.
                        usageHeaderNames.addAll(request.requestHeaders.keys)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        findViewById<Button>(R.id.open_dashboard).setOnClickListener {
            webView.loadUrl(UsagePageClient.DASHBOARD_URL)
        }
        findViewById<Button>(R.id.open_docs).setOnClickListener {
            webView.loadUrl(PRICING_DOCS_URL)
        }
        findViewById<Button>(R.id.probe).setOnClickListener { probePage() }
        findViewById<Button>(R.id.read_data).setOnClickListener { readStructuredData() }
        findViewById<Button>(R.id.clear_session).setOnClickListener { clearSession() }
        findViewById<Button>(R.id.copy_probe).setOnClickListener {
            copyText("Dashboard probe", lastProbeResult)
        }
        findViewById<Button>(R.id.copy_data).setOnClickListener {
            copyText("Data JSON", lastDataResult)
        }
        findViewById<Button>(R.id.copy_docs).setOnClickListener {
            copyText("OpenAI Docs URL", PRICING_DOCS_URL)
        }

        if (savedInstanceState == null) webView.loadUrl(UsagePageClient.DASHBOARD_URL)
        else webView.restoreState(savedInstanceState)
    }

    private fun probePage() {
        if (!isChatGptPage()) {
            lastProbeResult = "The current page is not ChatGPT. Finish sign-in, then open the dashboard."
            result.text = lastProbeResult
            return
        }
        lastProbeResult = ""

        // Only the current page's visible, limit-related lines reach Kotlin. No cookies,
        // localStorage, tokens, full DOM, or network response bodies are requested.
        webView.evaluateJavascript(PROBE_SCRIPT) { jsonValue ->
            val text = runCatching { JSONTokener(jsonValue).nextValue() as String }
                .getOrElse { "Could not decode the page probe." }
            val paths = synchronized(requestPaths) { requestPaths.take(24) }
            lastProbeResult = buildString {
                append(text)
                append("\n\nPossible dashboard request paths (no query strings):\n")
                append(if (paths.isEmpty()) "None observed." else paths.joinToString("\n"))
            }
            result.text = lastProbeResult
            status.text = "Probe complete. Compare these lines with the dashboard."
        }
    }

    private fun readStructuredData() {
        if (!isChatGptPage()) {
            lastDataResult = "Open the signed-in ChatGPT dashboard before reading data."
            result.text = lastDataResult
            return
        }
        lastDataResult = ""
        status.text = "Reading structured limits from this WebView session…"
        result.text = "Waiting for /backend-api/wham/usage…"
        observedHeadersBeforeRead = synchronized(usageHeaderNames) {
            usageHeaderNames.take(30).sorted().joinToString(", ").ifEmpty { "None observed." }
        }
        webView.evaluateJavascript(UsagePageClient.START_SCRIPT) { pollStructuredData(0) }
    }

    private fun pollStructuredData(attempt: Int) {
        if (attempt >= 30) {
            status.text = "Data request timed out."
            lastDataResult = "No response after 15 seconds. Reload the dashboard and try again."
            result.text = lastDataResult
            return
        }
        mainHandler.postDelayed({
            webView.evaluateJavascript(UsagePageClient.POLL_SCRIPT) { encoded ->
                val data = UsagePageClient.decodePoll(encoded)
                when (data?.optString("state")) {
                    "ok" -> {
                        lastDataResult = "Dashboard request header names: $observedHeadersBeforeRead\n\n" +
                            data.optString("data", "No rate-limit fields found.")
                        result.text = lastDataResult
                        runCatching {
                            LimitParser.parse(data.getString("payload"))
                        }.onSuccess { snapshot ->
                            SnapshotStore.save(this, snapshot)
                            WidgetRenderer.updateAll(this)
                            renderSnapshotSummary()
                            status.text = "Widget data updated from Codex usage."
                        }.onFailure {
                            SnapshotStore.markError(this, "Limit data unavailable")
                            WidgetRenderer.updateAll(this)
                            renderSnapshotSummary()
                            status.text = "Limit response could not be parsed."
                        }
                    }
                    "error" -> {
                        status.text = "Structured data read failed."
                        lastDataResult = "Dashboard request header names: $observedHeadersBeforeRead\n\n" +
                            data.optString("message", "Unknown error")
                        result.text = lastDataResult
                        SnapshotStore.markError(this, "Refresh failed; sign in or try again")
                        WidgetRenderer.updateAll(this)
                        renderSnapshotSummary()
                    }
                    else -> pollStructuredData(attempt + 1)
                }
            }
        }, 500)
    }

    private fun isChatGptPage(): Boolean {
        val host = android.net.Uri.parse(webView.url ?: "").host.orEmpty()
        return host == "chatgpt.com" || host.endsWith(".chatgpt.com")
    }

    private fun copyText(label: String, value: String) {
        if (value.isBlank()) {
            Toast.makeText(this, "Run the matching probe first.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun renderSnapshotSummary() {
        snapshotSummary.text = WidgetRenderer.statusSummary(this)
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            requestPaths.clear()
            usageHeaderNames.clear()
            lastProbeResult = ""
            lastDataResult = ""
            SnapshotStore.clear(this)
            RefreshScheduler.cancel(this)
            WidgetRenderer.updateAll(this)
            renderSnapshotSummary()
            result.text = "Session cleared."
            status.text = "Sign in again to test session removal."
            webView.loadUrl(UsagePageClient.DASHBOARD_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.example.codexlimits.WIDGET_REFRESH"
        private const val PRICING_DOCS_URL = "https://learn.chatgpt.com/docs/pricing"
        private val DATA_PATH_PATTERN = Regex("usage|limit|codex|wham", RegexOption.IGNORE_CASE)
        private val STATIC_ASSET_PATTERN = Regex("\\.(js|css|png|jpg|jpeg|svg|webp|woff2?)$", RegexOption.IGNORE_CASE)
        private val OPAQUE_SEGMENT_PATTERN = Regex("/[A-Za-z0-9_-]{32,}(?=/|$)")
        private val PROBE_SCRIPT = """
            (function () {
              const text = document.body ? document.body.innerText : '';
              const lines = text.split(/\n+/).map(s => s.trim()).filter(Boolean);
              const pattern = /5[ -]?hour|five[ -]?hour|weekly|per week|\bweek\b|remaining|reset|limit/i;
              const hits = [];
              for (let i = 0; i < lines.length && hits.length < 24; i++) {
                if (!pattern.test(lines[i])) continue;
                const context = lines.slice(Math.max(0, i - 1), Math.min(lines.length, i + 3));
                hits.push(context.map(s => s.slice(0, 120)).join(' | '));
              }
              return hits.length ? hits.join('\n') : 'No visible limit-related text found on this page.';
            })();
        """.trimIndent()
    }
}
