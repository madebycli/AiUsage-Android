# Iris design in AI Usage

Current expanded review: [AI Usage 0.9.3 gallery](orion-preview/README.md) and [Orion screen reference map](ORION_SCREEN_MAP.md). The sections below record the initial 0.9.2 integration.

Reference: [Iris brain/v2-beta, Build 102](https://github.com/madebycli/Iris/tree/60bbb256e588103ad18f9776ed8acfdde7c6f156).
Iris is read-only for this task. Earlier unpublished local work is not the reference.

The transfer uses the existing Compose/Material 3 Expressive stack. No assistant features or account logic are imported. The bottom navigation implementation, labels, ordering and settings remain unchanged.

## Adapted source

`ui/IrisDesign.kt` adapts the actual `OrionPrimitives.kt` from the pinned Iris commit: `orionSpringClick`, `orionFocusRing`, `orionReveal`, `OrionGroupItem`, `orionTonal`, `OrionSpinner`, `OrionBento`, `OrionHubHeader`. These originate in [Orion Store](https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e), RookieEnough and contributors, GPL-3.0. Iris changes are credited in that source. AI Usage removes Iris's settings dependency and shared-transition plumbing; Android's animation duration scale still applies. Complete GPL text ships at `assets/licenses/LICENSE-Orion-Store.txt`.

## Where it applies

- Settings: tonal bento tiles for accounts and navigation, grouped notification entry, connected native theme selector and explicit source links in About.
- Menu rows: per-segment shape and genuine gaps, tonal icons, flexible text column and trailing action.
- Usage and account cards: staggered reveal retained across lazy recycling, expressive spring press feedback, theme shape tokens.
- Details: spring-based page movement on the existing opaque page base; no transparent page overlap.
- All shared section headings follow the compact Iris/Orion hierarchy. Gauges, charts and provider data retain their meanings.

The existing palette, account authentication, reset calculations, background scheduling and widgets are not altered by the design transfer.

## Tracker migration fix

The screenshot identified the failed Claude Reset Tracker. On 12 November 2026 the old `codexreset.vercel.app` endpoints for **both** providers return HTTP 308 to `pingreset.com`. The public HTML client correctly rejects cross-origin redirects. The source URLs now use the canonical HTTPS domain directly; the redirect restriction remains intact. Both final pages returned HTTP 200 and preserve the existing parser contract. Trimmed real-response fixtures cover both migrated pages. Missing/invalid data still raises errors; no zero-percent fallback is introduced.

The Android 17 log excerpt itself contains lifecycle and scheduling events, without a crash stack trace. The reproducible URL migration, rather than that log, establishes the cause of the tracker failure.

## GitHub Preview release

Preview denotes a GitHub prerelease, not a different Android package. The normal release APK uses `com.madebycli.aiusage` and the existing Stable signing certificate. The manually triggered `preview-release.yml` builds and verifies it before publishing `v0.9.2-preview.1` as a prerelease with `latest=false`. The separate `.preview` application variant and branch APK were removed after clarification.
