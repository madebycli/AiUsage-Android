# Visual self-review

This review is written in English. The screenshots deliberately exercise the app's German labels, including long settings names. They show native Compose components with fixture data; they are not generated illustrations.

## Findings corrected during implementation

1. **Fractional counter frames changed the width of the percentage.** Display formatting now rounds to whole numbers in the app, notifications, widgets and accessibility labels. Measurements, calculations, prices and non-percentage units keep their precision. The real percentage ring uses a constant type-size factor for 0–100%; its count-up frames are checked for digit-only percentage text.
2. **The original widget size tabs were only simulations.** They did not change stored configuration. Real Detailed, Compact and Rings presets now update the per-widget settings; the size simulator is explicitly labelled separately.
3. **Automatic account columns prevented a fixed vertical layout.** Users can now select automatic layout, a vertical stack or a two-column grid. Rings and percentage layouts also offer vertically stacked time windows.
4. **Rings silently became Compact at a common 4×2 size.** The previous minimum-height threshold was slightly larger than the available content. It is corrected and covered by a regression test. Truly insufficient sizes still use a compact fallback, now explained in the configuration UI.
5. **Settings were only a pair of decorated tiles.** They now contain six destinations, including dedicated Appearance, Widgets and About pages. Every other existing destination also uses the shared presentation family.
6. **Long German tile names broke inside words.** The final tiles use native single-line auto sizing. A layout test checks every title for overflow. Large-font layouts use a single column and taller tiles.
7. **Background decorations increased header height.** Their layout now follows the measured foreground with `matchParentSize`; they no longer reserve empty space below the actual header content.
8. **Repeated login headings duplicated action labels.** Navigation bars and page identity areas no longer repeat the same login title. The first code step has its own heading, distinct from the Copy action.
9. **Widget configuration could lose edits during recreation.** The selected configuration now has a saveable serialized state. The preset UI test restores the saved state before confirming the result.

## What the images establish

The gallery covers the real settings destinations, source attribution, both login screens, overview and detail cards, statistics, reset views, widget configuration and actual widget bitmaps. Frame sequences show settings entry, a tile-to-subpage transition, expanding search and the actual percentage ring. A separate test confirms the decorative shape continues rotating.

The actual bottom-navigation source block was compared against the approved 0.9.2 implementation and is unchanged. Existing navigation tests remain part of the suite.

## Limits

Robolectric API 35 with native graphics is an Android host runtime, not a connected phone or launcher. These images do not establish Android 17 device frame rate, real OAuth browser completion, live account access, launcher resizing behavior or background delivery. The final normal-ID APK is built and signed by GitHub using the existing release key. Its signature is checked against Stable 0.9.1 before publication.
