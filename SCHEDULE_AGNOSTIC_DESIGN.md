# SleepGuard — Schedule-Agnostic Analysis: Design & Migration

> Status: **Phases 1–2 implemented.** The schedule-agnostic structural model
> (`RestRole` / `RestPattern` / `RestPeriod`) runs alongside the clock-based model **and now
> drives the user-facing report** (Phase 2). The clock-based labels still compute internally
> (sleep-like / awakening detection + displayed confidence). Phase 3 (retire the clock layer)
> and Phase 4 (adaptive window) are **not started** — Phase 3 changes displayed confidence and
> rewrites the clock-specific tests, so it needs an explicit decision. The `NightAnalyzer` /
> `NightAnalyzerTest` referenced in older notes below no longer exist.

## Goal

> **SleepGuard should detect *patterns around sleep*, not enforce assumptions about when
> sleep is supposed to happen.**

Our target population (PTSD, fragmented sleep, delayed/inverted schedules, white nights,
shift-like behavior) makes fixed-clock labels actively misleading. This design removes
clock dependence from classification, keeping clock time only as **neutral display metadata**.

---

## 1. What currently depends on absolute clock times

The current `NightPatternAnalyzer` splits cleanly.

**Already schedule-agnostic (~75%) — keep as-is:**
- `buildRealUseInteractions` (meaningful-interaction filter: unlock OR ≥60s).
- `buildQuietBlocks` (gap detection).
- `MAIN` / `SHORT` / `MICRO` thresholds — duration-based.
- Awakening detection (sandwiched interruptions ≤30 min).
- `phoneDownTime`, `preSleepPhoneTime`, `longestQuietBlock`.
- `windowOpensInQuiet`, future-window cap (`effectiveEnd = min(windowEnd, now)`).

**Clock-dependent — to migrate:**
- `labelFor()`: `DELAYED_SLEEP_LIKE` (04:00–12:00), `MORNING_RECOVERY_SLEEP` (06:00–12:00),
  `DAYTIME_RECOVERY_SLEEP` (12:00–18:00), via `WindowAnchors.morningEarliest/morningStart/noon`.
- Verdict: `overnightSleepLike` (start < 06:00), `recoveryPrimary`, and the
  `DELAYED`/`RECOVERY_SLEEP` verdicts.
- `firstMorningInteraction` fallback to "first use after 06:00".
- The `22:00 → 18:00` window placement (softer assumption; addressed in Phase 4).
- Naming: `NightPattern`, `ACTIVE_NIGHT`, "morning", "night".

## 2. Labels that become invalid/misleading under varied schedules

- **`DELAYED_SLEEP_LIKE`** — "delayed" is a judgment vs a norm; a 03:00–10:00 sleeper isn't delayed.
- **`MORNING_RECOVERY_SLEEP` / `DAYTIME_RECOVERY_SLEEP`** — "recovery" implies a missed "real"
  night; for a primary afternoon sleeper this is simply wrong.
- **`ACTIVE_NIGHT`** — "night" assumes 22:00–06:00 is when sleep belongs; pathologizes shift-like routines.
- **"first morning interaction"** — assumes waking is in the morning.

Structural facts (longest quiet block, phone-down, awakenings, fragmentation) survive untouched.
The clock labels answer *"did sleep happen when it's supposed to?"* — the question we must NOT ask.

## 3. Schedule-agnostic model

Classify quiet blocks by **role in the structure** (rank + duration); timing is **display-only metadata**.

```
enum RestRole {
  PRIMARY_REST    // the LONGEST sleep-like block (or merged window) — whenever it occurs
  SECONDARY_REST  // any other sleep-like block (a nap), ranked by size
  BRIEF_QUIET     // 20–90 min (today's MICRO)
}

enum RestPattern {
  CONSOLIDATED   // one dominant rest period, <= 1 awakening
  FRAGMENTED     // multiple rest periods and/or several awakenings
  MINIMAL_REST   // no sleep-like block >= threshold anywhere (was ACTIVE_NIGHT)
  // optional later: POLYPHASIC
}
```

- **`PRIMARY_REST` is rank-based (the longest), not duration-gated.** A 3h longest rest still
  becomes primary; its duration feeds *confidence*, not the label. `MAIN`/`SHORT` collapse into
  `PRIMARY`/`SECONDARY` by rank. The ≥90 min "rest-like at all" threshold stays (behavioral).
- **`DELAYED`/`RECOVERY` verdicts are removed** — they were clock-relative.
- **Timing → neutral metadata** (`startClock`, descriptive `DayPart` = LATE_NIGHT/MORNING/
  AFTERNOON/EVENING). Descriptive, never evaluative; computed *after* classification and never
  feeding labels/verdict/confidence. (Computed in the UI layer, which has the timezone — the
  analyzer stays clock-free.)
- **Metrics renamed** to drop clock framing (logic unchanged): `firstMorningInteraction` →
  `firstUseAfterPrimaryRest`. `phoneDownTime`, `awakenings`, `preSleepPhoneTime` unchanged.
- **Confidence** schedule-agnostic: primary rest exists, substantial (≥4h → higher), low
  awakenings, has use-after, full coverage, not `WINDOW_OPENS_IN_QUIET`.
- **Window**: removing clock labels makes classification schedule-agnostic, but the window
  *placement* still frames "where to look." Treat the window as a pure data boundary; adaptive/
  rolling window and personal-baseline timing are Phase 4 (need multi-night storage).

## 4. Incremental migration (strangler; codebase stays green)

- **Phase 0 — done.** This document.
- **Phase 1 — ✅ DONE.** Structural model (`RestRole` per block, `RestPattern`, `primaryRest`,
  `secondaryRests`, `firstUseAfterPrimaryRest`) added as new result fields, computed clock-free
  alongside the existing model. Clock labels and old verdict intact; 34 tests green. A 2-line
  `[debug]` preview shows it in the app.
- **Phase 2 — ✅ DONE.** The UI (`MainActivity`) renders the structural model (`restPattern`,
  `primaryRest`, `firstUseAfterPrimaryRest`); storage (`NightRecord`) persists it. The clock
  labels/verdict still compute internally but are no longer shown.
- **Phase 3 — remove the old clock layer** (labels, anchors, DELAYED/RECOVERY verdicts); retire/
  rewrite obsolete tests; reduce `WindowAnchors` to bare bounds.
- **Phase 4 — later (needs storage):** adaptive/rolling window + personal-baseline ("later than
  *your* usual", from the user's own history).

Recommendation: migrate **in-place additively** (the structural core is already shared); the clean
seam is "structural core (keep) → labeling/verdict layer (swap)".

## 5. Backward compatibility with tests

- Phases 1–2: old fields/labels untouched → **all 25 tests keep passing**; new tests additive.
- Phase 3 is the only breaking point, and only for clock-specific assertions.

## 6. Tests that change (only at Phase 3)

`NightAnalyzerTest` (7): untouched. From `NightPatternAnalyzerTest` (18):
- **Rewrite (clock-specific):** `label_delayed_*`, `label_morningRecovery_*`, `label_daytimeRecovery_*`,
  `whiteNightThenMorningQuiet_isRecoverySleep`, `delayedPrimary_yieldsDelayedVerdict`.
- **Rename only:** `label_main_*`, `label_short_*`, `activeNight_*`→`minimalRest_*`,
  `multipleShortBlocks_*`→`multipleRestPeriods_*`, drop "Night"/"recovery" wording elsewhere.
- **Keep:** `label_micro_*`, `shortGlowDoesNotBreakQuietPeriod`, `meaningfulThresholdIsConfigurable`,
  `windowOpensInQuiet_*`, `consolidatedNight_highConfidence`, `preSleepPhoneTimeCountsOnlyLastTwoHours`,
  `emptyInput_*`.

## 7. Out of scope (unchanged)

No Base44, no backend/network, no storage, no new permissions, no services. No new features beyond
the analysis model.

## 8. Phase 1 implementation spec

See the prepared Phase 1 spec (additive structural model + tests; no UI change, no clock in the new
path). Summary: add `RestRole`, `RestPattern`, `RestPeriod`, and new fields on `NightPatternResult`
(`restPattern`, `restPeriods`, `primaryRest`, `secondaryRests`, `firstUseAfterPrimaryRest`); compute
them from the existing quiet blocks/awakenings; add Phase-1 tests; touch only `Models.kt`,
`NightPatternAnalyzer.kt`, `NightPatternAnalyzerTest.kt`.
