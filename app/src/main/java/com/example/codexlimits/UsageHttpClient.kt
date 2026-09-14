package com.example.codexlimits

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Reads usage over HTTPS without saving cookies, access tokens, or response bodies. */
object UsageHttpClient {
    const val SESSION_URL = "https://chatgpt.com/api/auth/session"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val REFERER = "https://chatgpt.com/codex/settings/usage"

    fun read(cookie: String?, userAgent: String, network: Network?): LimitSnapshot {
        if (cookie.isNullOrBlank()) throw AuthFailure()
        val assignedNetwork = network ?: throw IOException("No active network assigned to refresh job")
        val session = request(SESSION_URL, userAgent, cookie, null, assignedNetwork)
        if (session.status == 401 || session.status == 403) {
            throw AuthFailure()
        }
        if (session.status != 200) throw HttpFailure("Session", session.status)
        val body = JSONObject(session.body)
        val accessToken = body.optString("accessToken").ifBlank { body.optString("access_token") }
        if (accessToken.isBlank()) throw AuthFailure()

        val usage = request(USAGE_URL, userAgent, cookie, accessToken, assignedNetwork)
        if (usage.status == 401 || usage.status == 403) {
            throw AuthFailure()
        }
        if (usage.status != 200) throw HttpFailure("Usage", usage.status)
        return LimitParser.parse(usage.body)
    }

    fun safeError(error: Throwable): String = when (error) {
        is AuthFailure -> "Sign in required"
        is HttpFailure -> "${error.request} request HTTP ${error.status}"
        is IOException -> "Network unavailable (${error.javaClass.simpleName})"
        else -> "Limit data unavailable"
    }

    private fun request(
        url: String,
        userAgent: String,
        cookie: String,
        token: String?,
        network: Network
    ): Response {
        val connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Cookie", cookie)
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            val body = if (status == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else ""
            return Response(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private data class Response(val status: Int, val body: String)
    private class AuthFailure : Exception()
    private class HttpFailure(val request: String, val status: Int) : Exception()
}
import android.net.Network
