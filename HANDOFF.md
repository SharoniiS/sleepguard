# SleepGuard Android POC — Agent / Developer Handoff

> Purpose of this file: give the **next agent or developer** everything needed to
> continue this project without re-deriving context. Read this first, then `README.md`
> (user/run guide) and the source. If anything here conflicts with the code, the code
> wins — update this file.

Last updated: 2026-06-15.

---

## 1. Product context (the "why")

**SleepGuard** is a sleep-tracking product for people with **PTSD-related sleep problems**
(nightmares, fragmented sleep, inaccurate sleep recall). The eventual product combines:

1. **Passive** Android phone screen activity during the night, and
2. A short **morning self-report**,

into a clear, honest **night timeline**.

The guiding ethic, which constrains every technical decision below:

> **We measure device activity, not sleep. We report what the phone did; we never infer
> what the person felt or did.** No nightmare detection, no diagnosis, no "you slept X."
> This matters especially for a PTSD population, where false certainty is harmful.

---

## 2. What this milestone is (and is NOT)

This repo is a **native Android POC** that reduces exactly two risks:

1. **Can we read nighttime screen activity at all?** → YES, validated on a real Samsung.
2. **Can we turn it into a meaningful, flexible "pattern around sleep"?** → YES, via
   `NightPatternAnalyzer`, validated on one real night, with 25 passing unit tests.

It is **NOT** the full product. Deliberately **out of scope** (do not add without an
explicit decision — see §7):
Base44 integration, any backend/network/sync, login, database/local storage, AI insights,
medical recommendations, iOS, smartwatch, background/foreground services, Accessibility
Service, notification listener, raw touch tracking.

---

## 3. Current status

- ✅ Builds (AGP 8.7.3 / Gradle 8.9 / Kotlin 1.9.24, compileSdk 35, minSdk 28, JDK 17).
- ✅ Runs on a real Samsung; Usage Access flow works; events read correctly.
- ✅ `NightPatternAnalyzer` produces a correct report on real data.
- ✅ The **schedule-agnostic structural** model (rest roles + pattern) drives the user-facing
  report. The clock-based quiet-block labels still compute internally — they feed the
  sleep-like / awakening detection and the displayed confidence — but are no longer shown
  directly. See §5.
- ✅ Local per-night storage (debug/POC): nights auto-saved on collect; backfill/show/clear.
- ✅ **36 unit tests** (`NightPatternAnalyzerTest` 27 + `NightStorageTest` 5 +
  `InteractionHistoryTest` 4). (Static count — not executed in the cleanup environment; see §9.)
- 🔒 **Feature freeze is ON.** We are in a **multi-night validation phase**. Only bug fixes
  and wording changes are allowed until the user explicitly lifts the freeze.

---

## 4. What exists (architecture)

Single-Activity, View-based (XML + ViewBinding). All analysis logic is **pure JVM** (no
Android deps) so it is unit-testable.

```
app/src/main/java/com/sleepguard/poc/
  MainActivity.kt          UI wiring, permission re-check, report rendering (structural model)
  UsageAccessManager.kt    Check Usage Access (AppOpsManager, API-28-safe) + open Settings
  ScreenEventsCollector.kt UsageStatsManager.queryEvents -> filtered RawScreenEvent list + raw/filtered counts
  NightPatternAnalyzer.kt  The "pattern around sleep" analyzer: schedule-agnostic structural
                           model (drives the report) + internal clock-based quiet-block labels
  Models.kt                Data classes / enums / AnalysisConfig / WindowAnchors / RestRole / RestPattern / RestPeriod
  NightRecord.kt           Serializable storage DTOs (debug/POC) + STORAGE_SCHEMA_VERSION
  NightRecordMapper.kt     Pure NightPatternResult -> NightRecord mapping
  NightRepository.kt       Local JSON persistence in app-private filesDir (load/upsert/clear)
  InteractionHistory.kt    Pure helpers: cross-night event flatten + long-inactivity blocks
app/src/test/java/com/sleepguard/poc/
  NightPatternAnalyzerTest.kt  27 tests (labels, precedence, awakenings, verdicts, future-window cap, structural model, ...)
  NightStorageTest.kt          5 tests (mapper, JSON round-trip, upsert merge)
  InteractionHistoryTest.kt    4 tests (flatten/dedup, long-inactivity threshold)
```

**Buttons** (all retrospective, on-demand — collection happens only on press):
1. **Collect Last Night** — captures the full day (yesterday 18:00 → today 18:00) and runs
   `NightPatternAnalyzer` on the 22:00 → 18:00 analysis window. Auto-saves the night. The
   capture window is wider than the analysis window only so the "all interactions" log misses
   nothing; the analyzer ignores events outside its anchors.
2. **Backfill Available History** (debug/POC) — probes the last 10 retained nights and saves
   each one that still has data (skips empty nights).
3. **Show All Logs** — toggles the History list between night summaries and every stored
   interaction (full date + time).
4. **Clear Stored Data** (debug/POC) — wipes local storage.

The screen shows: permission status, collection status, Last Night (the structural report),
History (saved nights + estimated-sleep blocks + optional full interaction log), and a Debug
block (timezone, window, raw vs filtered counts, saved-night count).

---

## 5. Decisions already made (and why) — do not silently reverse these

### Data acquisition
- **API:** `UsageStatsManager.queryEvents(start, end)`. The only event types kept are
  `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN`.
- **Permission:** `PACKAGE_USAGE_STATS` (special "app ops" permission). Checked with
  `AppOpsManager.checkOpNoThrow` (works on API 28; no unguarded API-29+ calls). Opened via
  `Settings.ACTION_USAGE_ACCESS_SETTINGS`, re-checked in `onResume`.
- **minSdk 28** — keep permission/check code API-28-safe.
- **Retrospective + on-demand only.** No background/foreground service, no WorkManager.

### Privacy (hard constraints)
- Collect **only timestamp + event type**. If a `UsageEvents.Event` carries
  `packageName`/`className`, **ignore it** — never store or display it.
- **No `INTERNET` permission.** The app is fully offline. (It was removed deliberately.)
- Never collect content, app names, URLs, input, location, mic, camera, contacts, etc.

### Interpretation principles (these are the product's spine)
- **Screen-down ≠ asleep; screen-up ≠ awake.** Sleep is inferred from *absence* of activity
  and is always an estimate.
- **`KEYGUARD_HIDDEN` (unlock) is the strongest "consciousness" signal.** A bare
  `SCREEN_INTERACTIVE` may be just a notification lighting the screen.
- **Never say "you slept X."** Use "possible sleep-like quiet period." Never infer the
  *cause* of an awakening (no nightmare/PTSD-episode claims).
- **Raw event display names are literal facts**, no interpretation: `Screen On / Screen Off
  / Locked / Unlocked` (chosen over "descriptive" names precisely to avoid assuming
  anything about the user).

### `NightPatternAnalyzer` design (the MVP logic)
- **Official window:** yesterday **22:00 → today 18:00**, device timezone. Computed as
  absolute `WindowAnchors` (windowStart, 04:00, 06:00, 12:00, windowEnd=18:00) to avoid
  across-midnight ambiguity. **Do not assume sleep is 23:00–08:00.**
- **Meaningful interaction (REAL_USE):** unlocked (`KEYGUARD_HIDDEN`) **OR** screen-on
  ≥ 60 s. Configurable via `AnalysisConfig`. Notification glows / micro-taps are **ignored**
  so they don't falsely break a quiet period.
- **Quiet-block label precedence (first match wins):**
  1. `DELAYED_SLEEP_LIKE` — ≥ 3h **and** starts in [04:00, 12:00)
  2. `MORNING_RECOVERY_SLEEP` — ≥ 90 min **and** starts in [06:00, 12:00)
  3. `DAYTIME_RECOVERY_SLEEP` — ≥ 90 min **and** starts in [12:00, 18:00)
  4. `MAIN_SLEEP_LIKE_BLOCK` — ≥ 4h
  5. `SHORT_SLEEP_LIKE_BLOCK` — ≥ 90 min
  6. `MICRO_QUIET_BLOCK` — ≥ 20 min
  - **Decision:** `delayedSleepLatestStart = 12:00` — a long block starting after noon is
    `DAYTIME_RECOVERY_SLEEP`, not `DELAYED`. (e.g. 07:00–11:00 → DELAYED; 14:00–17:30 →
    DAYTIME_RECOVERY.)
- **Night verdict (`NightPattern`):** `CONSOLIDATED_SLEEP_LIKE`, `FRAGMENTED_SLEEP_LIKE`,
  `DELAYED_SLEEP_LIKE`, `RECOVERY_SLEEP`, `ACTIVE_NIGHT`.
  - **Decision:** `ACTIVE_NIGHT` only when **no** sleep-like block ≥ 90 min exists anywhere.
    Multiple `SHORT` blocks → `FRAGMENTED_SLEEP_LIKE`, not `ACTIVE_NIGHT`.
- **Awakenings:** REAL_USE interruptions (≤ 30 min) sandwiched between two sleep-like blocks.
  Reported as a **lower bound** (awakenings without phone use are invisible). Cause never inferred.
- **Future-window cap:** `effectiveAnalysisEnd = min(windowEnd, now)`. The analyzer NEVER
  infers activity or quiet periods after the collection moment (prevents a phantom afternoon
  nap when collecting before 18:00). Implemented via the `nowMillis` param on `analyze()`.
- **Confidence (HIGH/MEDIUM/LOW):** measurement certainty of the sleep claim, not a medical
  score. LOW for active night / window-opens-in-quiet / no morning interaction.
- **UNKNOWN is honest:** nullable fields (`phoneDownTime`, `preSleepPhoneTime`, etc.) render
  as "Unknown" — never as 0, never fabricated.

### Tunable knobs (`AnalysisConfig`, current defaults)
`meaningfulMinScreenOnSeconds=60`, `microQuietMinMinutes=20`, `shortSleepMinMinutes=90`,
`mainSleepMinMinutes=240`, `delayedSleepMinMinutes=180`, `recoveryMinMinutes=90`,
`preSleepLookbackMinutes=120` (pre-sleep phone use is the **last 2h before phone-down only**),
`awakeningMaxMinutes=30`, `windowOpensInQuietMinMinutes=90`.

### Schedule-agnostic structural model (the forward direction)
`NightPatternAnalyzer` also produces a **clock-free** structural view, because the target
population sleeps at irregular hours and clock-band labels mislead. It classifies each quiet
block by **role**, not by time of day:
- `PRIMARY_REST` — the **longest** sleep-like block, whenever it occurs (rank-based, not
  duration-gated, not clock-based).
- `SECONDARY_REST` — any other sleep-like block (a nap).
- `BRIEF_QUIET` — a 20–90 min gap.

Verdict (`RestPattern`): `CONSOLIDATED` (one dominant rest, ≤1 awakening) / `FRAGMENTED`
(multiple rests and/or several awakenings) / `MINIMAL_REST` (no sleep-like block).
Clock time is kept only as **neutral display metadata**, never as a classification input.
Result fields: `restPattern`, `restPeriods`, `primaryRest`, `secondaryRests`,
`firstUseAfterPrimaryRest`. The migration plan (the structural model is intended to eventually
replace the clock labels in the user-facing report) lives in
[`SCHEDULE_AGNOSTIC_DESIGN.md`](SCHEDULE_AGNOSTIC_DESIGN.md).

### Local storage (debug/POC)
Because Android prunes raw usage events after a few days, nights are persisted so history can
accumulate. Decisions:
- **On-device only.** Single JSON file in app-private `filesDir` (`sleepguard_history.json`),
  via kotlinx.serialization. No external files, no sharing/export, no network, no permissions.
- **Storage DTOs are decoupled** from the analyzer's runtime models (`NightRecord` etc.), so
  the analyzer can keep changing without breaking saved files. Enum values stored as strings.
- Each record carries **`schemaVersion`, `analyzerVersion`, and a config snapshot** for future
  migration / re-analysis.
- **`recordId == nightOf`** (the morning's ISO date): one record per night; re-collecting a
  night upserts (no duplicates); stable across timezone/window-time shifts.
- **Auto-save** on every SleepGuard collect. **Backfill** skips nights with no retained data so
  it never overwrites a good record with an empty one.
- **Raw events are stored for DEBUG/TESTING only.** Production / Base44 sync must use the
  summary fields, never the raw events. (Stated in `NightRecord.kt`.)
- Privacy unchanged: only timestamps + event types + derived summary; never content/app names.

---

## 6. Hard constraint: data retention (read before promising history)

The screen/keyguard events come from `queryEvents`, whose **raw event stream is only retained
for a few days** (stock Android ~up to a week; **Samsung prunes aggressively**, often less).
The longer-retention aggregated API (`queryUsageStats`, months) **does NOT contain screen
on/off or keyguard events**, so it cannot extend our reach.

**Implication:** you cannot reliably backfill weeks of nights. The real product must **capture
each night going forward and persist the summary** — which requires storage (currently out of
scope). Do not promise multi-week history from on-demand fetching.

---

## 7. Working agreement with the user (important)

- **Validation-driven.** The user runs the app on a real device and confirms results before
  moving on. Respect the **feature freeze** — propose, don't add. Ask before building features.
- **Plain language.** The user is product-focused; explain in plain terms, avoid unexplained
  jargon, and present concrete options for decisions.
- **No assumptions about the user.** Literal, factual reporting only (see §5 ethics).
- Default working language is English; Hebrew is welcome for long structural explanations.

---

## 8. Next steps / open decisions (need explicit go-ahead)

In progress:
0. **Schedule-agnostic migration** ([`SCHEDULE_AGNOSTIC_DESIGN.md`](SCHEDULE_AGNOSTIC_DESIGN.md)):
   **Phases 1–2 done** — the structural model is computed alongside the clock model AND now
   drives the user-facing report. The clock-based labels still run internally (sleep-like /
   awakening detection + confidence) but are not shown. **Phase 3 (optional, not started)** —
   retire the internal clock layer (labels, anchors, DELAYED/RECOVERY verdicts); this is a
   behavior change to the displayed confidence and would rewrite the clock-specific tests, so
   do NOT start it without an explicit decision. Phase 4 (adaptive/personal-baseline window)
   comes later.

Done:
- **Retention probe + local nightly storage** — implemented as "Backfill Available History"
  (probe + save) plus auto-save on collect. See §5 "Local storage".

Not started:
1. **Base44 sync** (the original "next milestone"): POST the analyzed **summary** (never raw
   events / private data) to a Base44 backend function, after real-device + multi-night
   validation succeeds. Adds `INTERNET`. **Do not start until the user says validation is complete.**

Also pending (wording-only, optional): plain-language names for the analysis **block labels**
(`MAIN`/`DELAYED`/`SHORT`/`MORNING_RECOVERY`/`DAYTIME_RECOVERY`/`MICRO`) — not yet done.

---

## 9. Build / test / run notes

- **No Gradle wrapper jar/scripts** are committed (`gradlew.bat` will "not be recognized" in a
  terminal). Run from **Android Studio**: open the project, let it sync, ▶ to run on a device.
- **Run tests** from Android Studio: right-click `com.sleepguard.poc (test)` → *Run tests in
  'com.sleepguard.poc'*. Expect **39 green**.
- See `README.md` for the full run/grant-permission/physical-device walkthrough.
