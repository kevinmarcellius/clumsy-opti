package com.example.codexlimits

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class LimitsWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        WidgetRenderer.updateAll(context)
    }

    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        WidgetRenderer.updateAll(context)
        RefreshScheduler.schedule(context, 0)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val requestedAt = SnapshotStore.refreshRequestedAt(context)
            if (requestedAt > 0 && System.currentTimeMillis() - requestedAt in 0L..30_000L) {
                DiagnosticLog.append(context, "Repeat widget tap ignored: refresh already pending")
                return
            }
            DiagnosticLog.append(context, "Widget refresh tapped")
            SnapshotStore.markRefreshRequested(context)
            WidgetRenderer.updateAll(context)
            RefreshScheduler.schedule(context, expedited = true)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.codexlimits.REFRESH"
    }
}
