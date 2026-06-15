# SleepGuard Android POC

A minimal Android native (Kotlin) proof of concept for the **SleepGuard** project.

> 🧭 **Continuing this project (agent or developer)?** Read [`HANDOFF.md`](HANDOFF.md) first —
> it covers the objectives, current status, the decisions already made (and why), the data-retention
> constraint, the active feature freeze, and the next steps.

> **Goal of this milestone — one technical risk only:**
> Prove that we can read Android **screen on/off timing events** from the previous
> night, *passively*, *non-invasively*, and only after the user **explicitly grants
> Usage Access**. This is **not** the full SleepGuard app.

---

## 1. What this POC does

- Checks whether the user has granted **Usage Access**.
- Opens the system **Usage Access settings** screen so the user can grant it.
- **Collect Last Night** queries Android's `UsageStatsManager` (device timezone) for the full
  day — yesterday 18:00 → today 18:00 — and runs the `NightPatternAnalyzer` on the official
  22:00 → 18:00 analysis window, showing a flexible "pattern-around-sleep" report (see below).
  The wider capture window only feeds the "all interactions" log; the analyzer ignores events
  outside its anchors, so the sleep analysis is unaffected.
- Extracts only **screen/lock timing events** (timestamp + event type).
- Shows the Last Night report, a History list (saved nights + optional full interaction log),
  and a debug block (timezone, window, raw pre-filter count vs. filtered count, saved nights).

Collection is **retrospective and on-demand**: it happens only when the user presses **Collect
Last Night** (or **Backfill Available History**). There is no real-time monitoring.

### SleepGuard pattern analysis (`NightPatternAnalyzer`)

The SleepGuard Window button classifies the night into a flexible pattern that tolerates
PTSD-typical sleep (broken, late, inverted, white nights, morning crash):

- **Meaningful interaction** = the device was unlocked (`KEYGUARD_HIDDEN`) **or** the
  screen was on ≥ 60 s (configurable). Notification glows are ignored, so they don't
  falsely break a quiet period.
- **Quiet blocks** are labelled by duration + start time: `MAIN_SLEEP_LIKE_BLOCK`
  (≥4h), `DELAYED_SLEEP_LIKE` (≥3h starting 04:00–12:00), `SHORT_SLEEP_LIKE_BLOCK`
  (90 min–4h), `MORNING_RECOVERY_SLEEP` (≥90 min, 06:00–12:00),
  `DAYTIME_RECOVERY_SLEEP` (≥90 min, 12:00–18:00), `MICRO_QUIET_BLOCK` (20–90 min).
- It reports phone-down time, the longest sleep-like quiet period, first morning use,
  pre-sleep phone time (last 2h only), and possible nighttime awakenings — each with a
  confidence level, and `Unknown` when a value can't be observed.
- The report never says "you slept X" — only **"possible sleep-like quiet period."**
  Causes of awakenings (nightmares, etc.) are never inferred.

### Local storage (debug/POC)

Because Android keeps raw usage events for only a few days, the app can **save each night
on-device** so history accumulates over time:

- **Collect Last Night** auto-saves that night.
- **Backfill Available History** probes the last 10 nights still retained by the device,
  saves the ones that have data (skipping empty ones), and reports the earliest event available
  (the device's retention cutoff).
- Saved nights are always listed under **History**; **Show All Logs** expands it to every stored
  interaction, and **Clear Stored Data** wipes them.

Storage is a single JSON file in the app's **private internal storage** — no external files,
no sharing/export, no network. Records hold the per-night summary plus (for debugging only) the
filtered raw events as timestamps + event types. **Any future server/Base44 sync must use the
summaries only, never the raw events.**

## 2. What this POC does NOT do

- ❌ No backend, no login, no database, no cloud storage.
- ❌ No Base44 integration (that is the next milestone).
- ❌ No background service, no foreground service, no `WorkManager`.
- ❌ No **Accessibility Service** and no notification listener.
- ❌ No AI / insights / medical recommendations.
- ❌ No iOS, watch, heart-rate, sleep-stage, or nightmare detection.
- ❌ No network access at all — there is **no `INTERNET` permission**; the app is fully offline.
- ❌ No collection of private content (see **Privacy** below).

This is **not** a medical app and does not diagnose any condition.

## 3. How to run it in Android Studio

1. Open **Android Studio** (Koala / Ladybug or newer recommended).
2. **File → Open** and select the `SleepGuardAndroidPOC` folder.
3. Let Gradle sync. The project targets:
   - Gradle **8.9**, Android Gradle Plugin **8.7.3**, Kotlin **1.9.24**
   - `compileSdk 35`, `targetSdk 35`, **`minSdk 28`**
   - JDK **17** (bundled with recent Android Studio)
4. Connect a **real Android device** (recommended — emulators rarely have real
   overnight usage events) with USB debugging enabled, or start an emulator.
5. Press **Run ▶** to install and launch.

> **Note on the Gradle wrapper:** the wrapper *jar* is not included (binary file).
> If `gradlew`/`gradlew.bat` are missing or the wrapper jar is absent, Android Studio
> will generate it automatically on first sync. From a terminal you can also run
> `gradle wrapper --gradle-version 8.9` once (requires a local Gradle install).

### Run the unit tests (no device needed)

The core logic has JVM unit tests — `NightPatternAnalyzerTest` (quiet-block labelling, label
precedence, awakenings, white-night/active-night, recovery, `WINDOW_OPENS_IN_QUIET`, pre-sleep
window, structural model), `NightStorageTest` (mapper, JSON round-trip, upsert merge), and
`InteractionHistoryTest` (cross-night flatten/dedup, long-inactivity threshold):

```
./gradlew test
```

(Windows: `gradlew.bat test`)

## 4. How to grant Usage Access

Usage Access is a **special permission**. It is **not** a normal runtime permission and
cannot be requested with a dialog. The app declares `PACKAGE_USAGE_STATS`, and the user
must enable it manually:

1. In the app, tap **"Open Usage Access Settings"**.
2. Android opens **Settings → Apps → Special app access → Usage access**.
3. Find **SleepGuard Android POC** and toggle it **ON**.
4. Return to the app — the status updates to **Granted** automatically (re-checked in
   `onResume`).

## 5. Android APIs used

| API | Purpose |
| --- | --- |
| `UsageStatsManager.queryEvents(start, end)` | Read usage events for the night window |
| `UsageEvents` / `UsageEvents.Event` | Iterate events and read `eventType` / `timeStamp` |
| `AppOpsManager` (`OPSTR_GET_USAGE_STATS`) | Check whether Usage Access is granted |
| `Settings.ACTION_USAGE_ACCESS_SETTINGS` | Open the Usage Access settings screen |
| `java.time` (`ZoneId`, `LocalDate`, `Instant`) | Compute the night window in device timezone |

## 6. Event types collected

Used to pair raw screen-on sessions:

- `SCREEN_INTERACTIVE` — screen turned on for full interaction (API 28+)
- `SCREEN_NON_INTERACTIVE` — screen left the interactive state

Used to judge whether a session was *meaningful* (an unlock is the strongest signal):

- `KEYGUARD_HIDDEN` — device unlocked
- `KEYGUARD_SHOWN` — device locked

### How the analyzer pairs and filters sessions

- A raw session **starts** at `SCREEN_INTERACTIVE` and **ends** at the next
  `SCREEN_NON_INTERACTIVE`.
- A `SCREEN_NON_INTERACTIVE` with no open session is ignored.
- A second `SCREEN_INTERACTIVE` while one is open closes the previous session and starts a new one.
- A session still open at collection time is closed at that moment (never in the future).
- A session counts as **meaningful interaction** if the device was unlocked
  (`KEYGUARD_HIDDEN`) during it **or** the screen stayed on ≥ 60 s. Non-meaningful sessions
  (notification glows, micro-taps) are dropped so they don't falsely break a quiet period.

## 7. Privacy statement

> SleepGuard does not read your messages, notifications, app content, location,
> microphone, or camera. This POC only reads screen on/off timing events from Android
> Usage Access in order to estimate nighttime phone interaction.

The app stores/displays **only**: event timestamps, event types, calculated session
durations, and summary counts. It deliberately does **not** touch message/notification
content, app names, websites, keyboard input, touch coordinates, location, microphone,
camera, contacts, SMS, call logs, photos, files, or health data.

Saved history is kept **only in the app's private internal storage on the device** (a single
JSON file). It is never sent anywhere — there is no network access and no `INTERNET`
permission. Uninstalling the app removes it; **Clear Stored Data** wipes it on demand.

## 8. Known limitations

1. Android only — does not work on iOS.
2. Usage Access must be granted manually by the user.
3. Does not detect actual sleep stages.
4. Does not detect nightmares directly.
5. Screen activity is only a **proxy** for possible wakefulness.
6. Lack of screen activity does **not** prove the user was asleep.
7. Screen activity does **not** prove PTSD symptoms.
8. Results may vary across Android versions and manufacturers (some OEMs retain or
   report usage events differently).
9. The app collects **only** when the user presses **Collect Last Night** (or **Backfill**).
10. The analysis window is currently fixed at 22:00 → 18:00 (device timezone); the full day is
    captured but only this slice is analyzed.

## 9. Testing on a physical Android phone

Real devices are strongly preferred — emulators rarely contain genuine overnight
screen activity.

1. Enable **Developer options** and **USB debugging** on the phone, connect it, and run
   the app from Android Studio (**Run ▶**).
2. On first launch, tap **Open Usage Access Settings** and toggle **SleepGuard Android
   POC** on. Return to the app and confirm the status reads **Granted**.
3. To validate the real use case, leave the phone overnight, then in the morning tap
   **Collect Last Night**. Inspect the Last Night report and the History list.
4. Tap **Show All Logs** to expand the full interaction log, and confirm in it and the
   **Debug** block that only timestamps and the four screen/lock event types are shown —
   no app names, content, or other private data.

> Tip: if **Collect Last Night** shows no events, the device may have pruned older usage
> events — some OEMs (notably Samsung) do this aggressively. Lock/unlock the phone a few
> times and try again to confirm the API works.

## 10. Next milestone (not part of this POC)

```
Android POC
  ↓ analyze screen sessions
  ↓ create JSON payload
  ↓ POST to Base44 Backend Function
Base44 stores NightSession, ScreenEpisodes, NightSummary
Base44 UI shows Timeline + Morning Check-in + Insight
```

This sync step (and the `INTERNET` permission it requires) will be added **only after**
real-device validation of this POC succeeds — it is intentionally not present today.

---

## Project structure

```
SleepGuardAndroidPOC/
├─ settings.gradle
├─ build.gradle
├─ gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/sleepguard/poc/
│     │  │  ├─ MainActivity.kt
│     │  │  ├─ UsageAccessManager.kt
│     │  │  ├─ ScreenEventsCollector.kt
│     │  │  ├─ NightPatternAnalyzer.kt
│     │  │  ├─ NightRecord.kt          (storage DTOs)
│     │  │  ├─ NightRecordMapper.kt    (result -> record)
│     │  │  ├─ NightRepository.kt      (local JSON persistence)
│     │  │  ├─ InteractionHistory.kt   (cross-night log helpers)
│     │  │  └─ Models.kt
│     │  └─ res/ (layout, strings, theme, launcher icon)
│     └─ test/java/com/sleepguard/poc/
│        ├─ NightPatternAnalyzerTest.kt
│        ├─ NightStorageTest.kt
│        └─ InteractionHistoryTest.kt
└─ README.md
```
