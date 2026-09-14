package com.example.codexlimits

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class LimitsWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        RefreshScheduler.cancel(context)
        WidgetRenderer.updateAll(context)
    }

    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        RefreshScheduler.cancel(context)
        WidgetRenderer.updateAll(context)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            SnapshotStore.markRefreshRequested(context)
            WidgetRenderer.updateAll(context)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_WIDGET_REFRESH)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.codexlimits.REFRESH"
    }
}
