package com.example.codexlimits

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock

/** One-off deferrable jobs avoid Android's 15-minute minimum periodic interval. */
object RefreshScheduler {
    private const val JOB_ID = 75104
    private const val TWO_MINUTES = 2 * 60 * 1000L

    fun schedule(
        context: Context,
        delayMillis: Long = TWO_MINUTES,
        expedited: Boolean = false
    ) {
        if (!WidgetRenderer.hasWidgets(context) || SnapshotStore.isSignedOut(context)) return
        val runId = SystemClock.elapsedRealtimeNanos()
        val result = runCatching {
            val builder = JobInfo.Builder(JOB_ID, ComponentName(context, LimitRefreshJobService::class.java))
                .setExtras(PersistableBundle().apply { putLong("run_id", runId) })
            if (expedited) builder.setExpedited(true)
            else builder.setMinimumLatency(delayMillis.coerceAtLeast(0))
            context.getSystemService(JobScheduler::class.java).schedule(builder.build())
        }
        if (result.getOrNull() != JobScheduler.RESULT_SUCCESS) {
            DiagnosticLog.append(context, "Refresh scheduling rejected")
            SnapshotStore.markError(context, "Refresh scheduling was rejected")
            WidgetRenderer.updateAll(context)
        } else {
            DiagnosticLog.append(
                context,
                "Refresh job scheduled: ${if (expedited) "tap" else "routine"} run=$runId"
            )
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
