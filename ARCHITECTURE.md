# SleepGuard — Architecture & decisions

The single, current-state reference for the app. (For the chronological build log see
[`HANDOFF.md`](HANDOFF.md) §0; for screen logic see [`UI_FLOW_DESIGN.md`](UI_FLOW_DESIGN.md); for the
storage migration see [`ROOM_MIGRATION_DESIGN.md`](ROOM_MIGRATION_DESIGN.md).)

## 1. What it is

A **native Android, fully on-device** app that passively reads when the phone's screen went on/off
during the night and turns it into an honest "night activity" report — plus an optional morning
self-report. No backend, no account, no network. The UI is **Hebrew (RTL)**; code/docs are English.
Ethic: *we measure device activity, not sleep; never fabricate certainty.*

## 2. Architecture (pipeline)

```
UsageStatsManager ─► ScreenEventsCollector ─► NightPatternAnalyzer ─► NightRecord ─► Room ─► SleepViewModel ─► Compose UI
 (screen on/off,        (timestamps only)        (pure JVM logic)       (DTO)       (on-device)    (state)         (4 tabs)
  lock/unlock)
```

The analyzer is pure JVM (no Android) and unit-tested. Storage, ViewModel, and UI are thin layers
around it.

## 3. Modules (files under `app/src/main/java/com/sleepguard/poc/`)

| File | Role |
| --- | --- |
| `AppActivity.kt` | **Launcher** — hosts the Compose UI; calls `vm.refresh()` on resume |
| `ui/SleepApp.kt` | Scaffold, 4-tab bottom nav, in-app back stack, Home / History / More-info, shared helpers |
| `ui/NightReport.kt` | Shared Night Report screen + Questionnaire sub-screen |
| `ui/Theme.kt` | Minimal dark Material3 theme, RTL (placeholder visuals) |
| `SleepViewModel.kt` | UI state (permission, nights, latestComplete, savedMedications) + collection + repos |
| `UsageAccessManager.kt` | Usage Access permission check + open settings |
| `ScreenEventsCollector.kt` | Read & filter screen/lock events for a window |
| `NightPatternAnalyzer.kt` | The analysis (quiet periods, awakenings, pattern, confidence) — pure |
| `Models.kt` | Analyzer models / config / enums |
| `NightRecord.kt` | Serializable storage DTO (one per night) |
| `NightRecordMapper.kt` | analyzer result → `NightRecord` (pure) |
| `NightRepository.kt` | Room-backed repo (`loadAll`/`upsert`/`clearAll`/`getLatestComplete`) + one-time JSON import |
| `NightEntity.kt` | Room row for a night (`NightSummary` projection too) |
| `NightDao.kt` / `NightEntityMapper.kt` | DAO + pure `NightRecord ⇄ NightEntity` mapper |
| `MorningReportEntity/Dao/Repository.kt` | Per-night user self-report (questionnaire) |
| `MedicationEntity/Dao.kt` | User's reusable medication names |
| `AppDatabase.kt` | Room DB (v3): tables `nights`, `morning_reports`, `medications` + migrations |
| `InteractionHistory.kt` | `longInactivities` helper (estimated-sleep blocks) |
| `MainActivity.kt` | **Legacy View POC, debug-only** (no longer the launcher) — holds the backup-export / clear / backfill dev tools, reached via `adb` |

## 4. Data model (Room, on-device)

Two layers per night, joined by `nightOf` (the morning's ISO date):

- **Analysis layer** — `nights` (`NightEntity`): the full `NightRecord` serialized to a `recordJson`
  column + denormalized summary columns (window/collected millis, restPattern, confidence) for
  queries. Recomputable from screen events.
- **User layer** — `morning_reports` (`MorningReportEntity`): nightmares / medications / cannabis /
  alcohol / note. User input; never overwritten by re-collection.
- **`medications`** (`MedicationEntity`): the user's saved medication names for the dropdown.

Room is at **version 3** with non-destructive migrations (1→2 added `morning_reports`, 2→3 added
`medications`). A one-time import moves any legacy `sleepguard_history.json` into Room.

## 5. Screens & navigation

Four bottom tabs (RTL): **בית** (Home, latest-night summary) · **לילה אחרון** (Last Night → the
shared Night Report) · **היסטוריה** (History list → tap a night → the same Night Report) · **מידע
נוסף** (info-only). The Night Report has a collapsible **raw activity log** (transparency) and a
**questionnaire** card → the Questionnaire sub-screen. Navigation is an in-app `Dest` back stack
(`TabDest`/`Report`/`Quest`) with `BackHandler`: the device Back button moves between screens and only
exits at the Home root. Full detail in [`UI_FLOW_DESIGN.md`](UI_FLOW_DESIGN.md).

## 6. Tech stack & build

- **Kotlin 1.9.24**, single module. `compileSdk/targetSdk 35`, `minSdk 28`, JDK 17.
- **Jetpack Compose** (BOM 2024.06.00, compiler ext 1.5.14, Material3) for the product UI; the legacy
  POC screen is View/XML + ViewBinding.
- **Room 2.6.1** via **KSP** for persistence.
- `applicationId = com.sleepguard.app` (permanent), app name **SleepGuard**, `allowBackup=false`.
  Release signing reads a gitignored `keystore.properties` (see `keystore.properties.example`).
- Public repo: https://github.com/SharoniiS/sleepguard. License: proprietary (all rights reserved).

## 7. Decision log

1. **Pivot to the POC-as-product (native, on-device only, no backend); Base44 dropped.** The logic was
   already native; the web shell added only sync/publish/RLS friction. On-device makes privacy a
   selling point. (Base44 code, the REST bridge, `INTERNET`, Google login, and the Base44 docs were
   removed; the JSON export was repurposed into a local backup.)
2. **Persistence = Room** (JSON-blob + indexed columns), with a one-time import from the legacy file.
   For per-row upsert/query, lazy list projections, and Flow-readiness.
3. **Two per-night layers** (analysis `NightEntity` + user `MorningReport`), joined by `nightOf`, so
   re-collecting a night never destroys the user's self-report.
4. **UI = Jetpack Compose**, RTL. Modern, fits a richer redesign; the product was being rebuilt anyway.
5. **Tabs = Home / Last Night / History / מידע נוסף** (renamed from "Settings" — info only, since there
   is no account). Usage Access is handled contextually (no-permission state); export/clear stay
   **debug-only** in the legacy `MainActivity`.
6. **Factual wording** for the insight/summary (no soft judgments). ⚠️ Per an explicit request the
   report summary says "חלון השינה שנצפה" — this softens the earlier strict "no 'sleep' claims" rule;
   revisit if the observational principle should win.
7. **"ערוך זמנים" (manual quiet-window edit) deferred** to a later phase; the v1 timeline is read-only
   (the `MorningReport.correctedQuiet*` fields are not built yet).
8. **Raw activity log kept as a transparency feature** (full unfiltered events with Hebrew labels) —
   it gives the user control/trust and lets them interpret the night themselves.
9. **Device Back = in-app navigation** (a `Dest` back stack + `BackHandler`), never an abrupt exit
   from an inner screen.
10. **Medications dropdown** = "ללא תרופות" + presets (ריטלין/בנזודיאזפינים/נוגדי דיכאון) + the user's
    saved meds + "הוסף תרופה…" (saved to the `medications` table). Stable `DropdownMenu` (not the
    experimental ExposedDropdown API).
11. **Store-readiness:** permanent `applicationId`, app name, `allowBackup=false`, keystore-based
    release signing — chosen pre-publish because `applicationId` can't change after the first release.

## 8. Known temporary / follow-ups

- **Collection logic is duplicated** in `MainActivity` (debug) and `SleepViewModel` — intentional
  during the View→Compose move; collapses to one copy when `MainActivity` is retired.
- `allowMainThreadQueries()` (tiny data set) — move reads to Flow/coroutines in a later pass.
- `exportSchema=false` — turn on (+ `room.schemaLocation`) before the next schema change.
- DAO/migration tests need Robolectric/instrumented (the pure JVM suite has **43** tests).
- Visual design is a **placeholder** dark theme; a real design pass (and the owl artwork) is pending.
- Internal Kotlin package is still `com.sleepguard.poc` (not user-facing); optional rename later.

## 9. Related docs

[`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md) (visual spec from the Base44 reference) ·
[`HANDOFF.md`](HANDOFF.md) (build log + constraints) · [`UI_FLOW_DESIGN.md`](UI_FLOW_DESIGN.md) ·
[`ROOM_MIGRATION_DESIGN.md`](ROOM_MIGRATION_DESIGN.md) ·
[`SCHEDULE_AGNOSTIC_DESIGN.md`](SCHEDULE_AGNOSTIC_DESIGN.md) ·
[`NIGHT_DETECTION_LOGIC_SPEC.md`](NIGHT_DETECTION_LOGIC_SPEC.md) · [`README.md`](README.md)
