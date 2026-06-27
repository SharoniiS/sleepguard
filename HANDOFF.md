# SleepGuard Android POC — Agent / Developer Handoff

> Purpose of this file: give the **next agent or developer** everything needed to
> continue this project without re-deriving context. Read this first, then `README.md`
> (user/run guide) and the source. If anything here conflicts with the code, the code
> wins — update this file.

Last updated: 2026-06-27.

---

## 0. Strategic direction — POST-HACKATHON (2026-06-27) ⭐ READ FIRST

> This section is newer than the rest of the file and **supersedes** the older Base44 /
> "next milestone" notes below (§2 scope, §8 "Base44 sync"). Where it conflicts with
> §1–§9, this section wins until those are rewritten.

**Context:** the hackathon happened and went well — people liked the app. Based on that, the
user made a strategic decision about the product's future.

**DECISION — the POC becomes THE product; fully native, on-device only, no backend.**

- **The POC is no longer a "POC."** It is being promoted into the actual shipping app. There
  is no separate "real app" to build toward — this codebase is it.
- **Drop Base44 entirely.** Base44 was only the web UX/UI layer (judged generic/weak) sitting
  on top of logic that already lives natively here. The whole cloud layer is removed:
  - ❌ Base44 web app
  - ❌ the Android→Base44 REST bridge / sync / `api_key` / publish-to-go-live
  - ❌ RLS, `userId`-by-email, ProtectedRoute, and the seed-data demo plumbing
  - ❌ **Google Login is now unnecessary** — with no accounts and no cloud there is nothing to
    authenticate against. Open the app → see your data. Zero onboarding friction.
- **No backend at all.** Cloud was considered (Firebase was the natural fit since Google Login
  was already in place) and **deliberately rejected**. The product is **on-device only**.
- **Persistence = Room.** Room (local SQLite) is the chosen DB for the product, replacing the
  current single-JSON-file persistence (`sleepguard_history.json` via kotlinx.serialization,
  §5 "Local storage"). Data never leaves the phone.
- **Trade-off accepted consciously:** on-device only = no cloud backup, no multi-device, no
  family sharing; a user who switches phones starts empty. This is fine for now and turns
  **privacy into a product selling point** ("your data never leaves your device"), which aligns
  with the existing hard privacy constraints in §5. If backup is ever wanted, Room can export to
  a JSON/file without reintroducing a backend.

**What this leaves to build:** essentially one thing — the **native UI**. The collection
(`UsageStatsManager`) and the logic (`NightPatternAnalyzer`, backfill) are already done. The
work is building the user-facing app UI well — the intended shape is **3 tabs: Home / Week /
History** (Compose, or staying View-based as the current POC is — not yet decided). Note: a
native UX shell was attempted once before and reverted, so treat the UI as the real cost of
this plan, not an afterthought.

**Marketing asset to preserve (do NOT delete, do NOT maintain):** the loginless Base44 web
demo with seed data was the hackathon booth hero. A native-only app cannot reproduce a
"click a link → see a working dashboard" experience, which is valuable for pitching. **Freeze
it as-is** and keep it around as a demo/marketing artifact; just don't reopen or develop it.

**Status:** direction set; the Base44 *removal* is DONE (see execution log). Next concrete step is
the **Room** layer (night entity / DAO / migration off the JSON file) and then the UI.

### Execution log

**2026-06-27 — Base44 fully removed from the app (Track 3).** The app is now on-device only with no
network upload path. What changed:
- **Deleted** `Base44Payload.kt`, `Base44PayloadMapper.kt`, `Base44Sync.kt`,
  `Base44PayloadMapperTest.kt` (the entire Base44 wire/sync layer; the live `API_KEY`/`APP_ID` lived
  only in the deleted `Base44Sync.kt` — not in any doc — so no secret remains in the tree. ⚠️ still
  rotate that key in the Base44 dashboard as a precaution).
- **MainActivity.kt:** removed `syncToBase44()`, the whole Base44-email identity block + companion
  object (`enteredBase44Email`/`load`/`save`/`syncedNights`/`markNightsSynced`/`clearSyncedNights`,
  `PREFS_BASE44`/`EXPORT_USER_ID`/…), the email-prefill, and the sync button wiring.
- **Export repurposed (decision: keep as on-device backup):** the old "Export SleepSummary JSON"
  (which mapped to the Base44 `SleepSummaryPayload`) is now `exportBackupJson()` — it serializes the
  on-device `NightRecord` list verbatim (same local-storage format, the future Room shape) to
  `sleepguard_backup.json` and shares it via the existing `FileProvider`. No cloud, user-initiated.
- **UI:** removed the hero's "Primary action 2" (Base44 email + sync button); hero now holds only the
  Usage Access action. Strings `button_sync`/`hero_email_label`/`error_no_base44_email`/
  `label_base44_email`/`hint_base44_email` deleted; `hero_tagline`, `privacy_message`,
  `status_success`, `button_export_json` rewritten to on-device wording.
- **Manifest:** removed the `INTERNET` permission (FileProvider kept for the backup export).
- **Docs:** README §10 + storage section + this file updated; the standalone `BASE44_*.md` files
  (`BASE44_INTEGRATION_STATUS/BRIDGE_PLAN/SETUP_GUIDE`) were **deleted** (obsolete after the pivot).
- **Tests:** 45 `@Test` (NightPatternAnalyzer 36 + NightStorage 5 + InteractionHistory 4) — **all 45
  passed** in Android Studio (user-confirmed 2026-06-27). No analyzer / storage / threshold /
  displayed-data behavior changed.

**2026-06-27 — Store-readiness build prep (Track 4, partial).**
- `app_name` → **"SleepGuard"** (was "SleepGuard Android POC").
- `applicationId` → **`com.sleepguard.app`** (permanent Play identity; chosen pre-publish). The
  internal `namespace` stays `com.sleepguard.poc` (not user-facing; rename is a separate optional pass).
- `allowBackup` → **false** (consistent with on-device-only; no auto Drive backup).
- Release **signing scaffold**: `app/build.gradle` reads a gitignored `keystore.properties` (template
  = `keystore.properties.example`); absent → release stays unsigned, debug/tests unaffected.
  `keystore.properties`/`*.jks`/`*.keystore` added to `.gitignore`.
- **Still TODO (need user / Android Studio):** create the upload keystore; let Android Studio generate
  the Gradle wrapper jar; build the **AAB** (`./gradlew bundleRelease` or Build → Generate Signed
  Bundle); bump `versionCode`/`versionName` per release. `minifyEnabled` left false (optional later).

**2026-06-27 — Room persistence (Track 5, Phase 1: drop-in).** Storage moved from the single JSON
file to **Room** (on-device SQLite). Full design in [`ROOM_MIGRATION_DESIGN.md`](ROOM_MIGRATION_DESIGN.md).
- Approach: **JSON blob + indexed columns** — each night is one row keyed by `nightOf`; the full
  `NightRecord` is serialized into a `recordJson` column (same shape as the legacy file) and the
  queryable scalars (window/collected millis, restPattern, confidence) are denormalized columns. No
  TypeConverters. `NightRecord` is **untouched** (still the domain/export model).
- New files: `NightEntity` (+ `NightSummary` projection), `NightDao`, `AppDatabase` (v1), `NightEntityMapper`.
- `NightRepository` rewritten over the DAO; **public API unchanged** (`loadAll`/`upsert`/`clearAll`),
  so `MainActivity` is untouched. The old pure `upsertInto` list-merge is gone (Room PK REPLACE does it).
- **One-time import:** on first run `sleepguard_history.json` → Room, then renamed `.imported` (kept as
  backup; failures leave it in place). Preserves nights already on a device.
- Deps: `androidx.room:room-runtime/room-ktx/room-compiler:2.6.1` via the **KSP** plugin
  (`com.google.devtools.ksp:1.9.24-1.0.20`, added to root + app gradle).
- Tests: `NightStorageTest` keeps the 3 NightRecord/mapper tests and **replaces** the 2 list-upsert
  tests with `NightEntityMapper` round-trip + column tests → still **45 `@Test`** total. DAO behavior
  (upsert/replace, queries) needs Robolectric/instrumented tests (follow-up).
- Temporary shims to revisit in the UI track: `allowMainThreadQueries()` (matches old sync I/O;
  replace with Flow/coroutines) and `exportSchema=false` (enable before schema v2).
- NOT built/run here (no JDK/Gradle/SDK) — sync & run in Android Studio.

**2026-06-27 — Dead-code sweep.** Removed `InteractionHistory.flatten` (+ its 2 tests
`flatten_dedupsBoundaryEventsAndSorts`, `flatten_emptyIsEmpty`) — dead since the global-log UI was
dropped; only its own tests referenced it (verified by full-tree grep). Test total **45 → 43**
(InteractionHistory 4 → 2). No production behavior changed. **Reported but intentionally NOT removed**
(out of cleanup scope, would change analyzer result-shape + rewrite tests; flag for a deliberate
decision): `NightPatternResult.longestQuietBlock` (write/test-only, now redundant with
`primaryRest?.block`), and the internal clock-based label layer (`QuietBlockLabel`/`NightPattern` —
still feeds awakening detection + the displayed confidence; this is the SCHEDULE_AGNOSTIC Phase-3
decision, NOT cleanup).

**2026-06-27 — UI design + data layer for the product UI.** Full screen logic/flow in
[`UI_FLOW_DESIGN.md`](UI_FLOW_DESIGN.md): 4 tabs (Home / Last Night / History / **מידע נוסף**, renamed
from Settings — info-only) + shared **Night Report** + **Questionnaire** sub-screens. Key model
addition: a per-night **user layer** alongside the analysis layer. Built the data layer for it:
- `MorningReportEntity` + `MorningReportDao` + `MorningReportRepository` (self-report keyed by
  `nightOf`: nightmares / medications / cannabis / alcohol / note / updatedAt). Room → **v2** with
  explicit `MIGRATION_1_2` (creates `morning_reports`; non-destructive, preserves existing nights).
- `NightDao.getLatestComplete()` + `NightRepository.getLatestComplete()` for Home / Last Night.
- Locked decisions: insight wording = factual; "ערוך זמנים" deferred (timeline read-only in v1,
  `correctedQuiet*` fields not built); "מידע נוסף" info-only (permission contextual; export/clear stay
  debug). Raw activity log kept as a deliberate transparency feature.
- NOT built/run here — sync & run in Android Studio. DAO/migration need Robolectric/instrumented tests.
- Next: the UI screens themselves (decision pending: Compose vs the current View-based approach).

**2026-06-27 — Compose UI, Increment 1 (shell).** Decision: **Compose** for the product UI. Added the
Compose toolchain (BOM 2024.06.00, compiler ext 1.5.14, material3, activity-compose,
lifecycle-viewmodel-compose; `buildFeatures.compose`). New: `AppActivity` (the **launcher** now),
`SleepViewModel` (ports the collect logic; exposes hasPermission / nights / latestComplete), and
`ui/Theme.kt` + `ui/SleepApp.kt` (RTL, dark placeholder palette). Scaffold with the 4-tab bottom nav
+ global state gating (no-permission / no-data). **Real screens:** Home (from `getLatestComplete`),
History (list of `nights`), מידע נוסף (static info). **Placeholder:** לילה אחרון (awaits the Night
Report screen — Increment 2). The View POC `MainActivity` is KEPT as a debug screen (lost its launcher
filter; reachable via `adb am start … MainActivity`) — MVP not destroyed. Collection logic is
temporarily duplicated (MainActivity + SleepViewModel) until MainActivity is retired. NOT built here —
sync Gradle (downloads Compose) + run in Android Studio; report any compile errors to iterate.
- Next (Increment 2): the shared **Night Report** screen (timeline, cards, raw-log, owl insight) for
  לילה אחרון + History detail; then the **Questionnaire** sub-screen wired to `MorningReportRepository`.

**2026-06-27 — Compose UI, Increment 2 (Night Report + Questionnaire).** New `ui/NightReport.kt`:
- `NightReport` (shared screen): chips, factual summary, read-only **timeline bar**, stat cards
  (first/last activity, interruptions, total quiet, pre-sleep use), collapsible **raw activity log**
  (lazy `items`, Hebrew event labels — the transparency feature), **owl insight** (factual template),
  and a **questionnaire card**.
- `NightReportRoute` owns the report↔questionnaire sub-nav; used by the **לילה אחרון** tab
  (`onBack = null`) and from a **History** row tap (`onBack` pops to the list). History rows are now
  clickable; `SleepApp` navigation reworked (a `reportNight` state for full-screen history detail).
- `QuestionnaireScreen`: nightmares / meds / cannabis / alcohol / note → `MorningReportEntity` saved
  via `vm.saveReport`; pre-fills from the existing report.
- Insight wording is **factual** (per the locked decision); `summaryHe`/`insightHe` templates.
- Shared helpers/composables in `SleepApp.kt` made `internal` for reuse (`fmt`, `dur`, `quiet`,
  `patternHe`, `availabilityHe`, `Ltr`, `Chip`, `StatCard`).
- NOT built here — rebuild in Android Studio + report compile errors to iterate. Next: visual design
  pass (still placeholder theme), and History "filled-questionnaire" badges if wanted.

**2026-06-27 — Compose Increment 3 (questionnaire polish + in-app back nav).**
- **In-app back stack:** `SleepApp` now holds a `Dest` stack (`TabDest`/`Report`/`Quest`) with a
  `BackHandler`; the device Back button moves between screens (questionnaire→report→list→home) and
  only exits at the Home root. Bottom nav always visible; the active tab is the last `TabDest` in the
  stack. Removed `NightReportRoute` (nav is now top-level); `NightReport`/`QuestionnaireScreen` made
  `internal`.
- **Questionnaire:** removed the "אפשר למלא גם אחר כך" button (saving + Back cover it).
- **Medications dropdown:** `ExposedDropdownMenuBox` with "ללא תרופות", presets
  (ריטלין/בנזודיאזפינים/נוגדי דיכאון), the user's **saved** meds (under a "תרופות שמורות" header), and
  "הוסף תרופה…" → dialog that saves a new name. New Room table **`medications`** (Room → **v3**,
  `MIGRATION_2_3`) + `MedicationEntity`/`MedicationDao`; `SleepViewModel.savedMedications` +
  `addMedication`.
- NOT built here — rebuild + report compile errors.

**2026-06-27 — Visual redesign, Stage 1 (foundation).** Implementing the Base44-quality design in
Compose (see [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md)); staged. Stage 1:
- **Theme**: real palette (`Color.kt`) + **Rubik/Assistant** bundled fonts (`res/font`, `Type.kt`) →
  `Theme.kt` now uses the navy `darkColorScheme` + `SgTypography` + body font via `LocalTextStyle`
  (RTL kept). This upgrades every screen's colors + type at once.
- **Owl assets** downloaded to `res/drawable` (`owl_full`, `owl_circle`, `owl_banner`).
- **`HeroBanner`** component (owl banner art + dynamic Hebrew title over a scrim); wired into Home
  (with `dateWithDay` subtitle) and History; removed the placeholder `Hero`. Home big numbers use Rubik.
- Next stages: report hero + back overlay, glass cards / gradients (`sg-card`/`sg-card-raised`), the
  `OwlInsight` gradient card, richer timeline, and per-screen polish.

**2026-06-27 — Build fix + cleanup + docs.** Fixed the build: the medications picker used the
experimental `ExposedDropdownMenu` (unresolved in Material3 BOM 2024.06) → replaced with a stable
`OutlinedButton` + `DropdownMenu` (same behavior, no `@OptIn`). Removed unused imports
(`getValue`/`setValue`/`mutableStateOf` in `SleepApp.kt`). Added **[`ARCHITECTURE.md`](ARCHITECTURE.md)**
— the consolidated current-state reference (module map, data model, screens/nav, tech stack, and a
**decision log**). This §0 stays the chronological history; ARCHITECTURE.md is the clean overview.

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
  ViewInsets.kt            Reusable View.applySystemBarInsetsPadding() (edge-to-edge safe area)
app/src/main/res/layout/
  activity_main.xml        Main screen
  item_night.xml           One per-night card (header + always-visible summary + raw-events expand)
app/src/test/java/com/sleepguard/poc/
  NightPatternAnalyzerTest.kt  27 tests (labels, precedence, awakenings, verdicts, future-window cap, structural model, ...)
  NightStorageTest.kt          5 tests (mapper, JSON round-trip, upsert merge)
  InteractionHistoryTest.kt    4 tests (flatten/dedup, long-inactivity threshold)
```

**Buttons / sections** (all retrospective, on-demand — collection happens only on press):
1. **Collect Last Night** — captures the full day (yesterday 18:00 → today 18:00) and runs
   `NightPatternAnalyzer` on the 22:00 → 18:00 analysis window. Auto-saves the night. The
   capture window is wider than the analysis window only so the per-night raw log misses
   nothing; the analyzer ignores events outside its anchors.
2. **Show night details** — reveals the per-night card list (see §4a).
3. **Debug information** — reveals the collapsible Debug section (per-night summary lines +
   technical counts + **Backfill Available History** + **Clear Stored Data**), collapsed by default.

The screen shows: permission status, collection status, **Last Night** (the structural report,
unchanged technical wording), **compact History** ("estimated sleep" inactivity blocks, always
visible), the **per-night cards** (behind "Show night details"), and the collapsible **Debug**
section.

### 4a. UI / logs / history organization (current)

The technical logs were reorganized away from one giant global event dump:

- **System-bar insets:** `ViewInsets.kt` adds `View.applySystemBarInsetsPadding()` — reads the
  dynamic system-bar + display-cutout insets and pads the scroll content (baseline captured once,
  so repeated dispatches never accumulate). Called on `contentRoot` from `MainActivity`. Fixes
  edge-to-edge overlap on targetSdk 35 without disabling edge-to-edge.
- **Debug is separate/collapsible:** a "Debug information" toggle (`debugSection`, gone by default).
- **No more global raw dump:** the old "Show All Logs" (which merged every night via
  `InteractionHistory.flatten` → thousands of events) was replaced.
- **Per-night cards** (`item_night.xml`, built in `renderNightList`): the last 10 saved nights,
  newest first. Each card:
  - **Header** = date + estimated quiet-period duration only, e.g. `2026-06-15 · 7h 4m estimated`.
  - **Friendly summary, always visible** (`formatNightCardBody`): main quiet/sleep-like period,
    phone put down, first use after, pre-sleep 2h, possible interruptions, *Activity pattern*
    (steady / interrupted / mostly active), *Data reliability* (high / medium / low). Observational
    wording only — no clinical/sleep-quality claims. (The top **Last Night** card deliberately keeps
    the original technical wording via `formatNightSummary`.)
  - **Raw events per night** behind **"Show raw events for this night"**, hidden by default and
    built **lazily** only when opened (`formatRawEvents`), from that night's stored
    `NightRecord.events` only — never merged across nights.
- **`InteractionHistory.flatten` was REMOVED** (2026-06-27) along with its two unit tests — it was
  dead after the global-log UI was dropped (only its own tests referenced it). `longInactivities` (the
  live "estimated sleep" helper) and its tests stay.
- **No analyzer / storage-schema / UsageStats change** in any of this — presentation only.

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
