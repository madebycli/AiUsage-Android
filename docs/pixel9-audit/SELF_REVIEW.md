# Pixel 9 visual self-review · AI Usage 0.9.5

## Result

The native before/after comparison confirms the reported dark-theme regression: the **Settings** heading was black against the dark header. The corrected build inherits the proper theme foreground. The settings destinations now include their rotating category emblem, and their content follows a common 16 dp horizontal grid.

This is a review of actual Android-rendered frames, not the earlier host previews. It does not claim that screenshots prove every interaction or that every AI Usage page is pixel-identical to Orion.

## Device and method

- Google Pixel 9 AVD, 1080 × 2424 physical pixels, 420 dpi, Android 15 / API 35, Google APIs image, hardware rendering and KVM required.
- Unchanged 0.9.4 (`6b04ff2`) versus corrected 0.9.5, version code 16. Both use the same emulator, German UI labels and production screen composables inside the app activity.
- 27 views × two themes × two builds = **108 full-resolution app PNGs**. Long notification, navigation, account and hub pages also have lower-section photos. Dark mode was inspected first.
- Screenshots wait for a hardware frame-commit callback before calling `UiAutomation.takeScreenshot`. Dimensions and nonblank content are checked. PNGs in the gallery are unchanged; contact sheets alone are resized.
- Account names, quota values, source responses and sign-in codes are fixtures. No real credentials or live account sessions appear in this gallery.
- The separate original Orion 2.7.5 APK is unchanged. Its SHA256 is checked before installation; only local theme and onboarding preferences are configured in the disposable emulator.

## Findings and corrections

| Area | Observed before | Corrected behavior and evidence |
|---|---|---|
| Settings heading in dark mode | Black “Einstellungen” text on the dark header, visible in the native before photo. | Root `LocalContentColor` follows `onBackground`; the same native after frame has a light heading. Root foreground contrast is about 15.4:1 in the fixed dark palette, versus about 1.1:1 for the old black fallback. |
| Light and AMOLED foregrounds | Root-level content was not guaranteed to inherit the selected scheme. | The same theme provision covers light, dark, dynamic and AMOLED schemes. Automated checks cover the fixed light/dark/AMOLED foreground/background pairs. Native photos cover light and dark only. |
| Missing submenu identity | Accounts, Appearance, Notifications, Navigation, Widgets and About had only plain toolbar text. | Each destination now carries its category shape and icon, using the attributed Orion spinner. The 48 dp emblem lives in the normal 88 dp toolbar, without adding another wide banner. Long titles stay on one line through native text auto-sizing. |
| Page alignment | Usage and several detail pages used 20 dp gutters while other routes used different padding. | Main page gutters are 16 dp (42 physical pixels at the test density; the native usage-card edge changed from approximately 53 px to 42 px); information-card content also uses 16 dp. The after photos show the common outer grid across settings, usage, sources, history, forecasts and sign-in. |
| Notification density | A padded native list row sat inside another padded card, narrowing text and forcing the background-refresh title onto two lines. | Grouped native rows are no longer double padded. The title fits on one line at the captured Pixel 9 size; explanatory text has more width. Lower account rules use the same grouped surface and genuine gaps. |
| Account actions | The narrower account cards wrapped “Disconnect” onto a separate line in the German layout. | At the captured default font size, reconnect, rename and disconnect fit on the same action row. Flexible wrapping remains available for smaller widths or larger text. |
| Permission actions | A fixed horizontal row could compete for space. | Permission actions use a wrapping flow layout. No overlapping actions were observed in the captured default settings state. OS permission dialogs and every denied/granted combination are outside this photo matrix. |
| Information surfaces | Usage cards were markedly darker than the information panels in detail and forecast routes. | Usage cards now use `surfaceContainerHigh`, matching the information-panel role. Low-emphasis hints and grouped settings rows deliberately use lower surface roles. |
| Settings row structure | Bare icons and inconsistent card nesting did not follow the Orion row family. | Native list items use a 40 dp tonal leading icon, `surfaceContainer`, small internal segment corners, a clipped outer group and 2 dp gaps. Native switch semantics and checked indicators remain intact. |

## Page-by-page inspection

| Captured view | Dark and light observations |
|---|---|
| Usage overview | Three account cards align to the same gutters; the two quota windows fit without collisions. Percentages display whole numbers. |
| Account details | Both quota rings, reset labels and time/usage comparisons remain legible; the lower history preview can scroll. |
| Settings hub and lower categories | The title is legible in dark mode; category color pairs change with theme. The bottom capture exposes Navigation and About. |
| Accounts and provider actions | Three sample accounts retain readable status and actions; provider actions remain scrollable. |
| Appearance | Theme selection, font information and the grouped dynamic/AMOLED controls align. Google Sans Flex remains the application typeface. |
| Notifications and lower rules | Background refresh and per-provider reset rules share row geometry and readable labels. Long lists scroll under the toolbar. |
| Navigation and lower defaults | Ordering controls, start-page choices and statistics defaults fit their groups. Actual bottom navigation source is unchanged. |
| Installed widgets | Empty-state copy is readable. This clean emulator has no placed launcher widgets. |
| About | Version 0.9.5 / build 16 and the AI Usage, Orion, Iris and AniHyou attribution rows are readable. |
| Codex forecast and probabilities | Summary, source labels, percentages and uncertainty bands remain visible in both schemes. The lower photo scrolls to the probability details. |
| Claude forecast | Summary and two source probability panels follow the same grid and typography family. |
| Codex and Claude sources | Source status, age, probabilities and external-link icons are legible. These are successful fixture responses, not live availability claims. |
| Reset history | Dates, event types and excerpt columns remain distinct; long excerpts wrap in the content column. |
| Statistics | Provider, metric and period selectors remain readable; the clean fixture ledger shows the real no-measurements state. |
| Account ordering | Three account rows, handles and up/down actions fit; the original bottom navigation is retained. |
| Welcome | Both provider choices and introductory text are readable in dark and light. |
| Codex and Claude sign-in | Numbered steps and real input controls remain legible. The code/token placeholders are synthetic; browser authorization is not exercised. |
| Widget configuration, layout and appearance | Presets, size selectors, layout options and appearance controls are legible. The preview correctly shows the no-account state; it is not evidence of populated widgets on a real launcher. |

## Motion and interaction evidence

The submenu emblem calls the actual attributed `OrionSpinner`, with an 18-second rotation period, rather than substituting a static image. A direct production-title bitmap test compares two animation times; destination tests check all six settings pages for the category emblem. Existing shared-bounds tile-to-page opening and reverse closing behavior is retained from the Orion port. A still photo shows the current animation frame; it cannot establish motion quality by itself.

The before/after Android navigation tests open every category, return, scroll the long pages, switch primary sections and open the source/history routes. The host test suite has **174 passing tests across 17 suites**, including **34 UI tests**; lint reports **zero errors**. Native device-test results and source/run links are recorded in the gallery index.

## Orion comparison and intentional differences

The bento slots, category tonal conversion, shapes, turning icon primitive, grouped rows, spring/press behavior and shared-bounds opening/closing come from the attributed Orion/Iris sources; see the [source map](../ORION_SCREEN_MAP.md). Settings contain AI Usage's real controls and callbacks.

The original Orion screenshots also show differences that remain visible: its hub starts with a compact heading and a quick-toggle grid, while AI Usage retains the larger header/search arrangement from the supplied Iris reference. Orion uses Space Grotesk in these default-theme captures; AI Usage uses the requested Google Sans Flex. The contents, number of categories and category order are application-specific. These are not claimed as a one-to-one copy of every screen.

The compact destination toolbar is intentional: the requested removal of repeated wide page banners is preserved. Orion's large destination hero and descriptive banner are therefore not reintroduced. AI Usage's bottom navigation, providers, authentication and forecast data remain application-specific. Category colors and different native elevation roles are deliberate; they are not expected to be one identical fill across all panels.

## Limits

This is a Pixel 9 **emulator** audit, not a physical Pixel 9 or Android 17 validation. It covers default font scale, German labels and fixed light/dark palettes. It does not cover every accessibility font scale, landscape/tablet geometry, live OAuth, every network error, or actual launcher widget placement. The widget controls are photographed without installed accounts. No “perfect” or complete absence-of-bugs claim is made.

Earlier failed rendering attempts are excluded from the gallery. The local software emulator booted Android but timed out opening the app; an AOSP ATD image disabled drawing; an intermediate capture needed a frame-commit wait. Only the final nonblank, frame-synchronized native series is published here.
