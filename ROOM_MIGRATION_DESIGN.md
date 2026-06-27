# Room migration — design & status

Persistence for SleepGuard moved from a single JSON file to **Room** (on-device SQLite). This is the
storage half of turning the POC into the product. On-device only: no network, no cloud, no account.

## Why Room (over the JSON file)

- Per-row **upsert / delete / query** instead of rewriting the whole file on every save.
- **Range queries** by date (needed for the upcoming Week screen).
- **Lazy loading** — list screens can read summaries without pulling each night's ~hundreds of raw events.
- **Flow** for reactive UI (planned for the Compose UI track).

## Approach — "JSON blob + indexed columns" (minimal, low-risk)

`NightRecord` stays the single source of truth and is **untouched** (still `@Serializable`, still used
by the analyzer mapper and the backup export). Each night is one Room row:

| Column | Purpose |
| --- | --- |
| `nightOf` (PK) | one row per night → clean upsert, no duplicates |
| `windowStartMillis`, `windowEndMillis`, `collectedAtMillis` | queryable / sortable scalars |
| `restPattern`, `confidence` | queryable scalars |
| `recordJson` | the **full `NightRecord` serialized** — same JSON shape as the legacy file |

So the heavy/nested data (blocks, events, config, awakenings, flags) lives inside `recordJson`; the
scalar columns are just a denormalized index. No `TypeConverter`s are needed.

### Components

- `NightEntity` + `NightSummary` (projection of the scalar columns, no blob).
- `NightDao` — `upsert` (REPLACE), `getAll`, `getByNight`, `getAllSummaries`, `clearAll`.
- `AppDatabase` (version 1, singleton).
- `NightEntityMapper` — pure `NightRecord <-> NightEntity` (record ↔ blob + columns); unit-tested.
- `NightRepository` — rewritten over the DAO; **same public API** (`loadAll` / `upsert` / `clearAll`)
  so `MainActivity` is untouched.

### One-time data migration

On first run, if `sleepguard_history.json` exists it is decoded (`List<NightRecord>`), upserted into
Room, and renamed to `sleepguard_history.json.imported` (kept as a safety copy). Failures leave the
file in place — never lose nights. This preserves real nights already collected on a device.

## Decisions (confirmed)

1. **Drop-in first.** Swap storage behind the `NightRepository` façade; keep the current screen
   working. Flow / Week range-queries come in the UI track.
2. **Raw events** = inside the JSON blob; list screens use the `NightSummary` projection to avoid
   loading them. (No separate events table for now.)
3. **Import the legacy JSON** so existing on-device data carries over.

## Lazy / temporary bits to revisit (UI track)

- `AppDatabase` uses `allowMainThreadQueries()` as a drop-in shim (the old store did sync main-thread
  I/O; data set is tiny). Replace with Flow / coroutines and remove it.
- Turn on `exportSchema` (+ `room.schemaLocation`) before bumping to schema **version 2**, so
  migrations are tracked and tested.

## Testing

- Pure (JVM) unit tests cover `NightRecordMapper`, `NightRecord` JSON round-trip, and the new
  `NightEntityMapper` round-trip + column extraction (in `NightStorageTest`).
- The Room DAO (upsert/replace, queries) needs Robolectric / instrumented tests — a follow-up; not
  part of the pure JVM suite.

## Status

✅ Implemented (2026-06-27): entity, DAO, database, mapper, repository rewrite, legacy import, Room
dependencies (KSP). Not built/run in this environment (no JDK/Gradle/SDK) — sync & run in Android
Studio. Next: the native UI track (Home / Week / History) on top of these queries.
