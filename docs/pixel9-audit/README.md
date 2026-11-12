# Pixel 9: native Android visual audit

The comparison runs the unchanged **0.9.4** source (`6b04ff2`) and corrected **0.9.5** on the same hardware-accelerated **Google Pixel 9 AVD**, **1080 × 2424 px, 420 dpi**, Android 15 / API 35, full Google APIs system image. The complete device metadata is in [device.json](device.json).

These are full-resolution `UiAutomation.takeScreenshot` captures of the production Compose screens running in Android. PNGs are copied unchanged. Only the two overview sheets scale the photos and add English captions outside them. Account names, usage measurements and reset forecasts are fixtures; these photos do not claim live authentication or current predictions. UI labels remain German to exercise long wording.

[Detailed review and differences](SELF_REVIEW.md) · [Dark overview](images/dark-overview.jpg) · [Light overview](images/light-overview.jpg) · [Native capture workflow](../../.github/workflows/pixel9-audit.yml)

Dark mode was reviewed first. Click a photo for its original 1080 × 2424 resolution.

Native capture: [successful workflow run](https://github.com/madebycli/AiUsage-Android/actions/runs/43295320464) · [audited source](https://github.com/madebycli/AiUsage-Android/tree/eaa919c3a64129dd50c5e9b1af30ee304f189200) · [PNG hashes and provenance](provenance.json). All four native instrumentation invocations passed (two app tests per build; one original Orion test per theme).

## Corrected build: dark and light side by side

### Settings hub · both themes

| Dark | Light |
|---|---|
| ![Settings hub in dark mode](images/after/dark/settings.png) | ![Settings hub in light mode](images/after/light/settings.png) |

### Usage overview · both themes

| Dark | Light |
|---|---|
| ![Usage overview in dark mode](images/after/dark/usage.png) | ![Usage overview in light mode](images/after/light/usage.png) |

### Codex forecast · both themes

| Dark | Light |
|---|---|
| ![Codex forecast in dark mode](images/after/dark/forecast-codex.png) | ![Codex forecast in light mode](images/after/light/forecast-codex.png) |

### Claude forecast · both themes

| Dark | Light |
|---|---|
| ![Claude forecast in dark mode](images/after/dark/forecast-claude.png) | ![Claude forecast in light mode](images/after/light/forecast-claude.png) |

## Before and after: dark mode

### Settings hub

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Settings hub](images/before/dark/settings.png) | ![Corrected: Settings hub](images/after/dark/settings.png) |

### Appearance

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Appearance](images/before/dark/appearance.png) | ![Corrected: Appearance](images/after/dark/appearance.png) |

### Notifications and background refresh

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Notifications and background refresh](images/before/dark/notifications.png) | ![Corrected: Notifications and background refresh](images/after/dark/notifications.png) |

### Navigation preferences

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Navigation preferences](images/before/dark/navigation.png) | ![Corrected: Navigation preferences](images/after/dark/navigation.png) |

### Accounts

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Accounts](images/before/dark/accounts.png) | ![Corrected: Accounts](images/after/dark/accounts.png) |

### Installed widgets

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Installed widgets](images/before/dark/widgets.png) | ![Corrected: Installed widgets](images/after/dark/widgets.png) |

### About and source attribution

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: About and source attribution](images/before/dark/about.png) | ![Corrected: About and source attribution](images/after/dark/about.png) |

### Usage overview

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Usage overview](images/before/dark/usage.png) | ![Corrected: Usage overview](images/after/dark/usage.png) |

### Codex forecast

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Codex forecast](images/before/dark/forecast-codex.png) | ![Corrected: Codex forecast](images/after/dark/forecast-codex.png) |

### Codex forecast · probability details

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Codex forecast · probability details](images/before/dark/forecast-codex-probabilities.png) | ![Corrected: Codex forecast · probability details](images/after/dark/forecast-codex-probabilities.png) |

### Claude forecast

| 0.9.4 · before | 0.9.5 · corrected |
|---|---|
| ![Before: Claude forecast](images/before/dark/forecast-claude.png) | ![Corrected: Claude forecast](images/after/dark/forecast-claude.png) |

## The original Orion app on the same device

The official **Orion Store 2.7.5** APK is unchanged and its published SHA256 is checked before installation. Only local theme and onboarding preferences are configured on this disposable emulator. Its store features and bottom navigation are not imported into AI Usage.

| Original Orion · dark · settings | AI Usage · dark · settings |
|---|---|
| ![Original Orion](images/orion/dark/settings.png) | ![AI Usage](images/after/dark/settings.png) |

| Original Orion · dark · appearance | AI Usage · dark · appearance |
|---|---|
| ![Original Orion](images/orion/dark/appearance.png) | ![AI Usage](images/after/dark/appearance.png) |

| Original Orion · dark · navigation | AI Usage · dark · navigation |
|---|---|
| ![Original Orion](images/orion/dark/navigation.png) | ![AI Usage](images/after/dark/navigation.png) |

## Every page in both themes

All 108 before/after app photos are indexed below, including the lower portions of long screens. The original Orion light references are [Settings](images/orion/light/settings.png), [Appearance](images/orion/light/appearance.png) and [Interface](images/orion/light/navigation.png).

| Page | Before dark | Corrected dark | Before light | Corrected light |
|---|---|---|---|---|
| Usage overview | [Photo](images/before/dark/usage.png) | [Photo](images/after/dark/usage.png) | [Photo](images/before/light/usage.png) | [Photo](images/after/light/usage.png) |
| Account details | [Photo](images/before/dark/account.png) | [Photo](images/after/dark/account.png) | [Photo](images/before/light/account.png) | [Photo](images/after/light/account.png) |
| Settings hub | [Photo](images/before/dark/settings.png) | [Photo](images/after/dark/settings.png) | [Photo](images/before/light/settings.png) | [Photo](images/after/light/settings.png) |
| Settings hub · lower categories | [Photo](images/before/dark/settings-bottom.png) | [Photo](images/after/dark/settings-bottom.png) | [Photo](images/before/light/settings-bottom.png) | [Photo](images/after/light/settings-bottom.png) |
| Accounts | [Photo](images/before/dark/accounts.png) | [Photo](images/after/dark/accounts.png) | [Photo](images/before/light/accounts.png) | [Photo](images/after/light/accounts.png) |
| Accounts · provider actions | [Photo](images/before/dark/accounts-bottom.png) | [Photo](images/after/dark/accounts-bottom.png) | [Photo](images/before/light/accounts-bottom.png) | [Photo](images/after/light/accounts-bottom.png) |
| Appearance | [Photo](images/before/dark/appearance.png) | [Photo](images/after/dark/appearance.png) | [Photo](images/before/light/appearance.png) | [Photo](images/after/light/appearance.png) |
| Notifications and background refresh | [Photo](images/before/dark/notifications.png) | [Photo](images/after/dark/notifications.png) | [Photo](images/before/light/notifications.png) | [Photo](images/after/light/notifications.png) |
| Notifications · lower rules | [Photo](images/before/dark/notifications-bottom.png) | [Photo](images/after/dark/notifications-bottom.png) | [Photo](images/before/light/notifications-bottom.png) | [Photo](images/after/light/notifications-bottom.png) |
| Navigation preferences | [Photo](images/before/dark/navigation.png) | [Photo](images/after/dark/navigation.png) | [Photo](images/before/light/navigation.png) | [Photo](images/after/light/navigation.png) |
| Navigation · default statistics | [Photo](images/before/dark/navigation-bottom.png) | [Photo](images/after/dark/navigation-bottom.png) | [Photo](images/before/light/navigation-bottom.png) | [Photo](images/after/light/navigation-bottom.png) |
| Installed widgets | [Photo](images/before/dark/widgets.png) | [Photo](images/after/dark/widgets.png) | [Photo](images/before/light/widgets.png) | [Photo](images/after/light/widgets.png) |
| About and source attribution | [Photo](images/before/dark/about.png) | [Photo](images/after/dark/about.png) | [Photo](images/before/light/about.png) | [Photo](images/after/light/about.png) |
| Codex forecast | [Photo](images/before/dark/forecast-codex.png) | [Photo](images/after/dark/forecast-codex.png) | [Photo](images/before/light/forecast-codex.png) | [Photo](images/after/light/forecast-codex.png) |
| Codex forecast · probability details | [Photo](images/before/dark/forecast-codex-probabilities.png) | [Photo](images/after/dark/forecast-codex-probabilities.png) | [Photo](images/before/light/forecast-codex-probabilities.png) | [Photo](images/after/light/forecast-codex-probabilities.png) |
| Codex sources | [Photo](images/before/dark/sources-codex.png) | [Photo](images/after/dark/sources-codex.png) | [Photo](images/before/light/sources-codex.png) | [Photo](images/after/light/sources-codex.png) |
| Codex reset history | [Photo](images/before/dark/history-codex.png) | [Photo](images/after/dark/history-codex.png) | [Photo](images/before/light/history-codex.png) | [Photo](images/after/light/history-codex.png) |
| Claude forecast | [Photo](images/before/dark/forecast-claude.png) | [Photo](images/after/dark/forecast-claude.png) | [Photo](images/before/light/forecast-claude.png) | [Photo](images/after/light/forecast-claude.png) |
| Claude sources | [Photo](images/before/dark/sources-claude.png) | [Photo](images/after/dark/sources-claude.png) | [Photo](images/before/light/sources-claude.png) | [Photo](images/after/light/sources-claude.png) |
| Statistics | [Photo](images/before/dark/statistics.png) | [Photo](images/after/dark/statistics.png) | [Photo](images/before/light/statistics.png) | [Photo](images/after/light/statistics.png) |
| Account ordering | [Photo](images/before/dark/sorting.png) | [Photo](images/after/dark/sorting.png) | [Photo](images/before/light/sorting.png) | [Photo](images/after/light/sorting.png) |
| Welcome | [Photo](images/before/dark/welcome.png) | [Photo](images/after/dark/welcome.png) | [Photo](images/before/light/welcome.png) | [Photo](images/after/light/welcome.png) |
| Codex sign-in | [Photo](images/before/dark/login-codex.png) | [Photo](images/after/dark/login-codex.png) | [Photo](images/before/light/login-codex.png) | [Photo](images/after/light/login-codex.png) |
| Claude sign-in | [Photo](images/before/dark/login-claude.png) | [Photo](images/after/dark/login-claude.png) | [Photo](images/before/light/login-claude.png) | [Photo](images/after/light/login-claude.png) |
| Widget configuration | [Photo](images/before/dark/widget-config.png) | [Photo](images/after/dark/widget-config.png) | [Photo](images/before/light/widget-config.png) | [Photo](images/after/light/widget-config.png) |
| Widget layout controls | [Photo](images/before/dark/widget-layout.png) | [Photo](images/after/dark/widget-layout.png) | [Photo](images/before/light/widget-layout.png) | [Photo](images/after/light/widget-layout.png) |
| Widget appearance controls | [Photo](images/before/dark/widget-appearance.png) | [Photo](images/after/dark/widget-appearance.png) | [Photo](images/before/light/widget-appearance.png) | [Photo](images/after/light/widget-appearance.png) |

## Scope and limits

The bottom-navigation implementation remains unchanged. The local software emulator attempt booted Android but could not produce a useful app frame; it is not counted as a successful visual pass. This gallery comes from the separate KVM-backed Android run, not Robolectric. No physical Pixel 9, Android 17 validation, live sign-in or launcher widget-placement validation is implied.
