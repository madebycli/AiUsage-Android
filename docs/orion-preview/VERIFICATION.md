# Verification · 12 November 2026

## Local checks

- **172 tests in 17 suites**, zero failures, errors or skipped tests. This includes **32 Compose UI tests** and native widget-renderer tests. [Suite counts](tests.json).
- Android Lint: **0 errors, 59 warnings**. Warnings are not claimed to be resolved; this includes the ported SharedPreferences API style recommendation.
- `git diff --check` passes.
- The bottom-navigation implementation block matches approved commit `c0cf342` byte for byte. Its existing order/visibility/start-page regression tests still pass.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
python tools/build_preview_gallery.py
```

## New behavioral coverage

- Every count-up frame contains only a whole-number percentage, including a fractional measured target; the final 42.5 measurement displays as 43%.
- All 30 normal/emphasized Material typography slots use the bundled Google Sans Flex family.
- The fixed bento geometry matches Orion’s tall-left/two-short-right structure.
- A real long-press drag trades settings tiles and writes the new order to preferences.
- Ordinary toolbar titles replace the removed hero banners.
- Every settings destination opens and returns; settings search filters and opens the matching destination.
- Native title layout results confirm all six German tile labels remain on one line without visual overflow.
- Decorative rotation changes the actual rendered bitmap.
- Applied widget presets survive a saved-state restoration before confirmation and survive per-widget storage.
- Vertical account layout fixes the column count at one; grid layout caps it at two and adapts to narrow widths.
- Side-by-side and stacked time windows produce different real widget bitmaps.
- The Rings preset remains Rings at a standard 250×120 dp size; truly small 110×55 dp sizes retain the compact fallback.
- Existing authentication, data parsing, tracker migration, privacy, widgets, account identity, notification and reset tests remain included.

## Preview artifact

The new GitHub prerelease is built with the **normal** `com.madebycli.aiusage` package, version **0.9.4 (15)**. The release workflow uses the existing release certificate and checks it against Stable 0.9.1 before publishing. Preview is a GitHub prerelease designation. No separate `.preview` Android application is created.

The workflow runs the full tests, `lintRelease` and `assembleRelease`. Release signature, normal package/label, non-debuggable status and ZIP integrity are checked before upload. The release contains the APK and SHA256SUMS, and links to this English review. [Workflow](https://github.com/madebycli/AiUsage-Android/actions/workflows/release.yml).

The first local attempt exceeded the environment’s memory limit. The completed run used one Gradle worker and in-process Kotlin compilation; it is the result reported above.

## Interpretation limits

Screenshots and GIF frames come from Robolectric API 35 with native graphics, using the actual app theme, fonts and Compose components. Widget images come from the production bitmap renderer. Test account names and login codes are fixtures. The widget-configuration screenshots intentionally include the no-account state.

No Android phone or emulator is attached. These checks do not establish Android 17 device animation performance, actual launcher placement/resizing, live browser authentication or background delivery. Widget size belongs to the launcher; the app's size tabs simulate it and are labelled accordingly.
