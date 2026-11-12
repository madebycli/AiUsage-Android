# Orion references for every AI Usage screen

Reference source: [Orion Store at e561e91](https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e), RookieEnough and contributors, GPL-3.0. The attributed primitives were originally integrated through [Iris Build 102](https://github.com/madebycli/Iris/tree/60bbb256e588103ad18f9776ed8acfdde7c6f156). Iris remains unchanged.

The table distinguishes reused components from visual references. Account authentication, quota parsing and reset computation remain AI Usage operations.

| AI Usage destination | Orion reference | Applied implementation |
|---|---|---|
| Settings hub | [Settings.kt:655](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Settings.kt#L655), `Bento` at 857, category hues at 217 | Six actual destinations; attributed bento, spinner, tonal conversion and spring/focus behavior. |
| Settings search | [Root.kt:386](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Root.kt#L386) | Pill/input presentation is a visual reference. AI Usage uses native Material TextField and AnimatedVisibility with a local settings index. |
| Appearance | Settings `Page` at 880, `ThemePicker` at 1130, `SRow`/`Toggle` at 235/245 | Rotating identity header, native connected theme selector, grouped toggle rows. |
| Notifications and background settings | Settings `Page`, `SRow`, `Toggle`, `InfoCard` at 260 | Same header and groups; full-row accessible switches with native checked/unchecked thumb icons. Existing permissions and scheduling actions retained. |
| Navigation settings | Settings `LayoutPicker` at 1170 and grouped controls | New page presentation around the existing ordering/start controls. The actual bottom navigation implementation remains byte-for-byte unchanged. |
| Widget management | Settings `Page`, grouped `SRow` | Real placed-widget IDs open their existing configuration activities; empty state explains launcher placement. |
| Widget configuration | Settings `Page`, `Section`, `Toggle` and layout selection patterns | Native connected presets and layout choices; application-specific bitmap renderer and persistence. Orion does not supply this widget renderer. |
| About | [About.kt:104](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/About.kt#L104), `DevCoin`; Common grouped items | Adapted turning halo around the existing AI Usage icon, app/version identity and dedicated source/license rows. |
| Welcome | [Common.kt:316](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Common.kt#L316), identity/empty-state patterns and `CtaButton` | Shape-backed identity, provider choices and the actual copied Material Expressive CTA component. |
| Codex and Claude login | Common `GroupItem` at 364 and `CtaButton` at 381; [Gate.kt:138](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Gate.kt#L138) as input presentation reference | Grouped numbered steps, expressive confirmation buttons, existing password fields and callbacks. No Orion account/authentication flow is imported. |
| Account ordering | Settings `QuickTilesSheet` at 801 and grouped reorder controls | Compact identity, theme-shaped rows and tonal drag selection; existing handles, arrow actions and fixed drag step retained. |
| Usage overview and account details | [StoreScreen.kt:285](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/StoreScreen.kt#L285), Common `springClick`/`reveal`/`GroupItem` | Compact identity header, actual grouped surfaces and persistent staggered entry; stronger expressive press response. |
| Statistics | Settings `StatHero` at 1067, Common `SectionTitle`/`GroupItem` | Compact identity and grouped data panels. Charts retain AI Usage measurements and interactions. |
| Resets | Common section/group components and Settings identity header | Compact heading, grouped forecasts and animated provider content keyed to the displayed provider. |
| Reset history and source status | Common grouped content and section hierarchy | Same presentation family on both detail routes; original filters, timestamps, uncertainty and source actions retained. |
| Settings tile-to-page animation | [Settings.kt:343](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Settings.kt#L343), `PAGE_RESIZE` at 626 | Actual shared-bounds implementation adapted through Iris, with matching tile/header keys and scale-to-bounds sizing. |

## Source and licensing

`ui/IrisDesign.kt` contains the attributed reusable source. `DesignPages.kt` applies those components to AI Usage destinations. The complete upstream GPL text remains bundled in `assets/licenses/LICENSE-Orion-Store.txt` and accessible through About's source references. The normal app package and release signing identity are retained.
