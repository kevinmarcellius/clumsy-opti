package com.example.codexlimits

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import org.json.JSONTokener

/** A local-only feasibility probe. It neither copies cookies nor persists account data. */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var result: TextView

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
        }

        findViewById<Button>(R.id.open_dashboard).setOnClickListener {
            webView.loadUrl(DASHBOARD_URL)
        }
        findViewById<Button>(R.id.open_docs).setOnClickListener {
            webView.loadUrl(PRICING_DOCS_URL)
        }
        findViewById<Button>(R.id.probe).setOnClickListener { probePage() }
        findViewById<Button>(R.id.clear_session).setOnClickListener { clearSession() }

        if (savedInstanceState == null) webView.loadUrl(DASHBOARD_URL)
        else webView.restoreState(savedInstanceState)
    }

    private fun probePage() {
        val host = android.net.Uri.parse(webView.url ?: "").host.orEmpty()
        if (host != "chatgpt.com" && !host.endsWith(".chatgpt.com")) {
            result.text = "The current page is not ChatGPT. Finish sign-in, then open the dashboard."
            return
        }

        // Only the current page's visible, limit-related lines reach Kotlin. No cookies,
        // localStorage, tokens, full DOM, or network response bodies are requested.
        webView.evaluateJavascript(PROBE_SCRIPT) { jsonValue ->
            val text = runCatching { JSONTokener(jsonValue).nextValue() as String }
                .getOrElse { "Could not decode the page probe." }
            result.text = text
            status.text = "Probe complete. Compare these lines with the dashboard."
        }
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
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
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DASHBOARD_URL = "https://chatgpt.com/codex/settings/usage"
        private const val PRICING_DOCS_URL = "https://learn.chatgpt.com/docs/pricing"
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
