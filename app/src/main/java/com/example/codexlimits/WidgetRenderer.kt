package com.example.codexlimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetRenderer {
    private const val STALE_AFTER_MILLIS = 10 * 60 * 1000L
    private const val REFRESH_REQUEST_VISIBLE_MILLIS = 60 * 1000L

    fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

    fun statusSummary(context: Context): String {
        val snapshot = SnapshotStore.snapshot(context) ?: return "No account data fetched yet."
        val five = windowLabel("5 hours", snapshot.fiveHours)
        val week = windowLabel("Weekly", snapshot.weekly)
        val error = SnapshotStore.error(context)?.let { " • $it" }.orEmpty()
        return "$five • $week\nLast fetched ${localTime(snapshot.fetchedAtMillis)}$error"
    }

    fun updateAll(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        val snapshot = SnapshotStore.snapshot(context)
        val error = SnapshotStore.error(context)
        val refreshRequestedAt = SnapshotStore.refreshRequestedAt(context)
        val views = RemoteViews(context.packageName, R.layout.widget_limits)
        views.setTextViewText(R.id.widget_five, windowLabel("5 hours", snapshot?.fiveHours))
        views.setTextViewText(R.id.widget_five_reset, resetLabel(snapshot?.fiveHours))
        views.setTextViewText(R.id.widget_week, windowLabel("Weekly", snapshot?.weekly))
        views.setTextViewText(R.id.widget_week_reset, resetLabel(snapshot?.weekly))
        views.setTextViewText(
            R.id.widget_fetched,
            snapshot?.let { "Last fetched: ${localTime(it.fetchedAtMillis)}" } ?: "Last fetched: Never"
        )
        val stale = snapshot != null && System.currentTimeMillis() - snapshot.fetchedAtMillis > STALE_AFTER_MILLIS
        val refreshing = refreshRequestedAt > 0 &&
            System.currentTimeMillis() - refreshRequestedAt < REFRESH_REQUEST_VISIBLE_MILLIS
        views.setTextViewText(
            R.id.widget_status,
            error ?: if (snapshot == null) "Sign in in app" else if (refreshing) "Refresh requested" else if (stale) "Stale data" else "Current"
        )
        val refreshIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_WIDGET_REFRESH)
        val refresh = PendingIntent.getActivity(
            context, 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
        views.setOnClickPendingIntent(R.id.widget_title, open)
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    private fun windowLabel(name: String, window: LimitWindow?): String =
        if (window == null) "$name: Unavailable" else "$name: ${window.remainingPercent}% remaining"

    private fun resetLabel(window: LimitWindow?): String =
        window?.let { "Resets ${localTime(it.resetAtSeconds * 1000)}" } ?: "Reset: Unavailable"

    private fun localTime(epochMillis: Long): String =
        FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, LimitsWidgetProvider::class.java))

    private val FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
}
