package com.example.codexlimits

import android.content.Context

/** Only display values are stored here. No cookie, session, or access token is written. */
object SnapshotStore {
    private const val NAME = "widget_display"

    fun save(context: Context, snapshot: LimitSnapshot) {
        val editor = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("fetched_at", snapshot.fetchedAtMillis)
            .putString("bucket_id", snapshot.bucketId)
            .remove("error")
            .remove("refresh_requested_at")
            .remove("signed_out")
        if (snapshot.fiveHours == null) editor.remove("five_percent").remove("five_reset")
        else editor.putInt("five_percent", snapshot.fiveHours.remainingPercent)
            .putLong("five_reset", snapshot.fiveHours.resetAtSeconds)
        if (snapshot.weekly == null) editor.remove("week_percent").remove("week_reset")
        else editor.putInt("week_percent", snapshot.weekly.remainingPercent)
            .putLong("week_reset", snapshot.weekly.resetAtSeconds)
        editor.apply()
    }

    fun snapshot(context: Context): LimitSnapshot? {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong("fetched_at", 0)
        if (fetchedAt <= 0) return null
        val five = if (prefs.contains("five_percent") && prefs.contains("five_reset")) {
            LimitWindow(prefs.getInt("five_percent", 0), prefs.getLong("five_reset", 0))
        } else null
        val week = if (prefs.contains("week_percent") && prefs.contains("week_reset")) {
            LimitWindow(prefs.getInt("week_percent", 0), prefs.getLong("week_reset", 0))
        } else null
        return LimitSnapshot(five, week, fetchedAt, prefs.getString("bucket_id", "codex").orEmpty())
    }

    fun error(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("error", null)

    fun refreshRequestedAt(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("refresh_requested_at", 0)

    fun isSignedOut(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("signed_out", false)

    fun markError(context: Context, message: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .remove("refresh_requested_at")
            .putString("error", message.take(100)).apply()
    }

    fun markRefreshRequested(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .remove("error")
            .putLong("refresh_requested_at", System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("signed_out", true).apply()
    }
}
