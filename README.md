# Discord Message Auto · Android

Lightweight native Android version of [discord-mess-auto](https://github.com/NorihyDev/discord-mess-auto). Configure a Discord text channel, a message and a daily monitoring window; the app observes the channel editor and makes one send attempt when it becomes writable.

**Android 8.0+ · no Python on your phone · no separate Chromium download · French interface.**

## Install and use

1. Download `discord-message-auto-1.0.0.apk` from the [latest release](https://github.com/NorihyDev/auto-mess-discord-mobile/releases/latest), transfer it to your Android phone and open it. Allow installation from that source if Android requests it.
2. Enter the full `https://discord.com/channels/SERVER_ID/CHANNEL_ID` link and your exact message.
3. Tap **Connexion Discord** and sign in manually in the embedded browser. Complete 2FA or CAPTCHA normally. The application does not ask for a token or extract your credentials.
4. Tap **Vérifier** to test opening detection without inserting text, sending a message or creating a reservation.
5. Review **Réglages**, then tap **Surveiller**. The app preloads the channel and waits for the configured monitoring window. Keep the application visible and the phone connected to the Internet. Use pinch-to-zoom in the channel if needed.

The screen stays awake while armed. Switching apps, locking the phone or recreating the activity (including rotation) stops monitoring; resume it manually. There is no unattended background service or automatic restart. Each completed session ends; arm it again for the next day. Settings and the Discord session stay on the device.

Defaults: `20:00`, `Indian/Antananarivo`, 5 minutes before and 15 minutes after, matching the desktop project. Change the timezone to `Europe/Zurich` if the opening time is expressed in Swiss local time. Daylight saving and windows crossing midnight are handled by `java.time`.

**Opening time defines a window, not a scheduled keypress.** An already writable channel can receive the message at the beginning of the window. A late opening can trigger an attempt until the end of the window. Threads and direct messages are unsupported. Existing drafts stop an attempt rather than being replaced.

## Mobile performance

- A single native activity and the installed Android System WebView; no JavaScript UI framework or bundled browser.
- Preloaded channel and persisted local login before the monitoring window.
- A `MutationObserver` detects editor changes directly in the page, then uses an origin-scoped WebView message callback. There is no repeated JavaScript/native readiness polling.
- A 16 ms **target** fallback checks CSS visibility while actively waiting. It stops as soon as an editor is detected or the session ends.
- Native Android `InputConnection.commitText` inserts text, including emoji and line breaks. The prepared draft and focus are verified before native Enter events.
- The release APK is reduced with R8 and resource shrinking. Runtime browser resources are supplied by Android System WebView.

The journal reports native preparation through Enter and page observation through a matching new message. These measurements do not measure server acceptance, the owner's permission change or your position among participants. There is **no measured speed comparison with the PC application** and being first is not guaranteed. Phone CPU, WebView version, network and Discord rendering affect the result.

## Duplicate protection and uncertainty

One reservation per exact channel and scheduled day is written synchronously to private app storage **before Enter**. A reserved or confirmed entry blocks another attempt after a restart, even if the message changes. No automatic retry follows an uncertain result. If the process stops after reserving but before sending, that day's attempt can be missed.

Confirmation means a new visible message row contains matching normalized text within ten seconds. An optimistic UI message or another user's identical message can satisfy it. This is not a Discord server acknowledgement or proof that a server bot accepted the check-in. Check the channel yourself when the result is uncertain.

The application intentionally provides no button to clear reservations. Clearing Android app storage or uninstalling removes both reservations and the login, and can allow a duplicate. Check Discord before doing so.

## Account policy and compatibility

Discord [prohibits automated personal accounts](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots) and may terminate an account for using them. This Android port has the same account-policy limitation as the original desktop project.

The app displays Discord's desktop web interface inside WebView so the channel composer remains available. Discord can change its web UI, reject an embedded browser or require an unsupported login flow. If login cannot complete, stop and use Discord normally; this app does not bypass login challenges. Update Android System WebView / Chrome before troubleshooting. Mobile Discord's own application is not controlled by this project.

Only the HTTPS `discord.com` main frame can deliver watcher events. External main-frame links open in the device's browser. File/content access, cleartext traffic and Android backup are disabled. This follows Android's [WebView message-listener API](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E,androidx.webkit.WebViewCompat.WebMessageListener)). No personal browser profile, message configuration, log or signing key is included in the repository.

## Build

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
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
```

The script creates a persistent signing key in `%LOCALAPPDATA%\DiscordMessageAutoMobile\signing`, stores its password protected with Windows DPAPI, runs checks and writes `artifacts/discord-message-auto-1.0.0.apk`. Keep a secure backup of that signing directory to build compatible updates; never commit it. Release signing can also use the `DMA_STORE_FILE` and `DMA_STORE_PASSWORD` environment variables with the `mobile` key alias.

GitHub Actions builds a debug APK and runs Java and browser tests. Its disposable debug signing key differs from the release signing key: the Actions debug artifact cannot update the installed release app. Published release APKs use the maintained local release key.

## Verification

```powershell
python -m pip install -r requirements-test.txt
python -m playwright install chromium
python -m unittest discover -s tests -v
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Browser tests intercept every request with a fake channel; they do not contact Discord or send real messages. They exercise the same `watcher.js` asset used in Android. Java tests cover configuration, exact window boundaries, midnight, timezone conversion and daylight saving.

Initial validation includes compiled APKs, Android lint, Java unit tests and real Chromium DOM/keyboard tests. **No physical Android device or Android emulator was available for validation.** Native WebView input, login, on-device layout and a real Discord send still require verification on an Android device. Start with **Vérifier** and an authorized test channel before relying on a check-in.

## Project files

- `app/src/main/java/…/MainActivity.java`: native UI, WebView, lifecycle, native input and persistent reservations.
- `app/src/main/java/…/WatchConfig.java`: validation and daily windows.
- `app/src/main/assets/watcher.js`: opening detection, input guards and confirmation observer.
- `app/src/test/`: Java scheduling tests.
- `tests/`: isolated browser regression tests.
- `scripts/build-release.ps1`: repeatable local release signing and build.
- `.github/workflows/android.yml`: automated builds and checks.

The Windows/Python project in `discord-mess-auto` remains a separate application and is unchanged by this port.
