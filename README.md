# Codex limits widget for Android

This personal, sideloaded Android app displays your Codex five-hour and weekly **remaining percentage**, local reset time, and last successful fetch in a home-screen widget. It uses the account data returned to the signed-in ChatGPT dashboard. Missing windows display **Unavailable**. A refresh error leaves the last successful values visible with an error or stale label.

## Install and use

Push to `main` in [kevinmarcellius/clumsy-opti](https://github.com/kevinmarcellius/clumsy-opti), open **Actions → Build Android widget APK**, download the `codex-limits-widget-apk` artifact, unzip it, and install `app-debug.apk` on Android 14 or newer. Open the app, sign in to ChatGPT in its WebView, then tap **Data JSON** once. That first successful read saves the display values. Add **Codex Limits** from the home-screen widget picker. Tap **↻ Refresh** on the widget to request a background refresh. Tap the widget title to open the app if you need to refresh with **Data JSON** manually.

The app keeps **Probe**, **Data JSON**, and their copy buttons for diagnostics. **Copy Docs URL** copies the official pricing documentation link. **View widget logs** opens a selectable text box with timestamped refresh events; **Copy all** copies the full log, and **Clear** on that page deletes it. Logs include only request stages, HTTP statuses, and WebView error codes for the two known API paths—never headers, cookies, tokens, response bodies, or query strings. **Clear** on the main page signs out by clearing WebView cookies and storage, deleting displayed account data and logs, and stopping scheduled refreshes. A reinstall also clears app data.

GitHub Actions produces a debug-signed APK. The debug signing key may change between runs; if Android rejects an update, uninstall the old debug APK, then install the new one and sign in again.

## Data request and maintenance risk

The phone test on September 14, 2026 found this request flow inside the signed-in WebView:

1. `GET https://chatgpt.com/api/auth/session` with the WebView's same-origin session returns an `accessToken`.
2. `GET https://chatgpt.com/backend-api/wham/usage` with `Authorization: Bearer <accessToken>` returns the usage JSON. The app sends these requests in this order. Earlier phone testing found that a usage request without the Bearer header returned 401.

The app performs both manual and background requests inside WebView JavaScript, so the access token stays in JavaScript memory. The background job loads a small local HTML document with a valid `https://chatgpt.com` base URL, allowing same-origin `fetch()` with the signed-in WebView session without loading the dashboard page. No cookie or access token is returned to Kotlin, written to widget preferences, included in diagnostics, or logged. Only the parsed limit values are saved. Kotlin selects an explicitly identified `codex` bucket when present; the observed legacy `rate_limit` object has no `limit_id`, so the app uses that root object as the Codex fallback. It selects the five-hour and weekly windows by `limit_window_seconds` (18,000 and 604,800) or the documented equivalent `windowDurationMins`, computes `100 - used_percent`, and converts `reset_at` Unix seconds to the phone's local time. The [Codex App Server rate-limit documentation](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt) supplies the documented field semantics, but these two browser URLs are **undocumented** and may change, require new headers, or stop working.

WebView manages the sign-in cookies in its app-private storage, with Android backup disabled. **Those cookies are not wrapped by this app's Android Keystore**, so this beta does not yet meet the original Keystore requirement. The app writes only rendered limit values and refresh state to `SharedPreferences`. Do not treat the beta as a completed security or background-refresh validation.

## Refresh behavior and checks

The phone test found that native HTTPS requests from a scheduled job cannot resolve `chatgpt.com`, even while the same phone's signed-in WebView can read the dashboard. The current background job uses WebView networking from local HTML rather than native HTTPS or a full dashboard page load. Phone logs on September 14 showed successful background usage reads, but repeated stop callbacks and job replacement could leave the widget showing **Refresh requested**. Each scheduled run now has an identifier, callbacks from older runs cannot alter the active one, and a replaced job no longer requests another retry. The diagnostic log records taps, scheduling, run IDs, widget render status, and Android stop reasons so this behavior can be verified.

While a widget is installed, a one-off deferrable job is requested about two minutes after each attempt and runs only when the screen is interactive. Android may delay or throttle it. Tapping **↻ Refresh** requests an expedited background job and shows **Refresh requested** until the read succeeds or fails. A second tap within 30 seconds is ignored while that request is pending. Session and usage requests retry up to two times after a transient JavaScript fetch error, with one- and two-second pauses; HTTP errors are not retried. The widget retains the last successful fetch time and marks data stale after ten minutes when redrawn.

On the phone, verify that the widget's values and reset times match the dashboard after widget-initiated refresh, across a limit change, and after a window reset. Also test expired sign-in, offline mode, sign-out, and reinstall. Record the actual intervals between successful background fetches; two minutes is a request, not a guarantee. If a background read fails, open **View widget logs**, tap **Reload**, and use **Copy all** to share the diagnostic timeline. The local-HTML background approach still requires phone validation.
