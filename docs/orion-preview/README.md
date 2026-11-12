# AI Usage 0.9.4 Preview Gallery

An English review of the Orion settings port, opening and closing transitions, banner removal and typography correction. Whole-number percentages and widget fixes from 0.9.3 remain included. Click an image for its full resolution.

These are actual Compose/native Android host renderings and real widget-renderer outputs using test accounts. UI labels remain German to exercise long wording. No photograph or recording of a physical Android device is implied. [Detailed self-review](SELF_REVIEW.md) · [Screen-by-screen Orion sources](../ORION_SCREEN_MAP.md) · [Verification](VERIFICATION.md).

[One-sheet visual overview](images/overview.jpg)

## Settings and every submenu

The settings controller now follows Orion’s own `SettingsScreen`: a local saved menu, `SharedTransitionLayout`, fade effects and `PAGE_RESIZE` using `FillWidth` / `TopCenter`. A category tile shares its bounds with the **entire destination page**, in both directions. The fixed bento slots and long-press drag/trade gestures are ported from Orion. A tinted, integrated title and persistent search field follow the supplied reference image; the separate wide destination banners are removed.

| Settings hub | Appearance |
|---|---|
| ![Settings hub](images/settings.png) | ![Appearance page](images/appearance.png) |

| Accounts | Notifications |
|---|---|
| ![Accounts](images/accounts.png) | ![Notifications and background controls](images/notifications.png) |

| Navigation settings | Widgets management |
|---|---|
| ![Navigation settings](images/navigation.png) | ![Placed-widget management](images/widgets.png) |

| Dedicated About page | Large-font settings |
|---|---|
| ![About and source attribution](images/about.png) | ![Large German text](images/settings-large.png) |

The existing bottom navigation is preserved. Its order, visibility and start-page behavior continue to use the original implementation.

## Actual animation previews

| Open Settings | Tile to submenu |
|---|---|
| ![Settings entry](images/settings-entry.gif) | ![Shared tile transition](images/submenu.gif) |

| Filter settings | Whole-number count-up |
|---|---|
| ![Settings search](images/search.gif) | ![Actual quota ring counting in integers](images/percentage.gif) |

| Close a submenu | Close Settings |
|---|---|
| ![Close Appearance back into its original tile](images/submenu-close.gif) | ![Close the complete settings view](images/settings-close.gif) |

The closing recordings exercise both the reverse container transition and the complete settings exit. The root entry/exit uses Orion `Root.kt`’s original one-third-width horizontal slide and default fade parameters. Returning restores the hub’s scroll position, search and tile order. These GIFs are deterministic host-rendered frame sequences, not device frame-rate measurements. The ring's value can pass through 10, 11 and 12 without displaying 10.5 or changing its type-size factor. Final percentages also use whole numbers; data precision is retained internally.

## Welcome, sign-in and data pages

| Welcome | Codex sign-in |
|---|---|
| ![Welcome](images/welcome.png) | ![Codex login](images/login-codex.png) |

| Claude sign-in | Usage overview |
|---|---|
| ![Claude login](images/login-claude.png) | ![Account usage overview](images/usage.png) |

| Account details | Statistics |
|---|---|
| ![Account details](images/account-details.png) | ![Usage statistics](images/statistics.png) |

| Reset overview | Source status |
|---|---|
| ![Reset overview](images/resets.png) | ![Reset sources](images/sources.png) |

[Account ordering](images/sorting.png) retains drag handles and arrow controls. [Reset history](images/history.png) uses the same grouped surfaces and ordinary toolbar titles, without the removed wide banners. The extra wide headers on Home, statistics, reset/source/history pages, sign-in, widget configuration and settings destinations are gone, as are the colored title stripes. Existing authentication, filters, account actions, charts and reset-source semantics remain wired to the same operations. All 30 normal and emphasized Material typography styles now use the bundled Google Sans Flex family; previously the emphasized styles could fall back to the system font.

## Widgets: templates that actually apply

The Detailed, Compact and Rings choices modify the configuration saved for the placed widget. The separate size simulator is labelled as a simulation; Android's launcher owns the actual widget size.

| Configuration and applied preset | Account/window arrangement |
|---|---|
| ![Configuration empty state](images/widget-config.png) | ![Layout choices](images/widget-layout.png) |

| Detailed vertical accounts | Ring preset at 4×2 |
|---|---|
| ![Two detailed accounts vertically](images/widget-vertical.png) | ![Rings at a common widget size](images/widget-rings.png) |

| Time windows side by side | Time windows stacked |
|---|---|
| ![Side-by-side windows](images/widget-side.png) | ![Vertically stacked windows](images/widget-stacked.png) |

[Appearance controls](images/widget-appearance.png) retain per-widget theme, opacity, corner and type-size settings. The configuration screenshots include the no-account state; the rendered widget examples use named fixture accounts. They do not imply a successful live sign-in.
