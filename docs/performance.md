# Local device timing observations

Device: Xiaomi/POCO 25053PC47G, Android 16 (API 36), connected over USB.

The instrumented fixture uses the real WebView and a synthetic channel loaded
with `loadDataWithBaseURL`. No request reaches Discord. It records
`performance.now()` immediately before making the editor visible, then measures
the interval until the fixture receives Enter. It includes detection, insertion,
the persistent daily reservation and the return to the page.

| Version | After Home | Screen off |
| --- | ---: | ---: |
| 1.1, separate native/page callbacks | 38 ms | 38 ms |
| 1.2, immediate editor preparation | 28.1 ms | 23 ms |

These are two single observations per version from the same test fixture and
phone during this development session, not a statistical benchmark. The test
prints `DMA_BENCH: opening_to_enter_ms=...` to logcat for subsequent runs.

A subsequent full five-test run also passed, with observed Enter timings of
14.4 ms in foreground and 21.1/22.1 ms in background. Different runs naturally
vary with device scheduling and storage latency.

Version 1.2 removes several WebView round trips. It prepares text synchronously
when the observer detects an empty writable editor, reports `prepared` to Android,
and submits after Android durably writes the reservation. The final page command
revalidates token, deadline, channel, focus and exact text. Stop and repeated submit
commands cannot send the same prepared message twice.

The mutation observer reacts without an intentional delay. The existing 16 ms
fallback remains for CSS-only visibility changes, as in the desktop project.

No comparison against the running PC app was made. These numbers do not include
Discord's permission propagation, rendering of a real channel, network latency,
server acknowledgement or competition with other users. Live Discord editor
compatibility and overnight/Doze behavior still need separate verification.

## Version 1.2.1 live compatibility fix

On 2026-10-10, an authorized test in a dedicated Discord channel reproduced the 1.2 failure: `execCommand` displayed the draft in a Slate zero-width leaf, but Enter did not publish it. Native text input updated the editor state and native Enter published the message.

The corrected Android app observed exactly one test message 106 ms after editor detection following a permission change. The message remained after a channel reload. This measures one page observation in foreground, not server acceptance latency, permission propagation time or overnight reliability. The corrected native input path also passed the five existing device tests, including Home and screen-off sends. Previous 1.2 synthetic timing figures do not establish live Discord compatibility.

## Version 1.4.1 preparation change

The watcher now installs its draft observer before emitting `ready`. Android can commit native text as soon as it receives that event in the foreground, without a separate `evaluateJavascript` call and callback. The final page validation after the durable daily reservation remains in place, since live Discord compatibility depends on the editor state having settled before native Enter.

The CSS-only fallback requests a 1 ms interval. Chromium may clamp this to at least 4 ms or throttle background timers much more heavily. DOM mutations still run through the observer immediately when JavaScript is scheduled. This change increases CPU/battery use while waiting. The 21 isolated browser tests and Android build/lint pass. A 20 ms bound, 1 ms polling cadence, first-place result or instantaneous Discord delivery cannot be guaranteed.

## Version 1.4.2 USB device regression

The 1.4.1 build was installed on the Xiaomi/POCO 25053PC47G (Android 16/API 36). Foreground send passed, but Home and screen-off sends failed at native text input. In the background, WebView needed a page callback after focus was requested before its input connection could be used. Version 1.4.2 restores that callback only in the background. All five synthetic-channel device tests then passed: foreground, Home, screen off, notification stop and deadline. These tests use a local page and do not establish Discord server delivery time.

On 2026-10-13, the owner enabled sending in the dedicated test channel. The signed 1.4.2 APK passed the no-send channel check, then automatically sent one distinct test message in the foreground. The app observed that message 245 ms after editor detection. After force-stopping and reopening the app, the message was still visible exactly once in the reloaded channel. This is one interface observation; it does not measure when Discord accepted the message or the time between the permission change and editor detection.
