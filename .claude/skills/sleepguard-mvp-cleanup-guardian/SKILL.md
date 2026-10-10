---
name: sleepguard-mvp-cleanup-guardian
description: >-
  Guardrails for cleaning up and refactoring the SleepGuard Android POC. Use for any
  cleanup, dead-code removal, simplification, de-duplication, or "remove the cancelled
  layer" task in the SleepGuardAndroidPOC codebase. It keeps cleanup from turning into a
  redesign and protects the working MVP screen, analyzer, storage, and tests.
---

# SleepGuard MVP Cleanup Guardian

## Core principle

**Cleanup protects the working MVP. It does not redesign it.**

Your job is to make the codebase simpler, smaller, more readable, and more stable **while
preserving the current product behavior exactly**. If a change would alter what the screen
shows, how a night is analyzed, how data is stored, or which tests pass, it is out of scope —
stop and report it instead of doing it.

## What SleepGuard is (the boundaries cleanup must respect)

- **Android-only MVP.** Passive and **retrospective** — it analyzes the past on demand, never
  in real time.
- Reads phone activity via **Android Usage Access / `UsageStatsManager.queryEvents`**.
- **Local-first, non-invasive, observational** — not clinical, not diagnostic.
- Relevant events only: `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`,
  `KEYGUARD_HIDDEN`.
- **Meaningful interaction** (analyzer 1.1.0) = the screen was on **while the phone was unlocked**
  (an unlock during it, or still unlocked from before, ≤ 30 min). A lit but locked screen is never
  meaningful. Only when the input has no lock events at all: screen-on ≥ 60 s.
- Never tracks app content, messages, notifications, typing, location, microphone, camera, or
  any personal content. Only timestamps + event types + derived summary.

## The working screen (protect this output)

The current POC screen is valuable and the user is satisfied with it. Preserve its data:

- Usage Access permission status
- Collection status
- Last analyzed night
- Main quiet / sleep-like period
- Phone put-down time
- First use after the main quiet period
- Pre-sleep phone use in the last 2 hours
- Possible awakenings / interruptions
- Consolidated / fragmented pattern
- History of previous analyzed nights
- Raw logs / debug data (kept for hackathon-demo value)

## Data flow to keep obvious

```
UsageStats events → filtering → analysis → saved NightRecord → UI display
```

Keep the analyzer isolated and testable. Keep storage isolated and testable. Keep the UI thin —
it displays state, it does not contain analysis rules.

## Hard rules

1. Do not add features during cleanup.
2. Do not redesign the app.
3. Do not introduce new architecture (layers, managers, repositories, abstractions, patterns)
   unless absolutely necessary.
4. Do not change analyzer behavior unless clearly fixing a real bug.
5. Do not change thresholds unless clearly fixing a real bug.
6. Do not remove useful displayed data.
7. Do not break history, storage, or raw/debug logs.
8. Do not add background or foreground services.
9. Do not use Accessibility APIs.
10. Do not add invasive monitoring.
11. Do not claim tests passed unless they actually ran.
12. Prefer deleting unused code over wrapping it. Prefer direct, boring, readable Kotlin over
    clever patterns. Avoid "future architecture" not used by the current MVP.

## Product language constraints

Keep all user-facing copy calm, simple, and observational. Avoid medical conclusions, diagnosis
language, sleep-quality scores, and trauma interpretation. Prefer terms like **quiet period,
activity period, phone-down, first activity, analyzed period**. Do not introduce user-facing
**"rest"** language in new or edited copy. Do not rename concepts in a way that disconnects the
UI from the analyzer logic.

## Before changing any code — inspect first

Read the source (not just the docs) and identify:

- Dead files/classes from cancelled experiments or layers
- Duplicate models representing the same concept
- Multiple paths doing the same analysis
- Unused mappers / interfaces / DTOs / extension functions / imports
- Old terminology in code or UI
- Obsolete TODOs
- Debug code that should **stay** for demo value vs. code that is truly useless
- Storage serialization risks (see lesson F)
- Tests that protect current behavior

## Decision rule

- **Keep** code that is actively used by the working screen, the analyzer, storage, or the tests.
- **Delete** code that is unused, duplicated, or belongs only to a cancelled layer.
- **If unsure** whether something matters: preserve it and report the uncertainty. Do not guess.

---

## Lessons from past cleanups (read these — they prevent real mistakes)

These are the rules that would have prevented confusion or risky changes in earlier passes.
They matter more than the generic list above because they encode mistakes that were nearly made.

**A. Trust the code, not the docs.** The repo's docs (`HANDOFF.md`, `README.md`, design notes)
have drifted before — referencing source files that no longer exist, wrong test/button counts,
and describing an older architecture than what ships. Verify every doc claim against the actual
files before relying on it. The cancelled layer may already be partly removed in code while the
docs still describe it as present. After cleanup, update stale docs to match reality (the
HANDOFF's own rule: if docs conflict with code, the code wins).

**B. "Not shown on screen" ≠ "dead."** A model can be demoted to internal-only yet still be
load-bearing. Before deleting anything that looks superseded, trace whether it feeds: the
sleep-like / quiet-block detection, the awakenings, or a **stored** field. If it does, keep it;
removing it would silently change the screen or saved data and break tests. (Concrete recurring
example: the clock-based labeling model — no longer rendered, and since 2026-10-10 its Confidence
is not displayed either, but it still computes stored flags and the stored `confidence`. Confirm
the current state before acting.)

**C. A half-finished migration's "next phase" is not cleanup.** If you find a partially-migrated
design — e.g. two coexisting models where one drives the UI and the other runs internally — do
**not** "finish" the migration by ripping out the older layer. That changes behavior and rewrites
tests; it is a redesign, which is out of scope. Clean *within* the current state only.

**D. Confirm "unused" with a full-tree search (main + tests) before deleting.** A symbol used
only by tests is **not** dead — keep it; tests protect behavior. Delete only when there are zero
readers/callers anywhere in `src/main` and `src/test`.

**E. Remove the dead field, keep the live computation.** When deleting an unread data-class
field, check whether the local value that populated it is consumed elsewhere. Remove only the
field; keep the computation if anything still uses it (e.g. a value also used as an internal
reference or to set a flag/confidence).

**F. Serialized storage fields are an on-disk contract.** Do not remove a `@Serializable` field
just because nothing currently reads it — that changes the saved-file format and can break
existing saved nights. This needs a deliberate schema-version decision, not an incidental
cleanup. Preserve and flag instead. Likewise, do not casually change `STORAGE_SCHEMA_VERSION` or
the JSON shape.

**G. After editing a data class and its constructor call, re-read both and confirm the field
count matches the constructor-arg count.** A missing or extra argument will not compile — compile
(see H) to confirm; when compiling is not possible, this manual check is your safety net.

**H. Run the tests — but only claim what actually ran.** There is no committed Gradle wrapper, but
on the owner's machine the tests DO run (verified 2026-10-10) with Android Studio's bundled JDK and
the cached Gradle 8.13: set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` and run
`gradle :app:testDebugUnitTest :app:compileDebugKotlin --offline` with the binary under
`~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/` (`:app:lintDebug` and
`:app:assembleDebug` work too). If that is unavailable, verify by reading + full-tree grep +
arg-count matching, state plainly that tests were not run and why, and tell the user to run them in
Android Studio (right-click the `com.sleepguard.poc` test package → Run). **Never claim tests
passed** without a run. Do not trust documented test counts — count from the test results.

**I. Work only inside `SleepGuardAndroidPOC/`.** It sits among unrelated personal files and other
projects — ignore those entirely. Ignore generated output under `app/build/`; never edit it or
cite it as source.

---

## Cleanup behaviors (what you may do)

- Remove dead code from cancelled experiments / layers.
- Remove unused files, classes, interfaces, DTOs, mappers, extension functions, and imports.
- Remove duplicated logic and obsolete TODOs.
- Simplify UI code while keeping the same displayed information.
- Improve readability; move strings to a cleaner place if it genuinely helps.
- Lightly polish structure without changing behavior.
- Update stale docs/comments to match the current code.

## What you must not do

- Add features, redesign, or replace the current POC flow.
- Remove useful screen data or hide logs/debug data that help the demo.
- Change analyzer behavior or thresholds casually.
- Introduce a new architecture or continue a cancelled migration into a redesign.

## Testing

- Run all unit tests after cleanup.
- If the tests cannot be run, explain **exactly** why (see lesson H).
- Do not claim success without actual test results.
- If behavior changed, list every behavior change clearly and justify it.

## Final report format

End every cleanup task with:

1. What was deleted
2. What was simplified
3. Files changed
4. Tests run and results (or why they could not run)
5. Risks or assumptions (including anything preserved out of caution and why)
6. Behavior changes, if any (state "None" explicitly if nothing changed)
