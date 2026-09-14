package com.example.codexlimits

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** One-off deferrable jobs avoid Android's 15-minute minimum periodic interval. */
object RefreshScheduler {
    private const val JOB_ID = 75104
    private const val TWO_MINUTES = 2 * 60 * 1000L

    fun schedule(context: Context, delayMillis: Long = TWO_MINUTES) {
        if (!WidgetRenderer.hasWidgets(context) || SnapshotStore.isSignedOut(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, LimitRefreshJobService::class.java))
            .setMinimumLatency(delayMillis.coerceAtLeast(0))
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
