package com.example.codexlimits

import android.app.Activity
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
import org.json.JSONTokener
import org.json.JSONObject

/** A local-only feasibility probe. It neither copies cookies nor persists account data. */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val requestPaths = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        result.movementMethod = android.text.method.ScrollingMovementMethod()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                status.text = "Page loaded. If signed in, tap Probe."
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
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        findViewById<Button>(R.id.open_dashboard).setOnClickListener {
            webView.loadUrl(DASHBOARD_URL)
        }
        findViewById<Button>(R.id.open_docs).setOnClickListener {
            webView.loadUrl(PRICING_DOCS_URL)
        }
        findViewById<Button>(R.id.probe).setOnClickListener { probePage() }
        findViewById<Button>(R.id.read_data).setOnClickListener { readStructuredData() }
        findViewById<Button>(R.id.clear_session).setOnClickListener { clearSession() }

        if (savedInstanceState == null) webView.loadUrl(DASHBOARD_URL)
        else webView.restoreState(savedInstanceState)
    }

    private fun probePage() {
        if (!isChatGptPage()) {
            result.text = "The current page is not ChatGPT. Finish sign-in, then open the dashboard."
            return
        }

        // Only the current page's visible, limit-related lines reach Kotlin. No cookies,
        // localStorage, tokens, full DOM, or network response bodies are requested.
        webView.evaluateJavascript(PROBE_SCRIPT) { jsonValue ->
            val text = runCatching { JSONTokener(jsonValue).nextValue() as String }
                .getOrElse { "Could not decode the page probe." }
            val paths = synchronized(requestPaths) { requestPaths.take(24) }
            result.text = buildString {
                append(text)
                append("\n\nPossible dashboard request paths (no query strings):\n")
                append(if (paths.isEmpty()) "None observed." else paths.joinToString("\n"))
            }
            status.text = "Probe complete. Compare these lines with the dashboard."
        }
    }

    private fun readStructuredData() {
        if (!isChatGptPage()) {
            result.text = "Open the signed-in ChatGPT dashboard before reading data."
            return
        }
        status.text = "Reading structured limits from this WebView session…"
        result.text = "Waiting for /backend-api/wham/usage…"
        webView.evaluateJavascript(START_DATA_READ_SCRIPT) { pollStructuredData(0) }
    }

    private fun pollStructuredData(attempt: Int) {
        if (attempt >= 30) {
            status.text = "Data request timed out."
            result.text = "No response after 15 seconds. Reload the dashboard and try again."
            return
        }
        mainHandler.postDelayed({
            webView.evaluateJavascript("JSON.stringify(window.__codexLimitsDataProbe || {state:'pending'})") { encoded ->
                val payload = runCatching { JSONTokener(encoded).nextValue() as String }.getOrNull()
                val data = runCatching { JSONObject(payload ?: "{}") }.getOrNull()
                when (data?.optString("state")) {
                    "ok" -> {
                        status.text = "Structured data read succeeded."
                        result.text = data.optString("data", "No rate-limit fields found.")
                    }
                    "error" -> {
                        status.text = "Structured data read failed."
                        result.text = data.optString("message", "Unknown error")
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

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            requestPaths.clear()
            result.text = "Session cleared."
            status.text = "Sign in again to test session removal."
            webView.loadUrl(DASHBOARD_URL)
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
        private const val DASHBOARD_URL = "https://chatgpt.com/codex/settings/usage"
        private const val PRICING_DOCS_URL = "https://learn.chatgpt.com/docs/pricing"
        private val DATA_PATH_PATTERN = Regex("usage|limit|codex|wham", RegexOption.IGNORE_CASE)
        private val STATIC_ASSET_PATTERN = Regex("\\.(js|css|png|jpg|jpeg|svg|webp|woff2?)$", RegexOption.IGNORE_CASE)
        private val OPAQUE_SEGMENT_PATTERN = Regex("/[A-Za-z0-9_-]{32,}(?=/|$)")
        private val START_DATA_READ_SCRIPT = """
            (function () {
              window.__codexLimitsDataProbe = {state: 'pending'};
              fetch('/backend-api/wham/usage', {
                method: 'GET', credentials: 'same-origin', cache: 'no-store'
              }).then(async response => {
                if (!response.ok) throw new Error('HTTP ' + response.status);
                const body = await response.json();
                const limits = {};
                for (const key of ['rate_limit', 'rate_limits', 'rateLimits', 'rateLimitsByLimitId']) {
                  if (Object.prototype.hasOwnProperty.call(body, key)) limits[key] = body[key];
                }
                window.__codexLimitsDataProbe = {
                  state: 'ok',
                  data: JSON.stringify({topLevelKeys: Object.keys(body), limits}, null, 2).slice(0, 10000)
                };
              }).catch(error => {
                window.__codexLimitsDataProbe = {
                  state: 'error', message: String(error && error.message || error)
                };
              });
              return 'started';
            })();
        """.trimIndent()
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
