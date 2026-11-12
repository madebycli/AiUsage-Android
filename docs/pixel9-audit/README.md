# Pixel 9 visual audit · work in progress

This audit compares the unchanged 0.9.4 source (`6b04ff2`) with the corrected 0.9.5 source. The requested device profile is Google Pixel 9, 1080 × 2424 px at 420 dpi, using an Android 15/API 35 AOSP test image. Both light and dark themes are included, with dark mode reviewed first.

Screenshots are accepted only from `UiAutomation.takeScreenshot` on a running Android emulator. Blank startup frames are rejected. Account names, quotas and reset forecasts are labelled fixtures; they are not live account access or current predictions. The native test harness renders production screens and callbacks inside the app activity. No illustration or host rendering is presented as an emulator screenshot.

## Corrections under review

- The root Compose foreground now follows `onBackground` in light, dark and AMOLED themes. Previously a root-level `Text` could inherit the default black content color even against a dark background.
- Settings destinations include the turning category emblem from Orion's page design, integrated into the ordinary toolbar without reintroducing the removed wide banner cards.
- Page gutters use 16 dp, including usage, forecasts, settings, sign-in and widget configuration. Card content uses 16 dp.
- Native settings rows use Orion's `surfaceContainer` and tonal leading icon pattern. Notification settings no longer place a padded native row inside another padded card; permission actions can wrap rather than collide.
- Usage cards use the same elevated content surface role as the other information cards. Bento categories retain Orion's intentional category colors.
- The bottom navigation implementation remains unchanged.

## Coverage

The automated matrix covers 27 views per theme and build: usage, account details, settings hub/top/bottom, all six settings destinations, lower sections of long pages, Codex and Claude forecasts/sources, forecast probabilities, reset history, statistics, sorting, welcome, both sign-in screens and three widget configuration views.

The original Orion Store 2.7.5 release APK is verified against its published SHA256 before installation. Only its local theme and onboarding preferences are configured on the disposable test emulator; its code is unchanged. Its settings, Appearance and Interface pages provide the native reference. Source references remain in [the Orion screen map](../ORION_SCREEN_MAP.md).

## Execution status

A local software emulator booted and accepted the app APKs, but its unaccelerated app startup timed out and yielded a black frame. That frame is retained only as a diagnostic outside this gallery. No complete local emulator pass is claimed.

The hardware-accelerated GitHub Actions job requires KVM and captures unchanged/corrected builds on the same Pixel 9 AVD. The final gallery and observed findings will be added after downloading and inspecting those artifacts. Until then, this document records the method and changes, not completed device validation.
