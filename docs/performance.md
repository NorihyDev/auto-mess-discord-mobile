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
