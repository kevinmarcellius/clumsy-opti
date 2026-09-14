package com.example.codexlimits

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class LimitWindow(val remainingPercent: Int, val resetAtSeconds: Long)

data class LimitSnapshot(
    val fiveHours: LimitWindow?,
    val weekly: LimitWindow?,
    val fetchedAtMillis: Long,
    val bucketId: String
)

/** Parses only the Codex bucket and matches windows by returned duration. */
object LimitParser {
    private const val FIVE_HOURS_SECONDS = 5 * 60 * 60L
    private const val WEEK_SECONDS = 7 * 24 * 60 * 60L

    fun parse(json: String, fetchedAtMillis: Long = System.currentTimeMillis()): LimitSnapshot {
        val root = JSONObject(json)
        val candidates = mutableListOf<Candidate>()
        collect(root, null, candidates, 0)

        val selected = candidates.firstOrNull { it.id.equals("codex", ignoreCase = true) }
            ?: root.optJSONObject("rate_limit")?.let { Candidate("codex (legacy)", it) }
            ?: throw IllegalArgumentException("Codex limit bucket unavailable")

        val windows = listOfNotNull(
            selected.value.optJSONObject("primary_window"),
            selected.value.optJSONObject("secondary_window"),
            selected.value.optJSONObject("primary"),
            selected.value.optJSONObject("secondary")
        )
        return LimitSnapshot(
            fiveHours = windows.firstNotNullOfOrNull { parseWindow(it, FIVE_HOURS_SECONDS) },
            weekly = windows.firstNotNullOfOrNull { parseWindow(it, WEEK_SECONDS) },
            fetchedAtMillis = fetchedAtMillis,
            bucketId = selected.id ?: "codex (legacy)"
        )
    }

    private fun parseWindow(value: JSONObject, requestedSeconds: Long): LimitWindow? {
        val duration = when {
            value.has("limit_window_seconds") -> value.optLong("limit_window_seconds", -1)
            value.has("windowDurationMins") -> value.optLong("windowDurationMins", -1) * 60
            else -> -1
        }
        if (duration != requestedSeconds) return null
        val used = when {
            value.has("used_percent") -> value.optDouble("used_percent", Double.NaN)
            else -> value.optDouble("usedPercent", Double.NaN)
        }
        val reset = when {
            value.has("reset_at") -> value.optLong("reset_at", 0)
            else -> value.optLong("resetsAt", 0)
        }
        if (!used.isFinite() || reset <= 0) return null
        return LimitWindow((100.0 - used).coerceIn(0.0, 100.0).roundToInt(), reset)
    }

    private fun collect(value: Any?, inheritedId: String?, output: MutableList<Candidate>, depth: Int) {
        if (depth > 6) return
        when (value) {
            is JSONObject -> {
                val id = value.optString("limit_id").ifBlank {
                    value.optString("limitId").ifBlank { inheritedId.orEmpty() }
                }.takeIf { it.isNotBlank() }
                if (value.has("primary_window") || value.has("secondary_window") ||
                    value.has("primary") || value.has("secondary")
                ) output += Candidate(id, value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val childId = if (key == "codex" || key.startsWith("codex_")) key else id
                    collect(value.opt(key), childId, output, depth + 1)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collect(value.opt(index), inheritedId, output, depth + 1)
            }
        }
    }

    private data class Candidate(val id: String?, val value: JSONObject)
}
