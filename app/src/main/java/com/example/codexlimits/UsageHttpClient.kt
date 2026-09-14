package com.example.codexlimits

import android.net.Network
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
        val session = requestWithFallback("Session", SESSION_URL, userAgent, cookie, null, network)
        if (session.status == 401 || session.status == 403) {
            throw AuthFailure()
        }
        if (session.status != 200) throw HttpFailure("Session", session.status)
        val body = JSONObject(session.body)
        val accessToken = body.optString("accessToken").ifBlank { body.optString("access_token") }
        if (accessToken.isBlank()) throw AuthFailure()

        val usage = requestWithFallback("Usage", USAGE_URL, userAgent, cookie, accessToken, network)
        if (usage.status == 401 || usage.status == 403) {
            throw AuthFailure()
        }
        if (usage.status != 200) throw HttpFailure("Usage", usage.status)
        return LimitParser.parse(usage.body)
    }

    fun safeError(error: Throwable): String = when (error) {
        is AuthFailure -> "Sign in required"
        is HttpFailure -> "${error.request} request HTTP ${error.status}"
        is IOException -> error.message?.take(100) ?: "Network unavailable (${error.javaClass.simpleName})"
        else -> "Limit data unavailable"
    }

    private fun requestWithFallback(
        stage: String,
        url: String,
        userAgent: String,
        cookie: String,
        token: String?,
        network: Network?
    ): Response {
        val assigned = runCatching {
            if (network == null) throw IOException("No active Android network")
            request(url, userAgent, cookie, token, network)
        }
        assigned.getOrNull()?.let { return it }
        val assignedFailure = assigned.exceptionOrNull()
            ?: throw IllegalStateException("Network request completed without a result")
        if (assignedFailure !is IOException) throw assignedFailure
        return try {
            request(url, userAgent, cookie, token, null)
        } catch (defaultFailure: IOException) {
            throw IOException(
                "$stage connection failed: ${defaultFailure.javaClass.simpleName}" +
                    defaultFailure.message?.let { " ($it)" }.orEmpty(),
                assignedFailure
            )
        }
    }

    private fun request(
        url: String,
        userAgent: String,
        cookie: String,
        token: String?,
        network: Network?
    ): Response {
        val requestUrl = URL(url)
        val connection = ((network?.openConnection(requestUrl) ?: requestUrl.openConnection()) as HttpURLConnection).apply {
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
