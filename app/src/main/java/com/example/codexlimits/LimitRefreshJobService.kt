package com.example.codexlimits

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        if (!getSystemService(PowerManager::class.java).isInteractive) {
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
                    view.evaluateJavascript(UsagePageClient.START_SCRIPT) { poll(params, 0) }
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
                                SnapshotStore.save(this, it)
                                WidgetRenderer.updateAll(this)
                                finish(params)
                            }
                            .onFailure { fail(params, "Background limit data unavailable") }
                    }
                    "error" -> fail(params, probeError(data.optString("message")))
                    else -> poll(params, attempt + 1)
                }
            }
        }, 500)
    }

    private fun fail(params: JobParameters, message: String) {
        if (finished) return
        SnapshotStore.markError(this, message)
        WidgetRenderer.updateAll(this)
        finish(params)
    }

    private fun probeError(message: String): String {
        val diagnostic = runCatching { JSONObject(message) }.getOrNull()
        if (diagnostic != null) {
            val sessionStatus = diagnostic.optInt("sessionStatus", 0)
            if (sessionStatus != 0) return "Background session HTTP $sessionStatus"
            val usageStatus = diagnostic.optInt("authorizedStatus", 0)
            if (usageStatus != 0) return "Background usage HTTP $usageStatus"
        }
        return "Background fetch: ${message.take(60).ifBlank { "request failed" }}"
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
        finished = true
        handler.removeCallbacksAndMessages(null)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        return true
    }
}
