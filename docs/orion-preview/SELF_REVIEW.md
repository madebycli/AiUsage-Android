# Visual self-review · 0.9.4

This review is in English. The screenshots use the app’s German labels to exercise long wording. They are actual Android host renderings with fixture data, not generated mockups.

## Corrections in this preview

1. **The previous settings transition shared only a decorative header.** The settings menu now owns a local saved destination, following Orion’s `SettingsScreen`. The matching shared key covers the source bento and the entire destination Scaffold. Orion’s default effect specs and `scaleToBounds(FillWidth, TopCenter)` drive opening and closing, without remeasuring page contents every frame.
2. **The previous settings grid did not match the reference.** Fixed Orion slots now provide a full-width first tile, a tall left tile beside two compact tiles, and lower full-width tiles. The integrated heading and persistent search follow the supplied screenshot. The actual Orion long-press drag/trade and spring glide code is adapted to the six AI Usage categories; the chosen order persists.
3. **Repeated wide identity banners crowded the pages.** The `PageHero` component and every use are removed. Settings destinations, usage, statistics, resets, source status, history, sign-in and widget configuration use their ordinary titles. About retains its app icon/version identity. The integrated settings hub heading remains as requested in the reference.
4. **Colored vertical title stripes were distracting.** The stripe option and its drawing code are removed from the shared scaffold.
5. **Some text did not use the selected font.** The embedded files were already Google Sans Flex derivatives, verified by their original Google font metadata. The 15 emphasized Material styles had not been assigned the family. All 30 typography slots now explicitly use it. The font asset is not misrepresented as a new download.
6. **Saved scroll position affected the gallery.** Returning from About correctly restored the scrolled hub. The gallery test now explicitly scrolls to the integrated heading before taking the overview image; the app retains the saved position during ordinary use.

## Retained work

Whole-number percentage frames, stable ring type size, applied widget presets, saved widget edits, vertical account/window layouts and the Rings size correction remain included. Authentication, account actions, permission controls and reset computations retain their existing callbacks. The bottom-navigation implementation is unchanged.

## Evidence and intentional differences

The gallery contains 25 screenshots, six recorded frame sequences and an overview sheet. It includes settings entry/exit and tile-to-page opening **and return** animations. The root settings slide uses the same one-third-width offset and default animation parameters as Orion `Root.kt`. Tests exercise all six destinations, search, drag persistence, bento geometry, long German labels, font assignment, rotation and the earlier data/widget behavior.

This is a port of Orion’s settings layout and transition implementation into AI Usage, not a replacement with store features. AI Usage categories and controls remain. The supplied reference adds a persistent search and integrated decorative heading; the user explicitly requested removing the separate destination banners, so those are intentionally omitted. Large-font mode uses one column for readability.

No phone or emulator is attached. Host screenshots do not establish Android 17 device frame rate, launcher resizing, live OAuth completion or background delivery. The final normal-ID APK is signed in GitHub with the existing release key and checked against Stable 0.9.1 before publication.
