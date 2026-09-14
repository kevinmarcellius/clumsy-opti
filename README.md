# Codex limits widget for Android

This personal, sideloaded Android app displays your Codex five-hour and weekly **remaining percentage**, local reset time, and last successful fetch in a home-screen widget. It uses the account data returned to the signed-in ChatGPT dashboard. Missing windows display **Unavailable**. A refresh error leaves the last successful values visible with an error or stale label.

## Install and use

Push to `main` in [kevinmarcellius/clumsy-opti](https://github.com/kevinmarcellius/clumsy-opti), open **Actions → Build Android widget APK**, download the `codex-limits-widget-apk` artifact, unzip it, and install `app-debug.apk` on Android 14 or newer. Open the app, sign in to ChatGPT in its WebView, then tap **Data JSON** once. That first successful read saves the display values. Add **Codex Limits** from the home-screen widget picker. Tap **↻ Refresh** on the widget to open the app and immediately read the current data through its signed-in WebView.

The app keeps **Probe**, **Data JSON**, and their copy buttons for diagnostics. **Copy Docs URL** copies the official pricing documentation link. Results are copied only after an explicit tap. **Clear** signs out by clearing WebView cookies and storage, deleting displayed account data, and stopping scheduled refreshes. A reinstall also clears app data.

GitHub Actions produces a debug-signed APK. The debug signing key may change between runs; if Android rejects an update, uninstall the old debug APK, then install the new one and sign in again.

## Data request and maintenance risk

The phone test on September 14, 2026 found this request flow inside the signed-in WebView:

1. `GET https://chatgpt.com/api/auth/session` with the WebView's same-origin session returns an `accessToken`.
2. `GET https://chatgpt.com/backend-api/wham/usage` with `Authorization: Bearer <accessToken>` returns the usage JSON. A request without that Bearer header returned 401; the authenticated retry returned 200.

For a manual **Data JSON** read, the access token stays in WebView JavaScript memory. For a scheduled widget refresh, the app reads its WebView cookies through Android's `CookieManager`, sends them in the `Cookie` header of the HTTPS session request, then uses the returned access token in the `Authorization: Bearer` header of the HTTPS usage request. Both requests also send `Accept: application/json`, the WebView user agent, and the dashboard `Referer`. The cookie and access token exist briefly in Kotlin memory during a refresh, but are never written to widget preferences, diagnostics, or logs. Only the parsed limit values are saved. Kotlin selects an explicitly identified `codex` bucket when present; the observed legacy `rate_limit` object has no `limit_id`, so the app uses that root object as the Codex fallback. It selects the five-hour and weekly windows by `limit_window_seconds` (18,000 and 604,800) or the documented equivalent `windowDurationMins`, computes `100 - used_percent`, and converts `reset_at` Unix seconds to the phone's local time. The [Codex App Server rate-limit documentation](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt) supplies the documented field semantics, but these two browser URLs are **undocumented** and may change, require new headers, or stop working.

WebView manages the sign-in cookies in its app-private storage, with Android backup disabled. **Those cookies are not wrapped by this app's Android Keystore**, so this beta does not yet meet the original Keystore requirement. The app writes only rendered limit values and refresh state to `SharedPreferences`. Do not treat the beta as a completed security or background-refresh validation.

## Refresh behavior and checks

The phone test found that native HTTPS requests from a scheduled job cannot resolve `chatgpt.com`, even while the same phone's signed-in WebView can read the dashboard. The app therefore does not schedule the failing background requests. Tapping **↻ Refresh** opens the app and automatically performs the known-working signed-in WebView request; it changes the widget state to **Refresh requested** until that read succeeds or reports an error in the app. The widget always shows the last successful fetch time and marks data stale after ten minutes when it is redrawn.

On the phone, verify that the widget's values and reset times match the dashboard after widget-initiated refresh, across a limit change, and after a window reset. Also test expired sign-in, offline mode, sign-out, and reinstall. Background two-minute refresh remains blocked by the phone's native DNS behavior; manual `Data JSON` and widget-initiated refresh are phone-only, use the same signed-in WebView session, and do not require a companion service.
