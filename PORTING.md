# Porting notes

What this port reproduces, what it changes on purpose, and what it does not
attempt. Read this before trusting a recognition result.

## Scope

The original is 319 Swift files across two layers: a pure-Swift recognition core
(`Packages/GlyphRecognition`) and an app layer that adds capture, OCR, display,
statistics and diagnostics. This port reproduces **the glyph hack path end to
end** — capture, recognise, track, predict, display — and leaves the surrounding
product features out.

### File mapping

| Swift | Kotlin | Fidelity |
| --- | --- | --- |
| `Vec2` (SIMD2) | `Vec2.kt` | Equivalent |
| `NodeIndex.swift` | `NodeIndex.kt` | Equivalent |
| `NodeArray.swift` | `NodeArray.kt` | 1:1, positions verbatim |
| `NodeArrayDetector.swift` | `NodeArrayDetector.kt` | 1:1 |
| `GlyphPath.swift` | `GlyphPath.kt` | 1:1, with a corrected `Edge` (below) |
| `GlyphGeometry.swift` | `GlyphGeometry.kt` | 1:1, same signatures |
| `GlyphTable.swift` | `GlyphTable.kt` | 1:1 |
| `GlyphID.swift` | `GlyphPath.kt` | 1:1 |
| `SequenceTable.swift` | `SequenceTable.kt` | 1:1 |
| `SequencePredictor.swift` | `SequencePredictor.kt` | 1:1 for `predict`; `discrimination` / `earlyResolution` not ported |
| `LuminanceImage.swift` | `LuminanceImage.kt` | 1:1 |
| `ColorImage.swift` | `ColorImage.kt` | 1:1, plus a packed accessor |
| `GameUILayout.swift` | `GameUILayout.kt` | Constants verbatim; see the recalibration warning |
| `SlotStripReader.swift` | `SlotStripReader.kt` | 1:1 |
| `GlyphFrameRecognizer.swift` | `GlyphFrameRecognizer.kt` | 1:1 |
| `GlyphFrameSession.swift` | `GlyphFrameSession.kt` | 1:1 |
| `AnalysisGate.swift` | `AnalysisGate.kt` | 1:1 in rules; luma range differs |
| `GamePhaseEvidence.swift` | `GamePhaseEvidence.kt` | `HeaderPhraseMatcher` 1:1; `GameText` reduced to English |
| `SlotObservation.swift` | `SlotObservation.kt` | 1:1 |
| `PresentationTracker.swift` | `PresentationTracker.kt` | Mostly 1:1; two reductions, listed below |
| `GlyphDisplaySnapshot.swift` | `GlyphDisplaySnapshot.kt` | Status set and priority 1:1; localised status lines replaced with English strings |
| `GlyphDiagramLayout.swift` | `GlyphDiagramLayout.kt` | 1:1, outline ordering fixed |
| `GlyphDisplayRenderer.swift` | `GlyphOverlayWindow.kt` | Redrawn on `Canvas`; same palette |
| `ImageDecoder.swift` (CGImage) | `ImageDecoder.kt` | Android equivalent |
| `LiveGlyphOutput.swift` | `CaptureEngine.kt` | Android equivalent |
| `ScreenCaptureBackend` / `ReplayKitBackend` | `CaptureEngine.kt` | Replaced by MediaProjection |
| `HeaderTextRecognizer.swift` (Vision) | `HeaderTextRecognizer.kt` (ML Kit) | Different engine, same matcher |
| `GlyphPiPController` + `GlyphActivityController` | `GlyphOverlayWindow.kt` | One overlay instead of two surfaces |
| `FrameRing` / `BroadcastChannel` / `SampleHandler` | — | Not needed: single process, no extension |
| Gear, results, portal level, command notices, drone footer, input validator, statistics, iCloud, recordings, diagnostics | — | Not ported |

## Deliberate deviations

### 1. `Edge` equality is on the sorted pair

The Swift `struct Edge` stores `lower`/`upper` already sorted, so its synthesised
`Hashable` is direction-independent. A Kotlin `data class Edge(a, b)` on the raw
arguments would **not** be: `Edge(0,5) != Edge(5,0)`, so a stroke drawn the other
way would produce a different edge set and fail to match. `Edge` is therefore a
plain class with `equals`/`hashCode` over the sorted pair, and `LatticeTest`
asserts it. This is the single most important detail in the port.

### 2. Luminance is not stretched from video range

The original expands Rec.709 video range (16..235) to full range, because
CoreVideo hands it video-range buffers. Android's `ImageReader` in `RGBA_8888` is
full range, so the same stretch would move every pixel up and shift the gate's
own thresholds. `LumaGate.lumaOfArgb` applies BT.601 only.

### 3. The layout fractions need recalibration on Android

`GameUILayout` is measured from 603×1311 iPhone frames with a Dynamic Island.
The original says plainly that the numbers are not claimed to hold for other
devices. On Android the status bar height differs, so `headerTextTop` in
particular may land on the wrong rows, and the header will read as unknown — the
tracker then relies on the indicator path instead. If recognition never reaches
`PRESENTING`, measure your own device's fractions from a screenshot and adjust
`GameUILayout.ENGLISH_PORTRAIT`.

### 4. OCR is ML Kit, not Vision

ML Kit has one Latin model and no fast/accurate split, so the original's
two-tier strategy collapses to one recogniser. `HeaderPhraseMatcher` is unchanged,
which means the phrase rules — including `GO!` requiring a whole-header match —
still apply. Only English phrases are defined, matching the language the original
has a recording of.

### 5. One display surface, not two

iOS needs Picture in Picture *and* a Live Activity because an app cannot draw
over another app. A `TYPE_APPLICATION_OVERLAY` window can, so there is one panel.
It redraws at 15 Hz rather than on every snapshot, because the tracker publishes
at up to 20 Hz and most of those frames change nothing.

### 6. No shared-memory ring

The ring, the Darwin notifications and the broadcast extension exist because
ReplayKit delivers frames from a separate process. MediaProjection delivers them
in-process, so frames go straight to the analysis thread.

## Reduced areas

Two parts of `PresentationTracker` are smaller than the original's 825 lines.
Both are marked in the source:

- **Overlay obstruction** starts its clock on a `COVERED_BY_OVERLAY` rejection
  without the original's extra "the array is in view" gate.
- **The fallback path** infers slots from blank intervals but does not reproduce
  the original's `nextInferredIndex` bookkeeping across several consecutive
  inferred slots.

Everything else in the tracker — phase priority, the indicator prelude,
slot-count voting and conflict, skipped-slot marking, release-last-slot,
obstruction grace, nested-hypothesis resolution, interrupted/reset — follows the
Swift source.

## Verification status

**This port has not been compiled.** The environment it was written in has no JDK,
no Android SDK and no Gradle, so no `assembleDebug` was ever run. Treat the first
build as a real step, not a formality.

The Gradle wrapper jar and `gradlew` scripts are binaries and are not included;
generate them with Android Studio or `gradle wrapper --gradle-version 8.10.2`.

What is in place to help:

- `app/src/test/java/com/dynamicglyph/recognition/` — JVM unit tests for the
  lattice and geometry, both table parsers, the direction-independent lookup, the
  header phrase matcher, the tracker's boundary rules, and the luma gate. They
  use synthetic fixtures, so they need no reference data.
- Every non-obvious constant and rule carries the reason it exists, taken from
  the original's own comments.

What is **not** covered by tests:

- `NodeArrayDetector`, `GlyphFrameRecognizer` and `SlotStripReader` on real
  pixels. The original tests these against a private screenshot corpus that is
  not distributed; this port has no equivalent, so their first real validation is
  a device run against the game.
- The overlay rendering and the capture pipeline, which need a device.

## Reference data

`glyph_line.csv` and `glyph.csv` come from
[chibatching/glyph-predictor-data](https://github.com/chibatching/glyph-predictor-data),
which publishes no licence. They are **not** redistributed here. Fetch them with:

```sh
python tools/fetch_reference_data.py
```

That pins revision `109f6c32d24c9f219e946a1423642390db6ce86b` and verifies both
SHA-256 hashes before writing anything into `local-data/`, which is gitignored.
Satisfy yourself about the upstream licence position before using them.

Load each file once in the app; it copies them into its own storage.

## Licence

The original is PolyForm Noncommercial 1.0.0, and this port follows it. The name
"Dynamic Glyph" and its icons are not licensed: this port uses its own name and a
different icon.
