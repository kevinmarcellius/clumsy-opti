package com.example.codexlimits

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A bounded, app-private record of status codes and events. Never pass secrets here. */
object DiagnosticLog {
    private const val NAME = "diagnostic_events"
    private const val KEY = "lines"
    private const val MAX_LINES = 80
    private val FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

    @Synchronized
    fun append(context: Context, event: String) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        val time = FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
        val lines = (existing + "$time  $event").takeLast(MAX_LINES)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?: "No diagnostic events yet. Tap widget Refresh, then return here."

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
