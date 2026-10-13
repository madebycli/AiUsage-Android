# AI Usage 0.6: measured activity and reset reminders

AniSync remains the main design reference. Its GPL-3.0 SegmentedTabGroup from
9395c5c140eed4ab086459e6d6c7b331641175f3 is directly adapted for all provider,
period, source and reminder selectors (haptic choice). Same rounded typography,
neutral cards and section accents throughout. No AniSync app identity, anime
artwork or OAuth code is used. See LICENSE and docs/DESIGN-0.5.md for fonts/marks.

## What the calendar measures

The account API does not supply complete daily token/activity totals. Each day
shows the highest successfully measured five-hour quota percentage, measurement
count and date in the device's local timezone. It is a sampled peak, not daily
consumption. Unknown days are unknown, not zero or proof of no usage. Values
start with real measurements after the update; no fabricated historical heatmap.
Month is a GitHub-style twenty-week grid; Week shows seven day bars. Tap a day.
366 daily buckets are retained encrypted in the independent provider record.
Same-timestamp credit enrichment is not another sample; older writes are ignored.

A unique WorkManager periodic job requests account data about every24h with
network availability (first background read after6h). Opening/resuming the app
also refreshes. Android Doze, force-stop, network and battery policy can delay or
prevent runs: no guaranteed at-least-once-every24h claim, no exact alarms or
battery-exemption prompts. Failures never create measurements or fake zeros.
Separate shared account mutexes coordinate foreground/background rotation and
logout; AtomicFile operations share a process-local namespace lock.429 retry
boundaries are remembered. There is no new server or model call.

## Reset notifications

Settings → Notifications & refresh. Opt-in with Android13+ notification permission.
Daily measurement is independently configurable. Each connected provider has
separate five-hour and weekly rules, lead0/5/15/30/60min and a system channel
for sound/vibration/lock-screen preferences. Only-if-used is configurable.

One unique delayed job per provider/window uses only its reported resetAt, not
community forecasts. New deadlines replace old jobs; unchanged deadlines do not
requeue on every refresh. Rule, reported timestamp and random account generation
are the only WorkData, never credentials. Delivery verifies current settings,
permission, account existence, generation and matching latest recorded window.
Logout invalidates/cancels that provider's scheduled and visible reminders.
No reset time means no invented job. One notification per reported deadline;
very late (>6h) jobs drop. Tapping opens that provider's details. Android may delay
notifications, too. Wording reports a provider deadline, not proof a reset happened.

History tabs now strictly select a source, including a dedicated Tracker tab.
Matching canonical original links deduplicate inside one source; switching an
API source cannot display a cached feed under the wrong name. Source forecasts
still remain separate by provider/horizon and are not personal reset events.

0.6.0/code7 updates0.3+ with the same public debug-test signer. Accounts retained.
Keep the old APK until update verification; do not clear app data to install.
