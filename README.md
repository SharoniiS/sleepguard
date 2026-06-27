# SleepGuard

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-28-blue)
![On-device](https://img.shields.io/badge/data-on--device%20only-success)
![Status](https://img.shields.io/badge/status-in%20development-yellow)

**An honest, private night-activity tracker for people with PTSD-related sleep problems.**

SleepGuard passively reads when your phone's screen turned on and off during the night and turns
it into a clear, factual **night timeline** — *without ever claiming to know how you slept.* It is
built on one principle:

> **We measure device activity, not sleep.** The app reports what the phone did; it never infers
> what you felt, dreamed, or experienced. For a trauma-affected audience, false certainty is harmful —
> so the app deliberately avoids it.

All analysis happens **on the device**. Nothing is uploaded, there is no account, and there is no
server. The app has no `INTERNET` permission.

---

## Why it's different

- 🔒 **Private by design.** Your data never leaves your phone. No cloud, no login, no network access.
- 🧭 **Honest, not clinical.** No "you slept 7h", no sleep scores, no nightmare detection. It shows
  *possible quiet periods* and *phone activity*, and says **"Unknown"** when it genuinely doesn't know.
- 🌙 **Built for irregular sleep.** Late nights, fragmented sleep, daytime recovery and "white nights"
  are common with PTSD — the analysis is schedule-agnostic and doesn't assume a 23:00–07:00 night.
- 🪶 **Passive & non-invasive.** No background services, no accessibility APIs, no microphone, camera,
  location, contacts, or content. Only screen on/off and lock/unlock *timestamps*.

## Screenshots

> _Coming soon._ Add 2–3 screenshots to `docs/` and reference them here, e.g.:
>
> | Home | History | Night detail |
> |---|---|---|
> | ![Home](docs/home.png) | ![History](docs/history.png) | ![Detail](docs/detail.png) |

## How it works

```
Android UsageStatsManager  ──►  event filtering  ──►  NightPatternAnalyzer  ──►  local storage  ──►  UI
   (screen on/off, lock)        (timestamps only)     (quiet periods, etc.)      (on-device)
```

1. With the user's explicit **Usage Access** grant, the app reads screen/lock *timing* events for the
   night (device timezone), retrospectively and on demand — never in real time.
2. A pure-Kotlin analyzer derives the main quiet period, phone-down time, first morning use,
   pre-sleep phone use, and possible interruptions — each with a data-reliability level.
3. Results are stored locally so history accumulates (Android keeps raw usage events only ~9–10 days).
4. The UI presents the night honestly, in plain language.

The data-collection engine and analyzer are validated with a suite of unit tests.

## Tech stack

- **Kotlin**, single-Activity Android app
- `UsageStatsManager` (Usage Access) for passive screen-event timing
- Pure-JVM analysis core (fully unit-tested, no Android dependencies)
- Local persistence on-device with **Room** (SQLite)
- View / XML UI with ViewBinding (Jetpack Compose UI in progress)
- `compileSdk 35` · `targetSdk 35` · `minSdk 28` · JDK 17

> The app interface is in **Hebrew** (its initial audience); the codebase and docs are in English.

## Build & run

1. Open the project in **Android Studio** (let Gradle sync).
2. Connect a **real Android device** (emulators rarely have genuine overnight usage data) and press **Run ▶**.
3. In the app, grant **Usage Access** when prompted (Settings → Special app access → Usage access → SleepGuard).
4. Leave the phone overnight, then open the app in the morning to see the night analyzed.

Run the unit tests from Android Studio (right-click the `com.sleepguard.poc` test package → **Run**),
or `./gradlew test`.

## Project status & roadmap

SleepGuard began as a proof of concept and is being grown into a shippable, **native, on-device** app.

- [x] Read nighttime screen activity via Usage Access
- [x] Honest night-pattern analysis (schedule-agnostic) + unit tests
- [x] On-device local storage + history
- [x] Fully offline (Base44 cloud layer removed; on-device only)
- [x] Persistence on Room (SQLite), with one-time import from the legacy store
- [~] Native Compose UI (Home / Last Night / History / More info) — shell + data wired, screens in progress
- [ ] Google Play release

Developer/architecture notes live in [`HANDOFF.md`](HANDOFF.md).

## Disclaimer

SleepGuard is **not a medical device** and does not diagnose, treat, or monitor any medical
condition. Screen activity is only a *proxy* for possible wakefulness; the absence of activity does
not prove sleep. Always consult a qualified professional for medical advice.

## License

See [`LICENSE`](LICENSE). © 2026 Sharon Schwartz. All rights reserved.
