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
    private var finished = true
    private var readStarted = false
    private var activeRunId = -1L

    override fun onStartJob(params: JobParameters): Boolean {
        if (!WidgetRenderer.hasWidgets(this) || SnapshotStore.isSignedOut(this)) return false
        val runId = params.extras.getLong("run_id", 0)
        if (!finished) {
            DiagnosticLog.append(this, "Previous background run superseded: run=$activeRunId")
            releaseWebView()
        }
        finished = false
        readStarted = false
        activeRunId = runId
        DiagnosticLog.append(this, "Background job started: run=$runId")
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            DiagnosticLog.append(this, "Background job deferred: screen not interactive, run=$runId")
            SnapshotStore.markError(this, "Refresh deferred until screen is active")
            WidgetRenderer.updateAll(this)
            finished = true
            activeRunId = -1L
            handler.post { RefreshScheduler.schedule(this) }
            return false
        }

        handler.postDelayed({ fail(params, runId, "Background refresh timed out") }, 30_000)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!isActive(runId) || readStarted) return
                    readStarted = true
                    DiagnosticLog.append(this@LimitRefreshJobService, "Local WebView document loaded: run=$runId")
                    view.evaluateJavascript(UsagePageClient.START_SCRIPT) { poll(params, runId, 0) }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!isActive(runId)) return
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
                    if (!isActive(runId)) return
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

    private fun poll(params: JobParameters, runId: Long, attempt: Int) {
        if (!isActive(runId)) return
        if (attempt >= 40) {
            fail(params, runId, "Background request timed out")
            return
        }
        handler.postDelayed({
            if (!isActive(runId)) return@postDelayed
            val view = webView ?: return@postDelayed
            view.evaluateJavascript(UsagePageClient.POLL_SCRIPT) { encoded ->
                if (!isActive(runId)) return@evaluateJavascript
                val data = UsagePageClient.decodePoll(encoded)
                when (data?.optString("state")) {
                    "ok" -> {
                        runCatching { LimitParser.parse(data.getString("payload")) }
                            .onSuccess {
                                DiagnosticLog.append(this, "Background usage read succeeded: run=$runId")
                                SnapshotStore.save(this, it)
                                WidgetRenderer.updateAll(this)
                                finish(params, runId)
                            }
                            .onFailure { fail(params, runId, "Background limit data unavailable") }
                    }
                    "error" -> {
                        DiagnosticLog.append(this, probeDetails(data))
                        fail(params, runId, probeError(data))
                    }
                    else -> poll(params, runId, attempt + 1)
                }
            }
        }, 500)
    }

    private fun fail(params: JobParameters, runId: Long, message: String) {
        if (!isActive(runId)) return
        DiagnosticLog.append(this, message)
        SnapshotStore.markError(this, message)
        WidgetRenderer.updateAll(this)
        finish(params, runId)
    }

    private fun probeError(data: JSONObject): String {
        if (data.optString("origin") != "https://chatgpt.com") return "Background page origin mismatch"
        return when (data.optString("stage")) {
            "session" -> "Background session fetch failed"
            "authorized-usage" -> "Background usage fetch failed"
            else -> "Background WebView request failed"
        }
    }

    private fun probeDetails(data: JSONObject): String {
        val stage = when (data.optString("stage")) {
            "session", "authorized-usage" -> data.optString("stage")
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
            "sessionHTTP=${data.optInt("sessionStatus", 0)} " +
            "usageHTTP=${data.optInt("authorizedStatus", 0)}"
    }

    private fun requestStage(path: String): String? = when (path) {
        "/api/auth/session" -> "Session"
        "/backend-api/wham/usage" -> "Usage"
        else -> null
    }

    private fun isActive(runId: Long): Boolean = !finished && activeRunId == runId

    private fun releaseWebView() {
        handler.removeCallbacksAndMessages(null)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }

    private fun finish(params: JobParameters, runId: Long) {
        if (!isActive(runId)) return
        finished = true
        activeRunId = -1L
        releaseWebView()
        jobFinished(params, false)
        RefreshScheduler.schedule(this)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val runId = params.extras.getLong("run_id", 0)
        if (!isActive(runId)) {
            if (!SnapshotStore.isSignedOut(this)) {
                DiagnosticLog.append(this, "Stop callback ignored for completed run=$runId")
            }
            return false
        }
        val replaced = params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP
        val retry = WidgetRenderer.hasWidgets(this) && !SnapshotStore.isSignedOut(this) && !replaced
        if (!SnapshotStore.isSignedOut(this)) {
            DiagnosticLog.append(this, "Background job interrupted: run=$runId reason=${params.stopReason} retry=$retry")
        }
        finished = true
        activeRunId = -1L
        releaseWebView()
        if (retry) {
            SnapshotStore.markError(this, "Refresh interrupted; retrying")
            WidgetRenderer.updateAll(this)
        }
        return retry
    }
}
