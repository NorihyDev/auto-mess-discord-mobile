# Discord Message Auto · Android

Lightweight native Android version of [discord-mess-auto](https://github.com/NorihyDev/discord-mess-auto). Configure a Discord text channel, a message and a daily monitoring window; the app observes the channel editor and makes one send attempt when it becomes writable.

**Android 8.0+ · no Python on your phone · no separate Chromium download · English interface.**

## Install and use

1. Install the signed `discord-message-auto-1.4.0.apk` built with your HTTPS license server URL (see Build), or an APK from the [releases](https://github.com/NorihyDev/auto-mess-discord-mobile/releases). Enter the activation key given by the administrator before starting a session.
2. Paste a full Discord channel link into **Channel**, then tap **Open Discord** and sign in manually. Complete 2FA or CAPTCHA normally. Tap **Back to setup** to return.
3. Write **Your message** and tap **Edit** beside **Opening time** to choose the time and timezone.
4. Tap **Check without sending** to verify channel readiness without inserting text or sending.
5. Tap **Start watching** and allow notifications if requested. Watching starts automatically after permission is granted. The app preloads Discord and waits for the configured window. You can switch apps or turn off the screen; the notification lets you **Stop watching** or return.

The English interface uses bundled [Manrope](https://github.com/google/fonts/tree/main/ofl/manrope) typography, a light theme and a compact three-card setup. The **More options** menu contains **Activity log**, **Watch window**, **Reset today's attempt**, and **About & help**. Edits stay on the device, including when the app is backgrounded.

Monitoring uses a user-started foreground service with a notification and a bounded partial wake lock. The WebView stays active when the activity is hidden. Rotation preserves the activity. Back sends the running app to the background. Removing the task from Recents, force-stopping, rebooting, activity destruction or an OS process kill ends the session; there is no automatic restart or retry. Each completed session ends; arm it again for the next day. Settings and the Discord session stay on the device.

For extended screen-off monitoring, set the app's Android battery mode to **Unrestricted / No restrictions** (especially on Xiaomi/HyperOS). Doze, manufacturer battery management and lost network connectivity can still interrupt monitoring. A wake lock increases battery usage while armed, including the wait before the window. Physical-device tests below cover short screen-off sessions, not overnight reliability.

Defaults for a new install: `20:00`, the device's timezone, 5 minutes before and 15 minutes after. Existing settings are preserved. Choose the timezone in which the channel's opening time is expressed; **Use device time zone** selects the phone's current zone. Daylight saving and windows crossing midnight are handled by `java.time`.

**Opening time defines a window, not a scheduled keypress.** An already writable channel can receive the message at the beginning of the window. A late opening can trigger an attempt until the end of the window. Threads and direct messages are unsupported. Existing drafts stop an attempt rather than being replaced.

## Mobile performance

License checks occur before monitoring begins and every 30 seconds while it runs. No license request is made when the Discord editor opens, so activation does not add a network round trip to the send path. Opening-window scheduling now wakes at the precise start boundary rather than waiting for the next one-second status tick. Actual send latency still depends on the phone, Android WebView, the user's connection, and Discord; instantaneous delivery or server acceptance cannot be guaranteed.

- A single native activity and the installed Android System WebView; no JavaScript UI framework or bundled browser.
- Preloaded channel and persisted local login before the monitoring window.
- A `MutationObserver` detects editor changes directly in the page, then uses an origin-scoped WebView message callback. There is no repeated JavaScript/native readiness polling.
- A 16 ms **target** fallback checks CSS visibility while actively waiting. It stops as soon as an editor is detected or the session ends.
- Version 1.2.1 uses Android `InputConnection.commitText` to feed Discord's editor through native input, then dispatches native Enter after persisting the daily reservation. The page observes the asynchronously updated draft before authorizing Enter. The previous `execCommand` insertion could display text without updating Discord's editor state; synthetic Enter then left it unsent.
- The desktop viewport stays at 980 CSS pixels so Discord's composer does not collapse to zero width on a phone. No `innerHTML` replacement or Discord token/API is used.
- The release APK is reduced with R8 and resource shrinking. Runtime browser resources are supplied by Android System WebView.

The journal reports page detection through Enter dispatch and page observation through a matching new message. See [device timing observations](docs/performance.md) for the local 1.1/1.2 comparison. These measurements do not measure server acceptance, the owner's permission change or your position among participants. There is **no measured speed comparison with the PC application** and being first is not guaranteed. Phone CPU, WebView version, network and Discord rendering affect the result.

## Duplicate protection and uncertainty

One reservation per exact channel and scheduled day is written synchronously to private app storage **before Enter**. A reserved or confirmed entry blocks another attempt after a restart, even if the message changes. No automatic retry follows an uncertain result. If the process stops after reserving but before sending, that day's attempt can be missed.

Confirmation means a new visible message row contains matching normalized text within ten seconds. An optimistic UI message or another user's identical message can satisfy it. This is not a Discord server acknowledgement or proof that a server bot accepted the check-in. Check the channel yourself when the result is uncertain.

In **More options**, **Reset today's attempt** lets you manually remove the reservation for the configured channel and scheduled day. Stop monitoring, check Discord, then explicitly confirm the checkbox. It does not send or start monitoring, and leaves other channels/days and the login untouched. An uncertain send may already exist, so resetting can allow a duplicate. No reservation is cleared automatically.

## Account policy and compatibility

Discord [prohibits automated personal accounts](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots) and may terminate an account for using them. This Android port has the same account-policy limitation as the original desktop project.

The app displays Discord's desktop web interface inside WebView so the channel composer remains available. Discord can change its web UI, reject an embedded browser or require an unsupported login flow. If login cannot complete, stop and use Discord normally; this app does not bypass login challenges. Update Android System WebView / Chrome before troubleshooting. Mobile Discord's own application is not controlled by this project.

Only the HTTPS `discord.com` main frame can deliver watcher events. External main-frame links open in the device's browser. File/content access, cleartext traffic and Android backup are disabled. This follows Android's [WebView message-listener API](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E,androidx.webkit.WebViewCompat.WebMessageListener)). No personal browser profile, message configuration, log or signing key is included in the repository.

## Build

Distribution requires a hosted HTTPS license service and a signed release built with `DMA_LICENSE_URL`. Without a domain or server, use the [Cloudflare Worker and D1 setup](cloudflare_worker/README.md), which provides a `workers.dev` HTTPS address. A self-hosted [Python service](license_server/README.md) is also available. Without one of these backends, license creation, revocation, and activation cannot operate. The admin page lists phones after their first activation, not raw APK downloads. Debug builds without a URL bypass licensing only for local development.

Use Android Studio, or JDK 21 and Android SDK 36. The checked-in wrapper uses Gradle 9.1.0 with Android Gradle Plugin 9.0.1. Set `ANDROID_HOME` or your ignored `local.properties`:

```properties
sdk.dir=C\:/Android/sdk
```

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. On macOS/Linux use `./gradlew`.

To build a reduced, signed release on Windows:

```powershell
$env:DMA_LICENSE_URL = 'https://licenses.example.com'
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
```

The script creates a persistent signing key in `%LOCALAPPDATA%\DiscordMessageAutoMobile\signing`, stores its password protected with Windows DPAPI, runs checks and writes `artifacts/discord-message-auto-1.4.0.apk`. Keep a secure backup of that signing directory to build compatible updates; never commit it. Release signing can also use the `DMA_STORE_FILE` and `DMA_STORE_PASSWORD` environment variables with the `mobile` key alias.

GitHub Actions builds a debug APK and runs Java and browser tests. Debug builds use the separate `dev.norihy.discordmobile.debug` package, so device regression tests cannot overwrite the production app's login, configuration. Published release APKs use the maintained local release key.

## Verification

```powershell
python -m pip install -r requirements-test.txt
python -m playwright install chromium
python -m unittest discover -s tests -v
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Browser tests intercept every request with a fake channel; they do not contact Discord or send real messages. They exercise the same `watcher.js` asset used in Android. Java tests cover configuration, exact window boundaries, midnight, timezone conversion and daylight saving.

Version 1.3 regression tests run on the USB-connected Xiaomi/POCO 25053PC47G, Android 16/API 36. They use the real Android WebView and a local synthetic HTTPS channel, with no Discord requests: send in foreground, send after Home, send while the screen is off, notification stop and deadline cleanup. An authorized live test on 2026-10-10 confirmed one automatic message after a permission change, still present after reloading the channel. The app observed it 106 ms after editor detection; this is one interface observation, not a server-latency benchmark. Doze and overnight behavior remain unverified. Start with **Check without sending** and an authorized test channel before relying on a check-in.

With a device connected, install the separate debug/test APKs, grant its notification permission and run:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant dev.norihy.discordmobile.debug android.permission.POST_NOTIFICATIONS
adb shell am instrument -w dev.norihy.discordmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests reset only the debug package's preferences and briefly turn the screen off/on. Keep the device unlocked when starting them.

## Project files

- `app/src/main/java/…/MainActivity.java`: native UI, WebView, lifecycle, native input and session state.
- `app/src/main/java/…/WatchConfig.java`: validation and daily windows.
- `app/src/main/java/dev/norihy/discordmobile/UiStyle.java`: native UI styling and bundled Manrope typography.
- `app/src/main/assets/watcher.js`: opening detection, input guards and confirmation observer.
- `app/src/test/`: Java scheduling tests.
- `tests/`: isolated browser regression tests.
- `scripts/build-release.ps1`: repeatable local release signing and build.
- `.github/workflows/android.yml`: automated builds and checks.

The Windows/Python project in `discord-mess-auto` remains a separate application and is unchanged by this port.
