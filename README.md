# Dynamic Glyph for Android

> ### ⚠️ 未完工 · Work in progress — builds and unit-tested, never run on a phone
>
> **中文：** 已经**编译通过、71 个单元测试全部通过**，也生成了可安装的测试 APK。
> 但**从未在任何真机上运行过**——识别效果、权限弹窗、坐标是否对得上，都还是未知数。
> 需要一位懂 Android 的人（或愿意照着说明试一下的人）在真机上验证。
> **请先读 [HANDOFF.md](HANDOFF.md)** 和 [测试说明-Redmi-K80-Pro.md](测试说明-Redmi-K80-Pro.md)。
>
> **English:** It now **compiles and all 71 JVM unit tests pass**, and a signed
> debug APK is available. It has **never been run on a real device**, so
> recognition accuracy, permissions and screen-coordinate calibration are still
> unverified. Start with [HANDOFF.md](HANDOFF.md) (Chinese) and
> [PORTING.md](PORTING.md) (technical notes).

An Android port of [Dynamic-Glyph](https://github.com/ChestnutLUO/Dynamic-Glyph),
an iOS research prototype that watches the screen while you play Ingress,
recognises the glyph hack sequence, and shows what it saw in a floating panel.

## Read this first

Two things about the original apply here unchanged, and one is new.

The game's operator lists unofficial software as a form of cheating. This app
does not modify or talk to the game, but it is unofficial software, and the
guidelines draw no line between software that changes the game and software that
helps you play it. That risk falls on your account.

The reference tables are third-party data that is **not** included here; see
[Reference tables](#reference-tables).

And: **it compiles, but it has never been run on a phone.** The first real
device test is still outstanding. See [Verification](#verification).

## What it does

While a glyph hack is on screen the app:

1. captures the screen through MediaProjection,
2. finds the eleven-node array by fitting a lattice to the bright node rings,
3. measures ink along each of the 38 elementary edges,
4. matches the resulting edge set against the shape table, direction-independently,
5. reads the slot indicators and the countdown bar,
6. tracks which slot is open and commits a Glyph only on an independent boundary,
7. predicts the rest of the sequence from the table, and
8. draws it in a draggable overlay.

## Requirements

- Android 12 (API 31) or later — MediaProjection as a foreground service
- Screen capture permission, overlay permission
- The two reference CSV files

## Build

The Gradle wrapper is included, so no separate Gradle install is needed:

```sh
./gradlew test            # 71 JVM tests
./gradlew assembleDebug   # produces app/build/outputs/apk/debug/app-debug.apk
```

The wrapper downloads Gradle from `services.gradle.org`. If that host is
unreachable (common on some networks), replace the `distributionUrl` in
`gradle/wrapper/gradle-wrapper.properties` with a mirror, for example
`https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip`.

Install on a device. An emulator will not work: MediaProjection needs a real
display.

A ready-built, v2-signed arm64-only debug APK is in `dist/` for device testing
(this folder is gitignored, so rebuild it if you clone the repository fresh).

## Reference tables

`glyph_line.csv` (shapes) and `glyph.csv` (sequences) come from
[chibatching/glyph-predictor-data](https://github.com/chibatching/glyph-predictor-data),
which publishes no licence, so they are not redistributed with this project.

```sh
python tools/fetch_reference_data.py
```

This fetches a pinned revision, verifies both SHA-256 hashes, and writes them to
`local-data/` (gitignored). Read the upstream licence position yourself before
using them.

Then in the app, load each file once. It copies them into its own storage, so
this is a one-time step.

## Usage

1. Launch the app.
2. Load `glyph_line.csv` and `glyph.csv`.
3. Tap **Start Capture**.
4. Grant the overlay permission when asked, then screen capture.
5. Switch to Ingress. The panel appears near the top of the screen and can be
   dragged.

Without a shape table the app refuses to start rather than capture frames it
cannot read; without a sequence table it reports that prediction is unavailable.

## Layout

```
app/src/main/java/com/dynamicglyph/
  recognition/          pure Kotlin, no Android imports
    NodeArray, NodeArrayDetector, GlyphGeometry, GlyphPath, GlyphTable
    GlyphFrameRecognizer, GlyphFrameSession, SlotStripReader
    PresentationTracker, SequenceTable, SequencePredictor
    AnalysisGate, GameUILayout, GamePhaseEvidence, GlyphDisplaySnapshot
  capture/              MediaProjection, ML Kit OCR, pixel conversion
  display/              the overlay window
  android/              activity, foreground service
app/src/test/java/com/dynamicglyph/recognition/
                        JVM tests for the parts that need no device
```

The `recognition` package has no Android dependency, which is what makes it
testable on the JVM and is the same split the original uses.

## Verification

**Done:** `assembleDebug` succeeds on JDK 17 / Gradle 8.10.2 / Android SDK 35.
`testDebugUnitTest` runs 71 tests with 0 failures. The debug APK is signed
(v2), declares the three required permissions, and bundles the ML Kit OCR
model, so it does not need Google Play services.

**Still outstanding:** no run on any device. The three pixel-based readers
(`NodeArrayDetector`, `GlyphFrameRecognizer`, `SlotStripReader`) have no
screenshot corpus here, so their first real validation is a device run against
the game.

```sh
./gradlew test            # lattice, tables, phrases, tracker, gate
./gradlew assembleDebug
```

What the tests cover and what they cannot is spelled out in
[PORTING.md](PORTING.md#verification-status).

## Known differences from the iOS build

- **Layout fractions.** Measured on a 603×1311 iPhone frame with a Dynamic
  Island. Android status bars differ, so the header band may be off. If the app
  never leaves "Waiting for a glyph hack" while a hack is clearly on screen,
  recalibrate `GameUILayout`.
- **OCR.** ML Kit instead of Vision: one Latin model, no fast/accurate split,
  English header phrases only.
- **Display.** One overlay instead of Picture in Picture plus a Live Activity.
- **Not ported.** Gear drop reading, result panel reading, portal level, command
  notices, drone footer, the player-input validator, statistics, iCloud sync,
  recordings and the diagnostics screens.

## Licence

PolyForm Noncommercial License 1.0.0, following the original. The name
"Dynamic Glyph" and its icons are not licensed; this port uses its own name and a
different icon. Game imagery and trademarks belong to their owners.
