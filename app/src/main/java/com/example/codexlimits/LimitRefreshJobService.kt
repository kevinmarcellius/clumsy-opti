package com.example.codexlimits

import android.app.job.JobParameters
import android.app.job.JobService
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebSettings

/** Best-effort screen-active refresh using the cookies from the signed-in WebView. */
class LimitRefreshJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        finished = false
        if (!WidgetRenderer.hasWidgets(this) || SnapshotStore.isSignedOut(this)) return false
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            RefreshScheduler.schedule(this)
            return false
        }

        // CookieManager is the same app-private store used by MainActivity's WebView.
        // Neither the cookie nor the short-lived access token is persisted by this job.
        val cookie = CookieManager.getInstance().getCookie(UsageHttpClient.SESSION_URL)
        val userAgent = WebSettings.getDefaultUserAgent(this)
        val network = getSystemService(ConnectivityManager::class.java).activeNetwork
        worker = Thread {
            val result = runCatching { UsageHttpClient.read(cookie, userAgent, network) }
            handler.post {
                if (finished) return@post
                result.onSuccess { SnapshotStore.save(this, it) }
                    .onFailure { SnapshotStore.markError(this, UsageHttpClient.safeError(it)) }
                WidgetRenderer.updateAll(this)
                finish(params)
            }
        }.apply { start() }
        return true
    }

    private fun finish(params: JobParameters) {
        if (finished) return
        finished = true
        worker = null
        jobFinished(params, false)
        RefreshScheduler.schedule(this)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        finished = true
        worker?.interrupt()
        worker = null
        return true
    }
}
