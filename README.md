# Codex limits Android proof

This is the first gate for a phone-only Codex limits widget. It opens the ChatGPT usage dashboard in an Android WebView and runs a local JavaScript probe for visible limit-related text. It does **not** yet contain a widget or background refresh. Those depend on proving that sign-in and a real five-hour and weekly read work on Android.

The probe keeps all output on screen. It does not send page text to another server, extract cookies or tokens, or store the result. It also lists possible dashboard request **paths** while excluding query strings, headers, and response bodies; long opaque path segments are masked. The **Clear** button removes this app's WebView cookies and web storage. This proof app's WebView cookie storage is not the intended Keystore-protected authentication design for the final app.

The included GitHub Actions workflow builds a sideloadable debug APK on `ubuntu-24.04`, whose hosted runner includes Android SDK platform 35 and build tools. To use it, put this directory at the root of a GitHub repository, push to `main`, open **Actions → Build Android proof APK**, and download the `codex-limits-proof-apk` artifact. Unzip it and install `app-debug.apk` on the phone. The APK is a debug build for personal testing, not a release-signed package.

To test on an Android 14+ phone or emulator:

1. Install the GitHub Actions APK, or build `app:assembleDebug` locally with Android SDK 35 and JDK 17.
2. Open the app. Sign in inside its WebView, then navigate to the Codex usage dashboard. If the initial URL moves or fails, tap **Docs link** and follow the official usage dashboard link on that page.
3. Tap **Probe**. Compare the shown lines with the visible dashboard, especially five-hour/weekly percentages and reset times. Reopen the app and repeat to test session persistence.
4. Tap **Clear** and confirm the session is gone. Record whether sign-in works, whether the limit lines are present, and whether values change after a refresh.

Use **Copy Probe** or **Copy JSON** after the corresponding button to copy that result, even if the other result is currently shown. **Copy Docs URL** copies the official pricing documentation URL opened by **Docs link**. Results stay only in app memory until the app closes or **Clear** is tapped; the app copies only when you explicitly tap a copy button.

Once the page shows the limits, **Data JSON** makes an authenticated same-origin `GET https://chatgpt.com/backend-api/wham/usage` from inside the WebView. It displays only top-level field names and the `rate_limit`, `rate_limits`, `rateLimits`, or `rateLimitsByLimitId` fields, capped at 10,000 characters. The request sends the WebView's existing session credentials to ChatGPT; the app neither copies them nor logs them. This endpoint is undocumented and may change or disappear. The button tests whether its response matches the visible dashboard and the documented App Server rate-limit data contract.

If that same-origin request returns 401, the app tries `GET https://chatgpt.com/api/auth/session` and, only if the JSON supplies an `accessToken` or `access_token`, repeats the usage request with a Bearer header. The token stays in WebView JavaScript memory and is never displayed or passed to Kotlin. The result shows only HTTP status codes, session field **names**, and the original dashboard request's header **names**. A failed result does not prove that the dashboard is inaccessible; it identifies which authentication step still needs investigation.

An updated GitHub Actions debug APK may have a different debug signing key. If Android says the update is incompatible, uninstall the previous proof APK before installing this one, then sign in again.

Reading visible dashboard text is inherently brittle because it is not a documented API contract. A successful manual probe would not yet prove reliable unattended refresh. Do not build the widget from guessed or hard-coded values if this gate fails.
