# AI Usage 0.7

Native AniSync-style main titles now use the shared accent stroke. Reset inventory
uses icon rows, a single availability summary and expiry dates. Forecasts use
single rings with centered percentages; unsupported or outdated horizons remain
visible as unknown. Claude sources currently publish 48 hours and seven days.
The Vercel tracker's declared next sync, bounded to six hours, is respected instead
of excluding its entire 48-hour report after two hours. Recent fetch required.

## Actual statistics

Refreshes never increment a usage/limit counter. Legacy fetch counts remain readable
but are no longer incremented or shown. The calendar prefers real reported daily
tokens for Codex and imported daily message counts for Claude Code. If no daily
report exists, it explicitly shows sampled five-hour peaks, not daily consumption.
Missing days remain unknown. Server daily dates remain UTC, without timezone shifts.

Codex adapters follow the official openai/codex backend-client analytics and profile
contracts, read 2026-10-13. Profile totals/streaks/daily buckets; surface usage,
token/cache categories, credit events, Work/Codex reports, model/product/reasoning/
speed credit breakdowns, activities, plugins and skills are separate reports.
Eligibility varies by account. A 403/404 remains unavailable, never zero. Credits
keep signed corrections. Missing units remain unknown; credits are not dollars.
Overlapping totals and dimension breakdowns are never added together.

Claude personal OAuth exposes quotas, model scopes, reset grants and extra usage.
For local sessions/messages/tools/model tokens, an explicit stats-cache.json import
is available (1 MiB maximum). Reimport replaces its report. Local costUSD is a CLI
estimate, not an invoice. It does not establish a complete Claude/Cowork history.

Optional organization API reporting uses a separately supplied Admin key, validated
before saving under device Keystore encryption, never an Abo OAuth token. OpenAI
completions usage and costs; Anthropic messages usage, costs and Claude Code daily
productivity/model/tool reports are independent sources. Scope is explicitly the
organization, not personal subscription use. The reporting clients issue GETs only.
30-day usage/cost pages and at most 1000 Claude Code actors are bounded; further
pages are labelled incomplete. No pricing table or speculative token-to-cost math.
Enterprise Cowork analytics need a different API/key and are not claimed supported.

## Background and notifications

Unique WorkManager schedule every four hours with network constraint, migrated
from the old 24-hour job. Separate account gates; current quotas for both providers
plus one small Codex profile read. No forecasts, API billing or full analytics in
background. Rate limits are honored, failures retain existing values and dates.

Visible notification permission status and Allow button request POST_NOTIFICATIONS
on Android 13+. Permanent denial or system-blocked channels lead to Android settings.
Settings recheck permissions on resume and reschedule provider reset deadlines.
Four independent 5h/weekly reminder channels and lead settings are retained.
An optional explained battery-settings handoff helps users allow background work;
no forced exemption, exact alarm permission or continuous foreground service.
Android may defer jobs and notifications even with unrestricted battery settings.

References:
- https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/analytics.rs
- https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/profile.rs
- https://help.openai.com/en/articles/20001478
- https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/completions
- https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs
- https://platform.claude.com/docs/en/manage-claude/usage-cost-api
- https://platform.claude.com/docs/en/manage-claude/claude-code-analytics-api
- https://github.com/anthropics/claude-code/issues/83678
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/training/monitoring-device-state/doze-standby
