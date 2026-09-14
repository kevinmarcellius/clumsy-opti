package com.example.codexlimits

import org.json.JSONObject
import org.json.JSONTokener

/** Requests run inside a signed-in ChatGPT WebView; token values never cross to Kotlin. */
object UsagePageClient {
    const val DASHBOARD_URL = "https://chatgpt.com/codex/settings/usage"
    const val POLL_SCRIPT = "JSON.stringify(window.__codexLimitsDataProbe || {state:'pending'})"

    fun decodePoll(encoded: String): JSONObject? = runCatching {
        val value = JSONTokener(encoded).nextValue() as String
        JSONObject(value)
    }.getOrNull()

    val START_SCRIPT = """
        (function () {
          window.__codexLimitsDataProbe = {state: 'pending'};
          const url = '/backend-api/wham/usage';
          const options = {method: 'GET', credentials: 'same-origin', cache: 'no-store'};
          const finish = (body, diagnostic) => {
            const limits = {};
            for (const key of [
              'rate_limit', 'additional_rate_limits', 'rate_limits',
              'rateLimits', 'rateLimitsByLimitId'
            ]) {
              if (Object.prototype.hasOwnProperty.call(body, key)) limits[key] = body[key];
            }
            window.__codexLimitsDataProbe = {
              state: 'ok',
              payload: JSON.stringify(limits),
              data: JSON.stringify({diagnostic, topLevelKeys: Object.keys(body), limits}, null, 2).slice(0, 10000)
            };
          };
          (async () => {
            const diagnostic = {};
            const direct = await fetch(url, options);
            diagnostic.directStatus = direct.status;
            if (direct.ok) return finish(await direct.json(), diagnostic);

            const sessionResponse = await fetch('/api/auth/session', {credentials: 'same-origin'});
            diagnostic.sessionStatus = sessionResponse.status;
            if (!sessionResponse.ok) throw new Error(JSON.stringify(diagnostic));
            const session = await sessionResponse.json();
            diagnostic.sessionKeys = Object.keys(session);
            const token = session.accessToken || session.access_token;
            diagnostic.hasAccessToken = typeof token === 'string' && token.length > 0;
            if (!diagnostic.hasAccessToken) throw new Error(JSON.stringify(diagnostic));

            // Access token remains in page memory and is never returned to native code.
            const authorized = await fetch(url, {
              ...options, headers: {Authorization: 'Bearer ' + token}
            });
            diagnostic.authorizedStatus = authorized.status;
            if (!authorized.ok) throw new Error(JSON.stringify(diagnostic));
            finish(await authorized.json(), diagnostic);
          })().catch(error => {
            window.__codexLimitsDataProbe = {
              state: 'error', message: String(error && error.message || error)
            };
          });
          return 'started';
        })();
    """.trimIndent()
}
