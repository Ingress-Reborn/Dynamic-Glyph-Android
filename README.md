# Dynamic Glyph for Android

> ### ⚠️ 未完工 · Work in progress — not built, not tested
>
> **中文：** 这个安卓版代码已经写完，但**一次都没有编译过，也没有在任何手机上运行过**。
> 需要懂 Android 开发的人接手：先修编译错误，再在真机上试一次。
> **请先读 [HANDOFF.md](HANDOFF.md)**（中文，大白话说明现状和下一步）。
>
> **English:** The code is written but has **never been compiled or run on a device**.
> Someone with Android experience needs to take it from here: fix the build, then
> try it on a real phone. Start with [HANDOFF.md](HANDOFF.md) (Chinese) and
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

And new: **this port has not been compiled.** It was written in an environment
with no JDK, Android SDK or Gradle. Expect to fix build errors on the first
`assembleDebug`. See [Verification](#verification).

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

The Gradle wrapper's jar and the `gradlew` scripts are binaries and are not
included. Generate them once, either by opening the project in Android Studio
(which does it for you) or with a local Gradle:

```sh
gradle wrapper --gradle-version 8.10.2
```

Then:

```sh
./gradlew assembleDebug
```

Install on a device. An emulator will not work: MediaProjection needs a real
display.

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

**Not compiled.** The unit tests have not been run either. Run both:

```sh
./gradlew test          # lattice, tables, phrases, tracker, gate
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
