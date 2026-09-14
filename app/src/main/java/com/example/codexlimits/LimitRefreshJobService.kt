package com.example.codexlimits

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient

/** Best-effort screen-active refresh using the app's signed-in WebView session. */
class LimitRefreshJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var finished = false
    private var readStarted = false

    override fun onStartJob(params: JobParameters): Boolean {
        finished = false
        readStarted = false
        if (!WidgetRenderer.hasWidgets(this) || SnapshotStore.isSignedOut(this)) return false
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            handler.post { RefreshScheduler.schedule(this) }
            return false
        }
        handler.postDelayed({ fail(params, "Refresh timed out") }, 30_000)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (finished || readStarted) return
                    if (!url.startsWith("https://chatgpt.com/")) {
                        fail(params, "Sign in required")
                        return
                    }
                    readStarted = true
                    view.evaluateJavascript(UsagePageClient.START_SCRIPT) { poll(params, 0) }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) fail(params, "Network unavailable")
                }
            }
            loadUrl(UsagePageClient.DASHBOARD_URL)
        }
        return true
    }

    private fun poll(params: JobParameters, attempt: Int) {
        if (finished) return
        if (attempt >= 40) {
            fail(params, "Refresh timed out")
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
                            .onFailure { fail(params, "Limit data unavailable") }
                    }
                    "error" -> fail(params, "Refresh failed; sign in or try again")
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
