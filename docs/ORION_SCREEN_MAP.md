# Orion references for every AI Usage screen

Reference source: [Orion Store at e561e91](https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e), RookieEnough and contributors, GPL-3.0. The attributed primitives were originally integrated through [Iris Build 102](https://github.com/madebycli/Iris/tree/60bbb256e588103ad18f9776ed8acfdde7c6f156). Iris remains unchanged.

The table distinguishes reused components from visual references. Account authentication, quota parsing and reset computation remain AI Usage operations.

| AI Usage destination | Orion reference | Applied implementation |
|---|---|---|
| Settings hub | [Settings.kt:655](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Settings.kt#L655), `Bento` at 857, category hues at 217 | Six destinations with Orion’s fixed bento slots, saved local menu, long-press drag/trade and spring glide; integrated title/search follow the supplied image. |
| Settings search | [Root.kt:386](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Root.kt#L386) | Persistent native Material TextField as in the supplied reference, filtering the six AI Usage categories; no separate expanding search banner. |
| Appearance | Settings `Page` at 880, `ThemePicker` at 1130, `SRow`/`Toggle` at 235/245 | Ordinary toolbar with a compact turning category emblem, connected theme selector and grouped tonal toggle rows. Google Sans Flex is assigned to all normal and emphasized typography slots. |
| Notifications and background settings | Settings `Page`, `SRow`, `Toggle`, `InfoCard` at 260 | Banner-free toolbar with a turning category emblem; grouped native surface rows, 16 dp content spacing and full-row accessible switches with native checked/unchecked thumb icons. Existing permissions and scheduling actions retained. |
| Navigation settings | Settings `LayoutPicker` at 1170 and grouped controls | New page presentation around the existing ordering/start controls. The actual bottom navigation implementation remains byte-for-byte unchanged. |
| Widget management | Settings `Page`, grouped `SRow` | Real placed-widget IDs open their existing configuration activities; empty state explains launcher placement. |
| Widget configuration | Settings `Page`, `Section`, `Toggle` and layout selection patterns | Native connected presets and layout choices; application-specific bitmap renderer and persistence. Orion does not supply this widget renderer. |
| About | [About.kt:104](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/About.kt#L104), `DevCoin`; Common grouped items | Adapted turning halo around the existing AI Usage icon, app/version identity and dedicated source/license rows. |
| Welcome | [Common.kt:316](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Common.kt#L316), identity/empty-state patterns and `CtaButton` | Shape-backed identity, provider choices and the actual copied Material Expressive CTA component. |
| Codex and Claude login | Common `GroupItem` at 364 and `CtaButton` at 381; [Gate.kt:138](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Gate.kt#L138) as input presentation reference | Grouped numbered steps, expressive confirmation buttons, existing password fields and callbacks. No Orion account/authentication flow is imported. |
| Account ordering | Settings `QuickTilesSheet` at 801 and grouped reorder controls | Ordinary title, theme-shaped rows and tonal drag selection; existing handles, arrow actions and fixed drag step retained. |
| Usage overview and account details | [StoreScreen.kt:285](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/StoreScreen.kt#L285), Common `springClick`/`reveal`/`GroupItem` | Ordinary toolbar title, actual grouped surfaces and persistent staggered entry; stronger expressive press response. |
| Statistics | Settings `StatHero` at 1067, Common `SectionTitle`/`GroupItem` | Ordinary title and grouped data panels. Charts retain AI Usage measurements and interactions. |
| Resets | Common section/group components and Settings identity header | Ordinary title, grouped forecasts and animated provider content keyed to the displayed provider. |
| Reset history and source status | Common grouped content and section hierarchy | Same presentation family on both detail routes; original filters, timestamps, uncertainty and source actions retained. |
| Settings tile-to-page animation | [Settings.kt:343](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Settings.kt#L343), `PAGE_RESIZE` at 626 | Actual shared-bounds implementation adapted through Iris, with matching tile/**full destination** keys, local saved menu and scale-to-bounds sizing; the reverse transition returns to the source tile. |
| Settings entry/exit | [Root.kt:486](https://github.com/RookieEnough/Orion-Store/blob/e561e91e9409d9d9b437ab7acf919cd115769d7e/app/src/main/java/com/orion/store/ui/Root.kt#L486) | Original `slideInHorizontally { it / 3 } + fadeIn()` / reverse exit parameters, adapted to the existing AI Usage root navigation. |

## Source and licensing

`ui/SettingsScreen.kt` directly ports Orion’s settings controller, fixed slots and drag behavior. `ui/IrisDesign.kt` contains the attributed reusable source. Wide page banners and colored title stripes are removed throughout; separate destination heroes are intentionally omitted per user request. `DesignPages.kt` applies those components to AI Usage destinations. The complete upstream GPL text remains bundled in `assets/licenses/LICENSE-Orion-Store.txt` and accessible through About's source references. The normal app package and release signing identity are retained.

## Native Pixel 9 comparison

The [0.9.5 gallery and English self-review](pixel9-audit/README.md) compare unchanged 0.9.4 and corrected 0.9.5 in both themes at 1080 × 2424 px, alongside the unmodified official Orion 2.7.5 APK. The dark foreground provision, compact destination emblems and unified 16 dp gutters are documented there. AI Usage intentionally keeps its ordinary destination toolbar instead of Orion’s separate large destination hero.
