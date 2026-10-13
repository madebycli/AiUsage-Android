# AI Usage 0.5: minimal, rounded, consistent

Design reference: [AniSync](https://github.com/Marco-9456/AniSync), inspected at
9395c5c140eed4ab086459e6d6c7b331641175f3 on 2026-10-13. No AniSync app name,
app icon, account functionality or unrelated modules are copied.

Both providers use the same neutral 28dp card surface and type hierarchy.
Small marks identify Codex (OpenAI blossom) and Claude (Claude sunburst).
No generic terminal/sparkle substitutes. Source SVG paths: MIT CodexBar
ProviderIcon-codex.svg / ProviderIcon-claude.svg. Logos identify their owners;
AI Usage is independent of OpenAI and Anthropic. See docs/licenses.

Typography follows AniSync's fully rounded Google Sans Flex (ROND=100).
OFL font sourced from AniSync, original Copyright 2015 Google LLC; static
400/500/700, opsz=18, width=100 instances subset to Latin/European punctuation
and currency, renamed Usage Rounded. It needs no font download or variable
font API at runtime on Android 8. OFL is included, including original copyright.
The smaller header, section accent strokes and provider pill tabs follow the
reference; bottom Verbrauch/Resets/Einstellungen tabs remain native.

Default palette: cool neutral surfaces, muted blue and lavender chart accents.
Both providers have equal surface brightness. Android wallpaper colors remain
an explicit option; existing explicit preference is retained. No login/dummy
preview on the main screen. Provider, source and history subpages have Back/title.
Routine prose is removed from usage/details and moved into About/source details.
Warnings, unknown data, provider status and account/auth instructions remain.

## Forecast consensus

`ForecastConsensus` gives each successful fresh source one equal vote per
provider and identical horizon. It averages source percentages, never adds them
or assumes independent events. 6h/24h/48h/7d remain distinct; no extrapolation.
This is a display consensus, not a calibrated probability or reset guarantee.
The overview shows a centered percentage, rounded bar/line and source count.
Source values, intervals, timestamps, errors, model notes and links are accessible
on Sources. Public history is a separate screen, distinct from account grants.

Fetch and reported publication must be within 2 hours of the current time, with
5 minutes future clock tolerance. Missing publication uses fresh fetch only and
is marked model-age unknown on Sources. Latest-error cached data remain visible
on Sources but do not vote. Conflicting duplicates from one source/horizon do
not vote; identical duplicates count once. Invalid/non-finite values are omitted.
No usable source means unknown, never 0%; a fresh reported 0% remains valid.

Security, stored accounts, PKCE, refresh ownership, provider response parsers,
request throttling/backoff and the stable public test signer are retained.
No backend or model calls are introduced. 0.5.0 (code 6) updates 0.3+ in place.

## Validation

Run the existing auth/parser/storage suite plus consensus regressions and
native Compose tests for provider gating, source navigation/back preservation,
partial data and signed-out privacy. Review native light/dark screenshots.
Release evidence belongs to the exact passing CI head and verified APK signer.
