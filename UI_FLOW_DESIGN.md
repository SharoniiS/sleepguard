# UI flow & screen logic

The screen logic for the native SleepGuard app (on-device only). Design/visuals are intentionally
**out of scope here** — this is structure, data sources, states, and navigation. Based on the
original Base44 screens, re-grounded on the local Room data.

## Screens at a glance

Four bottom tabs (right→left, as in the app): **בית** (Home) · **לילה אחרון** (Last Night) ·
**היסטוריה** (History) · **מידע נוסף** (More info — renamed from "הגדרות", since there is no
profile/account; the tab is purely informational). Plus two **sub-screens** reached from within:
**דו"ח פעילות יומי** (Night Report) and **שאלון יומי** (Questionnaire).

```mermaid
flowchart TD
  Launch[App launch / resume] --> Perm{Usage Access granted?}
  Perm -- No --> Grant[Enable-access state]
  Perm -- Yes --> Collect[Auto-collect recent nights]
  Collect --> Has{Any saved nights?}
  Has -- No --> Empty[No-data guidance]
  Has -- Yes --> Home

  Home[בית / Home — latest summary] --> Report
  Last[לילה אחרון / Last Night] --> Report[דו\"ח פעילות יומי / Night Report]
  Hist[היסטוריה / History — list] -->|tap a night| Report
  Info[מידע נוסף / More info]
  Report -->|מילוי שאלון| Quest[שאלון יומי / Questionnaire]
```

## Two data layers (per night, joined by `nightOf`)

The original product = passive activity **+** a short morning self-report. That self-report (plus
manual time edits) is user input, not derived from screen events — so it lives in its **own** layer:

| Layer | Entity | Source | Mutable by |
| --- | --- | --- | --- |
| Analysis | `NightEntity` (exists) | screen events → analyzer | re-collection (recomputable) |
| User | `MorningReport` (**new**) | the questionnaire + "edit times" | the user |

`MorningReport` is a new Room table keyed by `nightOf` (parallel to `NightEntity`), joined for
display. Keeping it separate means re-collecting/overwriting a night's analysis never destroys the
user's self-report. Additive — does not touch the existing Room work.

```mermaid
erDiagram
  NightEntity ||--o| MorningReport : "nightOf"
  NightEntity { string nightOf PK }
  MorningReport {
    string nightOf PK
    bool nightmares
    string medications
    bool cannabis
    bool alcohol
    string note
    long correctedQuietStartMillis
    long correctedQuietEndMillis
    long updatedAt
  }
```

## Global states (every tab must handle)

1. **No Usage Access** → "enable access" prompt (the only thing shown until granted).
2. **Granted, no nights yet** → friendly "open after a night" guidance (never an empty dashboard).
3. **Latest night in progress** (collected mid-night, `collectedAt < windowEnd`) → show the last
   *complete* night + a small note. (Logic exists: `isCompleteNight`.)
4. **Has data** → normal.

---

## 1. בית — Home

At-a-glance summary of the **latest complete night**.

- **Source:** newest `NightEntity` with `collectedAt ≥ windowEnd` (add `getLatestComplete()` or pick
  from `getAllSummaries()`).
- **Content:** date (`nightOf`); quiet window `start–end`; duration; data-availability chip
  (`confidence`); pattern chip (`restPattern`); interruptions (`awakenings.size`); pre-sleep phone use
  (`preSleepPhoneTimeMillis`). Optional link → Last Night.
- **No greeting** (no account — decided).

## 2. לילה אחרון — Last Night → Night Report (latest)

The Last Night tab opens the shared **Night Report** for the latest night. Same screen as a History
night, different source. (Decided: one reusable report screen.)

## 3. היסטוריה — History

List of saved nights; tap → Night Report for that night.

- **Source:** `getAllSummaries()` (the projection we built — no heavy events loaded).
- **Row:** duration · window `start–end` · date (`nightOf`) · status dot · pattern chip · mini bar.
  - Status dot / chip ← `restPattern` (green = רצוף/CONSOLIDATED, amber = מקוטע/FRAGMENTED) and
    `confidence` (data availability). Newest first.
- **Tap** → `getByNight(nightOf)` → Night Report.

## 4. מידע נוסף — More info (was "הגדרות")

Informational (about / data source / privacy), as in the screenshot. Renamed because there is no
profile to configure.

- **About:** "SleepGuard · גרסה X", one-line description.
- **Data source:** "Android Native Component" — reads screen on/off timings only.
- **Privacy:** the on-device bullets (no identifying data, stays on device, screen content not read).
- **Open decision (native gap):** Base44's Settings was info-only because the web app *couldn't* do
  native things. The native app still needs a home for: **Usage Access status/grant**, **Export
  backup**, **Clear data**. Recommendation: Usage Access is handled **contextually** in the
  no-permission state (not a setting); Export/Clear are currently **debug/POC** actions and can stay
  out of the product UI for now (add a minimal "ניהול נתונים" section here later if wanted).

---

## Sub-screen A — דו"ח פעילות יומי (Night Report) — SHARED

Reached from Home, Last Night, and History. Source: `getByNight(nightOf)` (full record incl. events)
joined with the night's `MorningReport`.

| Section | Source |
| --- | --- |
| Hero: owl + "דו"ח פעילות יומי" + date + back | `nightOf` |
| Chips: data availability · pattern | `confidence`, `restPattern` |
| Summary sentence | templated from `restPattern` + quiet duration |
| **Timeline**: axis `windowStart → collectedAt`; quiet block(s); pre-quiet activity (estimated); "ערוך זמנים" | `mainRestEpisode`/`primaryRest`, events; footnote "הערכה גסה" |
| Card: פעילות אחרונה | quiet start = `phoneDown` |
| Card: פעילות ראשונה | quiet end = `firstUseAfter…` |
| Card: חזרות לפעילות | `awakenings.size` (+ times) |
| Card: סך זמן חוסר הפעילות | quiet duration |
| Card: שימוש שעתיים לפני | `preSleepPhoneTimeMillis` |
| יומן פעילות גולמי (collapsible) | `events` (lazy) |
| תובנת הינשוף | derived template (see wording note) |
| שאלון יומי card: status + "מילוי שאלון" | `MorningReport` presence → Questionnaire |

## Sub-screen B — שאלון יומי (Questionnaire)

Per-night self-report, opened from the report's questionnaire card.

- **Fields:** סיוטים (yes/no) · תרופות (none/text) · קנאביס (yes/no) · אלכוהול (yes/no) · הערה חופשית.
- **Actions:** שמור → upsert `MorningReport` for `nightOf`; "אפשר למלא גם אחר כך" → dismiss.
- **States:** empty (new) / pre-filled (editable).

---

## Open decisions (for the build phase)

1. **תובנת הינשוף wording.** "לילה רגוע במיוחד" is a soft judgment; per the UX-wording rules prefer a
   factual template (e.g. "לא זוהו הפרעות בשעות חוסר הפעילות"). A wording set will be proposed.
2. **"ערוך זמנים" scope.** It is a full editor (user override of the detected quiet window, stored in
   `MorningReport`). Ship in v1, or defer to a later phase?
3. **Native actions home.** Confirm the recommendation above (permission contextual; export/clear stay
   debug-only for now).

## Data-layer work this implies

- New `MorningReport` entity + DAO (`upsert`, `getByNight`, `getAll` for History "filled" badges) +
  Room schema bump (turn on `exportSchema` + migration before v2).
- `getLatestComplete()` query (or pick in code) for Home / Last Night.
- The rest (`getAll`, `getByNight`, `getAllSummaries`, `clearAll`) already exists.
