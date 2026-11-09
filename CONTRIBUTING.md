# Contributing

Suggestions and pull requests are welcome. By submitting one, you grant
ChestnutLUO a perpetual, irrevocable, worldwide, royalty-free licence to use,
modify and distribute your contribution under any terms, including in the App
Store app. Please do not submit anything you cannot grant that licence for.

The code itself is available under the
[PolyForm Noncommercial License](LICENSE). You may change it and share your
version for non-commercial purposes, under its own name and icon (see
[NOTICE](NOTICE.md)). Fixes that help everyone are most useful sent here as a
pull request.

## Android development

Use JDK 17 and the checked-in Gradle Wrapper. Configure the Android SDK in
`local.properties`; see [the build guide](Docs/ANDROID.md) for SDK versions and
commands. Open the repository root as a Gradle project in Android Studio.

- `app/`: native Android Views/Canvas UI, screen capture, overlay and CSV import.
- `recognition/`: pure Kotlin geometry, visual recognition, tracking and prediction.
- `Tools/MapControls/`: source generator for the map-control patterns and fixtures.

The current scope and engineering rules are in [Plan.md](Plan.md). Keep changes
focused and test the affected implementation using the existing Gradle tasks.
Do not rerun unrelated device suites or download published APKs for hash checks.
Missing private recordings are not evidence of successful device validation.

Edit generators and regenerate their outputs instead of changing generated code
by hand. Preserve the bundled CSV contents and line endings; their source and
license boundary are documented in [Docs/PROVENANCE.md](Docs/PROVENANCE.md).

## Versions and contributions

Android versionName and versionCode are maintained in `app/build.gradle.kts`.
Build artifacts, signing keys, SDK paths and original device recordings must not
be committed. Explain the behavior changed and the focused verification performed
in a pull request. Keep existing copyright and license notices.
