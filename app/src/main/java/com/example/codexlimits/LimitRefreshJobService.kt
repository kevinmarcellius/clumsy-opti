package com.example.codexlimits

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Screen-active background read through the same WebView session as MainActivity. */
class LimitRefreshJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var finished = false
    private var readStarted = false

    override fun onStartJob(params: JobParameters): Boolean {
        finished = false
        readStarted = false
        if (!WidgetRenderer.hasWidgets(this) || SnapshotStore.isSignedOut(this)) return false
        DiagnosticLog.append(this, "Background job started")
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            DiagnosticLog.append(this, "Background job deferred: screen not interactive")
            RefreshScheduler.schedule(this)
            return false
        }

        handler.postDelayed({ fail(params, "Background refresh timed out") }, 30_000)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (finished || readStarted) return
                    readStarted = true
                    DiagnosticLog.append(this@LimitRefreshJobService, "Local WebView document loaded")
                    view.evaluateJavascript(UsagePageClient.START_SCRIPT) { poll(params, 0) }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    val stage = requestStage(request.url.path.orEmpty()) ?: return
                    DiagnosticLog.append(
                        this@LimitRefreshJobService,
                        "$stage WebView network error code ${error.errorCode}"
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    val stage = requestStage(request.url.path.orEmpty()) ?: return
                    DiagnosticLog.append(
                        this@LimitRefreshJobService,
                        "$stage HTTP ${errorResponse.statusCode}"
                    )
                }
            }
            // This locally supplied page has the ChatGPT HTTPS origin. It needs no
            // main-frame network load, while same-origin fetch uses WebView cookies.
            loadDataWithBaseURL(
                UsagePageClient.DASHBOARD_URL,
                "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                "text/html",
                "UTF-8",
                UsagePageClient.DASHBOARD_URL
            )
        }
        return true
    }

    private fun poll(params: JobParameters, attempt: Int) {
        if (finished) return
        if (attempt >= 40) {
            fail(params, "Background request timed out")
            return
        }
        handler.postDelayed({
            val view = webView ?: return@postDelayed
            view.evaluateJavascript(UsagePageClient.POLL_SCRIPT) { encoded ->
                if (finished) return@evaluateJavascript
                val data = UsagePageClient.decodePoll(encoded)
                when (data?.optString("state")) {
                    "ok" -> {
                        runCatching { LimitParser.parse(data.getString("payload")) }
                            .onSuccess {
                                DiagnosticLog.append(this, "Background usage read succeeded")
                                SnapshotStore.save(this, it)
                                WidgetRenderer.updateAll(this)
                                finish(params)
                            }
                            .onFailure { fail(params, "Background limit data unavailable") }
                    }
                    "error" -> {
                        DiagnosticLog.append(this, probeDetails(data))
                        fail(params, probeError(data))
                    }
                    else -> poll(params, attempt + 1)
                }
            }
        }, 500)
    }

    private fun fail(params: JobParameters, message: String) {
        if (finished) return
        DiagnosticLog.append(this, message)
        SnapshotStore.markError(this, message)
        WidgetRenderer.updateAll(this)
        finish(params)
    }

    private fun probeError(data: JSONObject): String {
        if (data.optString("origin") != "https://chatgpt.com") return "Background page origin mismatch"
        return when (data.optString("stage")) {
            "direct-usage" -> "Background direct usage fetch failed"
            "session" -> "Background session fetch failed"
            "authorized-usage" -> "Background usage fetch failed"
            else -> "Background WebView request failed"
        }
    }

    private fun probeDetails(data: JSONObject): String {
        val stage = when (data.optString("stage")) {
            "direct-usage", "session", "authorized-usage" -> data.optString("stage")
            else -> "unknown"
        }
        val origin = if (data.optString("origin") == "https://chatgpt.com") "chatgpt.com" else "other"
        val kind = when (data.optString("errorName")) {
            "TypeError", "SyntaxError", "Error" -> data.optString("errorName")
            else -> "other"
        }
        val reason = when {
            data.optString("message").contains("Failed to fetch", ignoreCase = true) -> "Failed to fetch"
            data.optString("message").startsWith("{") -> "Unsuccessful HTTP response"
            else -> "Other WebView error"
        }
        return "Fetch failed: stage=$stage origin=$origin type=$kind reason=$reason " +
            "directHTTP=${data.optInt("directStatus", 0)} " +
            "sessionHTTP=${data.optInt("sessionStatus", 0)} " +
            "usageHTTP=${data.optInt("authorizedStatus", 0)}"
    }

    private fun requestStage(path: String): String? = when (path) {
        "/api/auth/session" -> "Session"
        "/backend-api/wham/usage" -> "Usage"
        else -> null
    }

    private fun finish(params: JobParameters) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        jobFinished(params, false)
        RefreshScheduler.schedule(this)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        DiagnosticLog.append(this, "Background job stopped by Android")
        finished = true
        handler.removeCallbacksAndMessages(null)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        return true
    }
}
