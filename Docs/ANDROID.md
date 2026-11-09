# Android build and verification

Updated: 2026-11-08

The Android rewrite uses the working name **Glyph Lens** and application ID
`org.szres.glyphlens`. The existing `LICENSE` and `NOTICE.md` remain unchanged.
The working name and new geometric icon do not grant distribution rights for the
reserved Dynamic Glyph name or artwork; distribution naming still needs a separate
check.

## Toolchain

| Component | Pinned or tested version |
| --- | --- |
| JDK | Liberica OpenJDK 17.0.19 |
| Gradle Wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | 2.4.21 in the version catalog; Android uses AGP 9 built-in Kotlin support |
| AndroidX Activity / Core | 1.13.0 / 1.19.1 |
| ML Kit text recognition | 16.0.1, bundled Latin / Chinese / Japanese / Korean models |
| kotlinx serialization | 1.12.0 |
| Room / KSP | 2.8.5 / 2.3.13 |
| AndroidX Test runner | 1.7.0 |
| compileSdk | Android 37.0 (`release(37)`, `minorApiLevel = 0`) |
| targetSdk / minSdk | 37 / 29 (Android 10) |
| SDK Build Tools | 36.0.0 (AGP default) |

AGP 9 no longer applies `org.jetbrains.kotlin.android` to the app module. See the
[AGP 9 built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
and [AGP 9.5 compatibility notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes).
The SDK location belongs in the ignored root `local.properties` or in the usual
Android SDK environment setting; repository build files do not contain a machine
path.

## Build and test

From the repository root on Windows:

```bat
.\gradlew.bat :recognition:test --rerun-tasks :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease :app:bundleRelease :app:lintRelease
```

The Debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
With a device connected, install it using:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On 2026-11-08 the combined command above completed successfully. The recognition
suite executed 451 tests with no failures and four explicit corpus skips; the app
JVM suite executed 177 tests with no failures or skips. The skips cover recorded
array/Glyph annotations, banner/window/slot/countdown annotations, opaque RGBA
golden pixels, and the private replay manifest/frames/OCR trace. These are not
silent passes: setting `GLYPH_REQUIRE_REFERENCE_DATA=1` makes missing private
reference data fail full validation, and full replay without `--ocr-trace` exits
non-zero unless `--no-ocr` or an ablation was explicitly selected. AndroidTest
sources were assembled into `app-debug-androidTest.apk`, but no instrumented case
ran because `adb devices -l` showed no attached device. JVM success, AndroidTest
compilation and Lint are therefore recorded as different evidence levels, not as
device acceptance.

## Implemented P1 scope

- Native home page with Start, Cancel, Stop, status text and window Hide/Show.
- Overlay permission, notification permission and a fresh MediaProjection consent
  request for every new session. Notification denial does not skip the foreground
  service notification.
- One `mediaProjection` foreground service and one serial capture thread.
- `ImageReader` using `acquireLatestImage`, explicit RGBA conversion and timely
  `Image.close` through `use`.
- One `VirtualDisplay` per projection. A display-size change uses `resize` and a
  replacement `Surface`, rather than creating a second projection display.
- A small `WindowManager` overlay with Canvas drawings driven by the production
  `GlyphDisplaySnapshot`; the original five-path demo remains available only for
  isolated rendering checks.
- Dragging, hiding and restoring the overlay. After the first device report of
  drag jank, all overlay View and `WindowManager` operations were moved to the
  main thread; frame conversion remains serial on the capture thread. Full-frame
  conversion is throttled to the source pipeline's approximately 20 Hz before the
  expensive work, so overlay motion cannot monopolize that thread. Hiding does not
  stop capture; Stop releases the reader, virtual display, projection, overlay and
  foreground state. The floating-window preference also applies during a running
  session, and Open Ingress is available while capturing.
- `START_NOT_STICKY`: process death does not automatically resume capture.

Android 14+ consent and configuration-change behavior follows the
[platform requirements](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection).

## P2–P5 implementation status

- Both CSV types can be imported from the home page, stored atomically and read at
  startup. Status reporting includes duplicate sequences and missing Glyph names.
  Invalid imports do not replace a previous valid file; no table is required for
  gear-only capture.
- Geometry, prediction, five-language text parsing, pixel recognition, slot
  evidence and OCR scheduling are implemented in the pure Kotlin module. The
  recognition suite has 451 tests with four explicit real-corpus skips and no
  failures.
- `OverlayController.render(snapshot, table, uptime)` is the production main-thread
  rendering entry used by the live tracker.
- Bundled Latin, Chinese, Japanese and Korean ML Kit adapters, crop conversion,
  coordinate mapping and a single-lane completion bridge are implemented.
  `RgbaFrame → ImageFrame → pixel/slot evidence → Header OCR → PresentationTracker →
  GlyphDisplaySnapshot → main-thread overlay` is connected, including result,
  command, Portal, gear and input-validation business. Real English and every other
  game-language OCR flow still needs device/corpus acceptance, so P2/P3 end-to-end
  recognition is not marked complete.

## Final-gap audit closure (2026-11-08)

The final source review found the following production behavior connected; it did
not find another deterministic code defect requiring a follow-up patch:

- Home renders the published production snapshot through the same overlay preview
  renderer, exposes the actual standalone gear count and selects the latest hack or
  independent drop from real history. Settings and Setup use that production
  renderer too. Open Ingress restores an eligible hidden window and converts launch
  lookup/RuntimeException failures into a visible unavailable state.
- Capture reads the command band directly from the raw `Image.Plane`, honoring
  pixel/row stride and source time before full-screen conversion and the 49 ms
  throttle. Deadline refreshes reuse the immutable snapshot for stale and matched-
  check fade changes; they do not invent new source evidence. Open hacks remain in
  memory and only `isClosed` results enter Room/outbox. Result, gear and standalone
  drop outputs share one `CaptureOriginFactory` and game-language memory.
- The live OCR scheduler remains single-lane. Replay passes no result context when
  `results=off`, including when commands or gear keep the shared lane active, and
  its regression test observes zero result OCR requests. Full replay requires a
  real `--ocr-trace`; only explicit `--no-ocr` or ablation mode permits unavailable
  OCR, with missing input returning a non-zero exit.
- Results details contain native bonus/activity/item/length/Portal/command/Glyph/
  draw-time charts projected from `ResultsPage`, in addition to the lossless text.
  Drive sync is application-owned; local saves/deletes and applied remote merges
  refresh saved results and request reconciliation after Activity facades detach.
- Only the report whose name equals the current `activeFolder` is protected from
  share/delete; older reports remain usable while another capture is running.
  Debug and album recording retain independent early-stop paths, and album audio
  remains playback-capture-only with no microphone fallback. QS/Start still enter
  fresh system authorization, while Stop reports observed stopped/unconfirmed
  states rather than optimistic success.

These are implementation and automated-evidence statements, not device acceptance.
The chart pixels, application-owner instrumentation, image writers/receipts, real
recording codecs, overlay interaction and system entry cases have only been
compiled in this final run. See the open device/corpus/account checks below.

## Live diagnostics and benchmark isolation

- `CaptureService` publishes the production `GlyphDisplaySnapshot`, lightweight
  complete/idle/continuity counts, overlay render/coalescing counts, capture events
  and the active evidence folder to `DiagnosticsLiveStore`. Recognition-history
  strings are created only for a session that opted into evidence. With evidence
  Off there is no evidence directory, per-frame hash/JSONL/JPEG work or recognition
  history; scalar `PowerMeter` counters and the bounded memory-event ring still run.
- Stop calls `PowerMeter.shared.end()`, publishes the final readout and freezes the
  live store. Elapsed time, CPU, thermal/low-power values, PSS and timing samples no
  longer advance, and retired publishers cannot replace the final value. The active
  folder is cleared when finalization completes so the stopped report can be shared
  or deleted.
- Recognition, individual ML Kit OCR requests, recording offers, overlay updates
  and source-arrival-to-overlay-render completion are timed independently. p50/p95
  use nearest rank over at most the latest 1,024 samples per metric; null remains
  unavailable. Display latency here ends when the main-thread render call returns,
  not at a later vsync or photographed pixel. These instrumentation paths have JVM
  tests, but no device timing values have been collected.
- `GlyphCaptureEngine.diagnostics` is the public read-only OCR-lane interface used
  by the recording benchmark. The benchmark no longer reflects on the private
  `header`; production OCR work records directly into the injected isolated meter.
  It does not reset or contaminate `PowerMeter.shared`, write results/evidence, or
  drive the formal overlay. A benchmark display-latency value remains unavailable
  rather than being invented.
- The production evidence path now connects ordinary frames/events/recognition,
  tracking, `GlyphSnapshotWriter`, structured results and input-validation,
  result-frames, command pictures, gear pictures and recording receipts. Enqueue
  state is not presented as a saved file: the specialized writers emit actual
  saved/failed receipts after encoding and are drained before the report is
  released. Ordinary JPEGs increment `imagesWritten` only after a successful save;
  `frame-images.jsonl` records saved/failed/busy/budgetExhausted/writerClosed with
  the original frame/source time. `frames.csv` reads display rotation, process CPU
  and actual overlay visibility, using `unavailable` only when the source cannot be
  read. JVM tests exercise the production streams and scalar fields. Android
  Bitmap/image-receipt/budget cases are compiled but have not run without a device,
  so long-session drain and on-device P5-02/P5-03 acceptance remain open.

## Optional Google Drive sync configuration

Cloud sync is off by default and currently requires a locally supplied Web OAuth
client ID. Put this value in the ignored root `local.properties` (never commit it):

```properties
GOOGLE_OAUTH_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
```

In the same Google Cloud project, configure an Android client with package
`org.szres.glyphlens` and the signing SHA-1, and enable the Drive API. The app uses
Identity sign-in plus incremental `drive.appdata` consent and short-lived tokens;
tokens are not written to disk. An empty client ID leaves the feature visibly
unconfigured rather than simulating authorization. Real account and two-device
verification is still pending.

Instrumented tests can be compiled without a device and run when ADB is available:

```bat
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Compilation does not prove the instrumented cases ran. ADB initially reported no
attached device, so a headless `GlyphLensTest` AVD was created from the installed
Android 36 x86_64 image. On 2026-11-08 the complete suite ran on that AVD:
**81 tests, 0 failures, 0 errors, 0 skipped**, on Android 16 / API 36. The first
run exposed a missing `AndroidJUnitRunner`, fixed by declaring
`androidx.test:runner:1.7.0` explicitly. The next run exposed eight failures across
recording/decoding, result JPEG expectations, sync fixtures, charts and setup;
these were fixed or corrected from evidence and the full suite was rerun to green.
The final result is in `app/build/outputs/androidTest-results/connected/debug/TEST-GlyphLensTest(AVD) - 16.xml`.
The first AVD was shut down after that run. A later real-recording analysis used the
same AVD image to replay the user's 23-second clip through the production engine;
see [REAL_RECORDING.md](REAL_RECORDING.md). Those production results confirm the
three-sequence `nourish / xm / portal` and `INPUT`, not a blanket recognition
failure. A subsequent full suite on the user-provided `127.0.0.1:7555` device
(Android 15 / API 35, NCO-AL00) also passed **84 tests, 0 failures, 0 errors, 0
skipped**, including the 32.88-second five-Glyph recording and RGB_565 decoding.
These are real Android runtime results, not
physical-device results: Room, Bitmap, MediaCodec, MediaStore, FileProvider,
RemoteViews, results charts, system shortcuts and application lifecycle paths were
covered, but no physical screen capture, game, camera, audio policy or Google
account was exercised.

## Real MediaProjection smoke on 127.0.0.1:7555 (2026-11-08)

On the user-provided Android 15 / API 35 NCO-AL00 virtual device, the installed
Debug app was driven through the real setup and system consent UI. Overlay access
and notification permission were granted, then the entire screen was shared.
`dumpsys activity services org.szres.glyphlens` showed `CaptureService` in the
foreground state.

- The overlay was dragged by its title area from approximately y=232 to y=723;
  the before/after screenshots are `app/build/outputs/device-smoke/overlay-before.png`
  and `overlay-dragged.png`.
- Tapping `×` hid the overlay while the home page reported that capture continued;
  Show window restored it.
- Tapping Stop removed the overlay and `dumpsys` reported no active service.
  The stopped screenshot is `app/build/outputs/device-smoke/stopped.png`.

This is a real lifecycle result, not a measured smoothness score. No claim is made
about other devices, the Ingress package, audio policy, long sessions or power.
The device was left connected and the session was stopped cleanly.

## Sustained capture on 127.0.0.1:7555 (2026-11-08)

A real foreground MediaProjection session ran for **986.777 seconds (16m27s)** on
the user-provided NCO-AL00 Android 15 device. Session evidence is in
`app/build/outputs/device-smoke/long-session/spikeB-2026-11-08T08-00-31Z/`.
It recorded **2948 frames, all `complete`**, and `events.log` ends with
`capture stopped after 2948 frames`; `dumpsys` reported no active service after
Stop.

Independent 30-second sampling over 904 seconds is in
`app/build/outputs/device-smoke/long-session-samples.csv`:

| Measurement | Result |
| --- | --- |
| Total PSS | 167,367 KB first, 147,117 KB last, 146,038 KB minimum, 185,417 KB maximum, 166,979.2 KB mean |
| Total RSS | 285,256 KB first, 267,060 KB last |
| Process CPU time | 24 s first, 447 s last; delta / 904 s wall = **0.468 CPU core per wall second** |
| Session `frames.csv` | CPU 0.230 → 461.630 s over 986.777 s; `footprintMB` 14.230 → 15.501, max 61.349 |
| Device state | all 2948 frames `thermal=nominal`, rotation 0, overlay active; no failed or budget-exhausted image receipt |

The PSS samples do not show a monotonic increase over this run. CPU time is not a
battery or power measurement, and the memory samples do not prove the absence of
leaks. The captured screen was the app's own home page, not Ingress: no Glyph
sequence, game latency, real game power trend, two recordings or 15–20 minute
full-game recognition is claimed by this run.

## Real dual recording smoke on 127.0.0.1:7555 (2026-11-08)

Both opt-in recording paths were enabled in the actual Settings UI, followed by a
real whole-screen MediaProjection session. `events.log` records independent
`bug-report recording started` and `album recording started` lines. The session
stopped after 139 capture frames and both entries in `recordings.jsonl` are
`finished` with `stopReason=sessionStopped`.

- 116 video frames appended, zero rate/status/order/pool/not-ready/finalization
  drops; audio was disabled (`audioSource=none`, 0 samples).
- Nine finished H.264/MP4 segments, 1080×1920, 20 fps cap, 4 Mbps configured
  average, 48.442856 seconds and 1,553,331 bytes in the session receipt.
- Album directory `/sdcard/Movies/Dynamic Glyph/` contains nine corresponding
  `DynamicGlyph-recording-20261108-162340660-*.mp4` files. The last album segment
  and session `screen-recording.mp4` both have SHA-256
  `2e75fee82b48c113503dc7e1b49321c21f7e9a249d887b0c5e7ff99ea479e7a7`.
- `dumpsys` reported no active service after Stop. Evidence is in
  `app/build/outputs/device-smoke/recording-session/spikeB-2026-11-08T08-22-51Z/`.

`pm path com.nianticproject.ingress` returned no package on this device. Therefore
this verifies real recording/file/lifecycle behavior on the home screen, not real
Ingress playback audio, microphone exclusion under game audio, or a sustained
full-game recording session.

## Release measurements on this host (2026-11-08)

The local Release build and Release Lint passed. File sizes and hashes below are
measurements of this checkout, not a target or a claim about another build:

| Artifact | Bytes | MiB | SHA-256 |
| --- | ---: | ---: | --- |
| `app-release-unsigned.apk` | 58,501,521 | 55.791 | `7EC1175FD2BED82B88A977C98A78FB814D658EBE03F76D31F029C824C991569C` |
| `app-release.aab` | 25,017,257 | 23.858 | `99F812B329E99F95BEEBD7F1D4337147C45E12C312CE0B7FD68CDADCA5C86CB8` |

The unsigned APK still needs release signing before installation/distribution. It
contains all four native ABIs. The AAB is not an installed-per-device size. Within
these artifacts, 48 ML Kit model files occupy 4,402,977 uncompressed bytes
(3,046,854 compressed bytes in APK; 2,828,269 in AAB). The four
`libmlkit_google_ocr_pipeline.so` files occupy 41,033,660 uncompressed bytes; their
combined compressed size is 41,033,660 in this APK and 16,987,402 in this AAB.
These are content measurements, not a measured delta against a no-OCR build.

| Requested measurement | Result and evidence boundary |
| --- | --- |
| Cold/warm startup | **Not verified.** No Android device is attached; Gradle build time is not app startup. |
| Memory footprint/trend | **Not verified on device.** Real `Debug.getPss()` sampling is wired; no device samples or 15–20 minute trend exist. |
| Recognition and OCR p50/p95 | **Not verified on device/corpus.** Production timers and nearest-rank calculations have JVM tests; controlled test clocks are not performance data. |
| Display latency | **Not verified on device.** The source-arrival → main-thread render-completion metric is implemented; no real samples or vsync/photon measurement exists. |
| Sustained 15–20 minute session and Stop release | **Not run.** Requires real capture and game interaction. |
| CPU/thermal/low-power trend | **Not verified on device.** Real gauges are nullable and connected; no trend was collected and no power value is estimated. |
| Recognition accuracy, two screen ratios, recording overhead | **Not verified.** Private tables/recordings, physical devices and game flows are unavailable. |
| Drive sync | **Protocol-tested only.** Real Google account, two-device, deletion, offline, quota and permission acceptance remain unverified. |

## Device acceptance still required

The first APK was installed manually on 2026-11-07. The user confirmed that the
overlay could be dragged but reported severe jank. The overlay was then moved off
the capture thread and the updated APK passed unit tests, assembly and Lint.
**Drag smoothness on the corrected build still needs a device retest.** The rest
of this checklist also remains open until each result is recorded; automated
checks do not replace it:

1. Start from the visible home page, grant overlay access, and accept screen
   capture. Confirm that the foreground notification and live overlay appear.
2. Switch to Ingress and confirm that capture continues and that the overlay does
   not cover the Glyph slot strip. Drag it by its header.
3. Close the overlay with `×`; confirm that capture continues. Return to Glyph
   Lens and restore it with Show window.
4. Stop from the home page and separately from the notification. Confirm that the
   overlay, recording indicator, projection and foreground notification all go
   away, then start a fresh session.
5. Cancel screen-sharing consent and retry. Stop a running session from the
   system's screen-recording controls and verify that the app returns to Start.
6. Rotate or resize the display during capture and confirm that frames continue.
7. Run a sustained session and inspect memory growth and game touch behavior.
   These measurements are not replaced by compilation or JVM tests.

Real Glyph recognition, OCR, CSV import, prediction, Open Ingress, statistics,
result recognition, private cloud sync, recording and diagnostics remain required
P2–P5 deliveries, not optional scope. Apple Dynamic Island / Live Activity is the
only source capability explicitly deferred by the user; Xiaomi Super Island has a
separate future integration plan in Plan.md. The user-provided `glyph_line.csv`
and `glyph.csv` are bundled as default resources, with their pinned hashes and
cross-references verified by `RealGlyphTablesTest`; a user import takes precedence
over the bundled tables. The real recording is analyzed read-only and is not
bundled in the APK.
