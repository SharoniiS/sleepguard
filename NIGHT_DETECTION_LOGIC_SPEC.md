# SleepGuard — Night Detection Logic (Response-Evidence Model)

> Status: **DESIGN / FROZEN SPEC. Not yet implemented.**
> This freezes the agreed logic for telling a *real wake* apart from a *passive screen
> event*. It supersedes the implicit "first meaningful use after the quiet block = wake"
> behavior that produced the two bugs in §0.
>
> **Respects the current feature freeze.** This is a spec to lock the design, not a green
> light to code. Staging in §12: the Gate-1/Gate-2 corrections to the existing wake-anchor
> path are **bug-fix level** (the two bugs are real misreports) and may proceed as bug fixes;
> the scoring model (§6) and per-user personalization (§10) are **post-freeze** and need an
> explicit go-ahead, exactly like Phase 3 in `SCHEDULE_AGNOSTIC_DESIGN.md`.
>
> Companion docs: `HANDOFF.md` (§5 "Interpretation principles" is the spine this builds on),
> `SCHEDULE_AGNOSTIC_DESIGN.md` (the structural `RestRole`/`RestPattern` model this plugs into).

Last updated: 2026-06-20.

---

## 0. The two bugs this fixes

Both are the same root error: **treating a lit screen as "the user woke up."**

1. **Isolated night blip ends sleep.** Night of 2026-06-20: a single `Screen On` at 02:13
   (no `Unlock`, no return activity for 7h44m) was taken as the end of the primary rest block.
   Reported **2h 52m** of sleep instead of ~10.5h. Real story: one passive blip inside a long
   consolidated sleep.
2. **A ringing alarm reads as activity.** An alarm that rang/snoozed to its natural end (no
   `Unlock`, no response) was counted as user activity. Paradox to internalize: **an alarm that
   "rang endlessly" is evidence of *sleep*, not wake** — the user did not respond to it.

---

## 1. Core principle (the frozen invariant)

> **There is no "wake" from a passive `Screen On`.**
> A wake exists only when there is **response evidence** or **engagement**, and then only
> if **quiet does not meaningfully return** afterward.

Everything below is the precise machinery for that one sentence. The principle is fixed; the
thresholds and weights are tunable/personalizable (§10).

---

## 2. Vocabulary (maps to the POC's four event types)

| Spec term | POC `UsageEvents` type | Display name |
|---|---|---|
| Screen On | `SCREEN_INTERACTIVE` | `Screen On` |
| Screen Off | `SCREEN_NON_INTERACTIVE` | `Screen Off` |
| Unlock | `KEYGUARD_HIDDEN` | `Unlocked` |
| Lock | `KEYGUARD_SHOWN` | `Locked` |

`Unlock` (`KEYGUARD_HIDDEN`) remains the **strongest consciousness signal** (HANDOFF §5). The
change here: `Unlock` is no longer the *only* gate — it is the strongest member of a family of
**response-evidence** signals (§3, Gate 1). A bare `Screen On` is, by default, noise.

**Trigger** = a forced event that lit the screen without the user choosing to enter (alarm,
incoming call, notification). **Engagement** = the user actually entering the device. These can
differ; the gap between them is itself a reported signal (§8).

---

## 3. The three safety gates (FROZEN)

Gates run as hard rules. They define what is **impossible**; the score (§6) only chooses between
interpretations the gates have already allowed. A scoring error can be wrong by *degree*; the
gates guarantee it is never wrong by *category* (never "2h52 instead of 10h").

### Gate 1 — Response Evidence

A passive `Screen On` cannot end sleep. A non-`Unlock` trigger can become a **wake candidate**
only if there is evidence of user response. Response evidence may include:

- **Unlock + sustained foreground use** (`KEYGUARD_HIDDEN` then continued activity).
- **Responsive dismissal** — the trigger appears interrupted *before its natural completion or
  before its expected repeat cycle continues* (a hand stopped it). See §4 for the precondition.
- **Answered-call-like behavior** — a long locked-screen `Screen On` session consistent with an
  active call.
- **Repeated active reactions** — multiple non-random interactions suggesting the user handled
  the device.
- **Wake-like texture afterward** — scattered or continuing activity after the trigger, *not* a
  clean return to quiet.

> **FROZEN CONDITION (the one the user emphasized):**
> Responsive dismissal can be inferred **only** if there is an established pattern —
> **at least 2 repeated cycles, or a known reference width** for the trigger type.
> Without a pattern or reference width, **a single isolated `Screen On` event cannot be treated
> as responsive dismissal.** Default safe classification: `passive_blip` or `forced_interruption`,
> **never `wake`.**

### Gate 2 — Continuity

Even with response evidence, the event ends sleep only if **quiet does not meaningfully return**
afterward.

- Quiet returns after the event → classify as **interruption**; count it if relevant; **do not**
  end the main rest window.
- Quiet does **not** return, and the following period shows wake-like texture / engagement →
  classify as **final wake candidate**.

### Gate 3 — No sleep over sustained intentional usage

A window containing sustained intentional phone use cannot be labeled sleep. If a user `overlay`
(§9) claims sleep during clear usage, **do not silently accept it** — surface a conflict and ask
for correction. Protects against inflated sleep (e.g. a declared nap that contradicts the data).

---

## 4. Pattern detection — responsive dismissal vs timeout (NEW mechanism)

The POC today only has duration thresholds. This adds **pattern awareness over screen-event
bursts**, which is what makes Gate 1's "responsive dismissal" inferable without knowing the app.

We do **not** get the trigger's identity (privacy: only timestamp + event type). We infer the
expected shape from the burst itself:

- **Alarm-like** — periodicity: `Screen On`/`Off` at a roughly fixed cadence (≈9 min snooze), or
  one continuous `Screen On` of a fixed timeout length.
- **Call-like** — a `Screen On` of a ring-width band (~30–45s) then `Off` (voicemail) = ran its
  course; cut to a few seconds = declined; extended well beyond = answered.
- **Notification-like** — a single brief flash, no repeat. Almost never response evidence.

**Responsive dismissal** is inferred when a trigger appears interrupted **before its natural
completion or before its expected repeat cycle continues**.

**Timeout** is inferred when the trigger **runs its expected course, repeats in a regular
pattern, or stops without any evidence of user response.**

Two properties that keep this robust and safe:

- **Self-calibrating, no global threshold.** The "expected repeat" is learned from the burst's
  own early cycles *in the same night*. Snooze every ~9 min → a 4th cycle that never comes = a
  hand stopped it before cycle 4.
- **Bounded blast radius.** A *mis-read* "responsive dismissal" can, at worst, create a false
  **interruption** — Gate 2 (continuity) still requires no-quiet-after for a final wake. It can
  **never** create a false terminal wake that truncates sleep.

---

## 5. Pipeline

```
RawEvents
  → Sessionization          (merge nearby raw events into sessions — REQUIRED so repeated
                             Screen On/Off can be seen as alarm/snooze patterns, not N blips)
  → Pattern detection       (§4: alarm-like / call-like / notification-like; responsive vs timeout)
  → Response Evidence Gate  (§3 Gate 1 — pattern precondition enforced)
  → Continuity Gate         (§3 Gate 2 — interruption vs final wake)
  → Wake Candidate Scoring  (§6 — only among gate-approved candidates)
  → AlgorithmEstimate       (rest window + interruptions + reliability + trigger/engagement)
  → UserOverlay reconciliation (§9)
```

Maps onto existing code: sessionization/pattern detection are **new** in front of (or inside) the
current `buildRealUseInteractions` filter; the Gates replace the implicit "first REAL_USE after
the quiet block = `firstUseAfterPrimaryRest`" wake-anchor logic; the structural
`RestRole`/`RestPattern` output (`SCHEDULE_AGNOSTIC_DESIGN.md`) is unchanged downstream.

---

## 6. Wake-candidate scoring (POST-FREEZE)

Scoring runs **only after the gates** have admitted an event as a valid candidate. The gates say
what is impossible; the score chooses between valid interpretations — chiefly **trigger vs
engagement** in the dead zone between them.

**Signed score `S`:** positive = likely *slept through* the trigger (wake = engagement);
negative = likely *woke at* the trigger.

| Signal | Initial weight |
|---|---|
| Timeout / repeated snooze without response | **+3** |
| Clean quiet dead-zone after trigger | +2 |
| Trigger far from personal wake window | +2 |
| Fresh sustained morning engagement | +1 |
| Responsive dismissal / early interruption of pattern | **−3** |
| Trigger inside personal wake window | −2 |
| Wake-like texture in dead zone | −2 |
| Answered-call-like locked-screen duration | −2 |
| Short trigger-to-engagement gap | −1 |

**Initial threshold:**
- `|S| >= 3` → automatic decision.
- `|S| < 3` → low confidence → emit an **interval**, optionally ask one focused question (§8/§9).

Worked: 2026-06-20 alarm = timeout (+3) + clean dead zone (+2) + fresh morning engagement (+1) =
**+6 → slept through, wake = engagement (09:57).** Even if `S` were wrong, Gate 1 already blocks
the catastrophe.

---

## 7. Classification taxonomy

| Class | Meaning | Ends sleep? |
|---|---|---|
| `passive_blip` | Screen lit, no response evidence, quiet returns | No |
| `forced_interruption` | Trigger ran its course (timeout/snooze), no response, quiet returns | No |
| `real_interruption` | Response evidence (e.g. answered call, responsive dismissal), quiet returns | No (counts as awakening) |
| `responsive_trigger` | Trigger cut short + wake-like texture, no return to quiet | Yes (wake ≈ trigger) |
| `slept_through_trigger` | Timeout/no response, then later engagement | Wake = engagement |
| `final_wake` | Engagement, sustained, no meaningful quiet after | Yes |

This sits orthogonally on two axes: **response evidence** (was the user awake at this moment at
all?) × **continuity** (did they stay awake?). `Unlock` is the strongest response-evidence
signal, not a separate axis.

---

## 8. Output (honest, never a false-precise single point)

`AlgorithmEstimate` emits:

- **Rest window** (onset → wake), feeding the existing `PRIMARY_REST` / `RestPattern`.
- **Wake as a point *or* an interval** `[trigger, engagement]` when they differ and `|S| < 3`,
  plus a best estimate and the deciding signal.
- **Interruptions** (count + duration), each tagged with its §7 class.
- **Intended-vs-actual wake delta** when a timeout trigger precedes engagement ("alarm fired
  07:00, first engagement 09:57") — a real finding for this population, not hidden.
- **Reliability** (HIGH/MEDIUM/LOW), the existing confidence field, driven by `|S|`, data
  coverage, and phone-presence evidence. Honors "data availability, not confidence" wording.

---

## 9. UserOverlay reconciliation

- User corrections are stored **separately** as an overlay. **RawEvents and derived sessions are
  never modified.**
- `FinalSleepWindow = UserOverlay if present, otherwise AlgorithmEstimate.`
- Naps and "I was awake off-phone" are overlay declarations — they create rest/wake the phone
  cannot see. Conflicts with clear data are **surfaced, not silently accepted** (Gate 3).
- The app produces a default estimate automatically and **does not ask the user to resolve every
  ambiguous event** — it asks only when `|S| < 3` and the gap matters.
- Over time, user corrections **personalize the scoring weights** (§6). Asking frequency decays
  as confidence in the per-user pattern grows.

---

## 10. Fixed vs personalized — the critical line

- **The three gates (§3) and the pattern precondition (§4) are FIXED for everyone.** They are
  safety/logic invariants. Personalization must never erode them.
- **Only the scoring weights (§6) personalize.** A user is learned as a *trigger-waker* (gets up
  with the alarm) or an *engagement-waker* (snoozes through). A one-time prior ("where do you
  charge your phone at night?") enters as a *weight*, not a gate.
- **Fallback when the user never answers:** population defaults + the unambiguous gate decisions
  only; keep the interval wide and reliability low. Never block on feedback.

---

## 11. Worked examples

1. **Night blip.** 02:13 `Screen On`, no `Unlock`, no pattern, quiet returns. → `passive_blip`.
   Does not end sleep. *(fixes bug 0.1)*
2. **Alarm rang without response.** 07:00 alarm-like `Screen On`/`Off`, no `Unlock`,
   timeout/snooze pattern, quiet returns until 09:57. → `slept_through_trigger`; wake = 09:57.
   *(fixes bug 0.2)*
3. **Alarm dismissed responsively.** 07:00 alarm-like pattern starts, abruptly interrupted before
   the expected repeat, followed by wake-like texture. → `responsive_trigger`; wake ≈ 07:00.
4. **Answered call during the night.** 03:00 long locked-screen session consistent with an active
   call, then quiet returns. → `real_interruption`; count as interruption, do **not** end sleep.
5. **User correction.** Overlay stored separately; raw events untouched;
   `FinalSleepWindow = overlay`; corrections personalize weights; gates never personalize.

---

## 12. Relationship to the current analyzer + staging

**What already exists and stays:** `KEYGUARD_HIDDEN` as the strongest signal; the `REAL_USE`
filter concept; quiet-block detection; `awakenings` as sandwiched interruptions; the
schedule-agnostic `RestRole`/`RestPattern` output; `confidence`/UNKNOWN honesty; the future-window
cap; privacy (timestamps + event types only).

**What this spec changes/adds:**
- Splits `REAL_USE` so that a long `Screen On` is **not** automatically engagement (a ringing
  alarm or a stuck/unclosed `Screen On` can exceed 60s) — engagement now requires `Unlock` **or**
  pattern-confirmed response evidence (§3/§4).
- Replaces "first meaningful use after the primary rest = wake" with the **two-gate** wake anchor.
- Adds **sessionization + pattern detection** (§4/§5) — the genuinely new mechanism.

### 12.1 Decision record (read-only audit of `NightPatternAnalyzer.kt`, 2026-06-20)

Both bugs originate in **one place** — `buildRealUseInteractions` (`NightPatternAnalyzer.kt`
lines 174–225, specifically session-building 187–204 and the `REAL_USE` decision 216–217). The
wake/sleep-end anchors (`mainEnd` 49–64, `firstMorning` 89–91, `firstUseAfterPrimaryRest` 146–148)
are **derived and correct** given correct `REAL_USE`; they need no direct change.

Audit of `NightPatternAnalyzerTest.kt`: nearly every test uses the `use()` helper, which always
includes `KEYGUARD_HIDDEN` (an unlocked, `CLOSED_OFF` session). This makes the blast radii precise.

#### 1. `1a` — APPROVED as a bug-fix under the freeze

Fixes bug **0.1** (the fake multi-hour session from an unclosed `Screen On`). Scope:
- A `MISSED_OFF` / `OPEN_AT_END` session **without `Unlock` cannot become `REAL_USE`** (no
  duration path for unclosed sessions — their on-duration is unknown).
- **Do not stretch** an open `Screen On` to the next `Screen On` or to `effectiveEnd`. Unknown
  duration → represent as a point, not a span.
- **`wasUnlocked` must be bounded to a trustworthy range** — for `MISSED_OFF`/`OPEN_AT_END`, a
  short window from the session start — so a far `Unlock` (e.g. the 09:57 morning unlock) is **not
  swallowed** by a fake session that began at 02:13.
- Add the regression tests in §12.2.

**Test impact: ZERO.** Every existing test closes its sessions with `SCREEN_NON_INTERACTIVE`
(`CLOSED_OFF`), whose path is unchanged; no existing test constructs a `MISSED_OFF`/`OPEN_AT_END`
session. `1a` is a narrow, safe fix.

#### 2. `1b` / Option A — NOT approved right now

Removing the `duration >= minOnMillis` path for non-unlocked sessions is a **broad policy change,
not a narrow bug-fix.** Audit finding: it breaks **exactly one** existing test
(`meaningfulThresholdIsConfigurable`), and that test only *documents the path being removed* — the
suite has **no coverage** of the legitimate non-unlock scenarios, so it cannot vouch for A's safety.
Option A is especially dangerous for:
- **Users with no lock screen / smart-lock** — nothing would ever be `REAL_USE`, so entire days
  collapse into "sleep". Catastrophic and invisible to the tests.
- **Answered calls under lock** — a genuine interruption would vanish.
- **Lock-screen usage** (reading ≥60s on the lock screen).

**Do not apply `1b` under the freeze.**

#### 3. Bug `0.2` — intentionally left OPEN

A long, **properly closed** `Screen On` with no `Unlock` (e.g. an alarm that rang/snoozed to its
natural end) can still be counted as `REAL_USE` by the current `duration >= minOnMillis` path. `1a`
does **not** touch this. The future fix must be **category splitting, not a binary deletion of the
duration rule** (see §12.4). 0.2 stays open by decision until that post-freeze work is approved.

#### 4. Future direction (post-freeze, needs explicit go-ahead)

- **Split the single `REAL_USE` notion into three categories:**
  - `REAL_USE` — `Unlock` / intentional foreground use.
  - `LOCKED_SCREEN_ACTIVITY` — long screen-on with no `Unlock`; **must not auto-end sleep.**
  - `FORCED_INTERRUPTION` — possible alarm / call / notification; counted only cautiously.
- This preserves no-lock / smart-lock users (their activity is still seen) while a passive long
  screen-on no longer truncates sleep — the right resolution for 0.2.
- **Pattern detection (§4), the scoring model (§6), the trigger/engagement interval and
  intended-vs-actual delta (§8), and the overlay + personalization (§9/§10)** all remain post-freeze
  and require explicit approval. They are features, not bug fixes.

#### 12.2 Tests for `1a` (to add)

1. `unclosedScreenOn_doesNotEndSleep` — `use(~22:10)`, then a bare `Screen On` at 02:13 (no
   `Screen Off`), then `Screen On`+`KEYGUARD_HIDDEN`+`Screen Off` at 09:57. Assert: one `MAIN`
   block ~22:10→09:57; `firstUseAfterPrimaryRest ≈ 09:57`; `nighttimeAwakenings` empty; the 02:13
   `Screen On` is **not** in `interactions`.
2. `openScreenOnAtCollection_isNotMultiHourUse` — a `Screen On` near `now` with no `Screen Off`
   and no `Unlock`, `now < windowEnd`. Assert: no multi-hour interaction; quiet not broken there.
3. `farUnlockNotSwallowedByUnclosedScreenOn` — `Screen On(t)` with no `Off`; a `KEYGUARD_HIDDEN`
   much later (belonging to a later session). Assert: the `Screen On` at `t` is **not** `REAL_USE`.
4. `quickUnlockAfterMissedOff_stillCounts` (regression) — `Screen On(t)` + `KEYGUARD_HIDDEN(t+1s)`
   with no `Off` → still `REAL_USE` (within the bounded unlock-association window). Guards the
   bounded-range choice so a genuine quick unlock is not lost.

Verify-only (unchanged): `shortGlowDoesNotBreakQuietPeriod`, `meaningfulThresholdIsConfigurable`
stay green under `1a` (both are `CLOSED_OFF`).

> **Status line: `1a` is IMPLEMENTED (freeze lifted for it on 2026-06-20).** Patch applied to
> `buildRealUseInteractions` (`SessionClose` tracking + bounded unlock association); the four §12.2
> tests added; full suite **40/40 green**; debug APK rebuilt. `1b` and the `0.2` fix still wait for a
> post-freeze policy decision — do not mix them: `1a` corrects a clear artifact; `1b` redefines
> "real use" system-wide. (Code changes are in the working tree, not yet committed.)

**Out of scope (unchanged):** no Base44/backend/network, no new permissions or services, no app
identity, no content. Detection logic only.

---

## Pipeline placement of `0.2` and `0.3` — independent components

The two live at **different, non-overlapping pipeline stages** and share no state. Each can be built,
tested, and shipped on its own.

| Stage | Owner | The one question it answers | In → Out |
|---|---|---|---|
| Sessionization | (existing) | group raw events into sessions | raw events → sessions |
| **Event classification** | **`0.2`** | does THIS screen-on break sleep? | one session → `REAL_USE` / `LOCKED_SCREEN_ACTIVITY` |
| Quiet blocks | (existing) | quiet between sleep-breakers | `REAL_USE` set → quiet blocks |
| **Timeline bridging → headline** | **`0.3`** | which breakers are interruptions vs the terminal wake? | sleep blocks → bridged run + measurements |

`0.2` is **event-level** (one session at a time). `0.3` is **timeline-level** (the sequence of
blocks). `0.3` operates on whatever sleep-breaking set exists → it does **not** require `0.2`; `0.2`
reclassifies events regardless of bridging → it does **not** require `0.3`. No shared variable, no
ordering dependency.

---

## 13. Bug `0.2` design — only `Unlock` breaks sleep (DESIGN / DEFERRED, not implemented)

Resolves the principle: **`duration >= minOnMillis` without `Unlock` is a *significant screen
event*, but not necessarily `REAL_USE` and not necessarily end-of-sleep. Don't delete the
information — classify it differently.**

> **Component boundary.** Stage: **event classification only** (inside `buildRealUseInteractions`).
> **Input:** one raw screen-on session + the window's unlock set. **Output:** a class
> (`REAL_USE` / `LOCKED_SCREEN_ACTIVITY`). It does **not** touch quiet blocks, bridging, the
> headline, or `0.3`. Independently buildable/testable/shippable.

### 13.1 The rule

- **`Unlock` (`KEYGUARD_HIDDEN`) is the sole sleep-breaking signal.**
- A `CLOSED_OFF` screen-on with **no `Unlock`** and `duration >= minOnMillis` →
  **`LOCKED_SCREEN_ACTIVITY`**: a significant screen event that is **preserved** but **does not
  break a quiet period / does not end sleep**.
- `FORCED_INTERRUPTION` (alarm/call/notification subdivision of `LOCKED_SCREEN_ACTIVITY`) stays an
  **empty stub** until pattern detection exists. Not now.

### 13.2 The reliability gate (the only safety net — no UI, no prior, no personalization)

Removing the second condition globally is **Option A**, which silently erases activity for users
whose `Unlock` is not reliable. Gate it on one window-level check:

```
windowHasUnlock = keyguardHidden.isNotEmpty()   // any unlock in the analysis window?

CLOSED_OFF, no unlock, duration >= minOnMillis:
    windowHasUnlock  -> LOCKED_SCREEN_ACTIVITY   (preserved; NOT a quiet-breaker)   ← 0.2 fixed
    else             -> REAL_USE                 (current fallback; activity stays visible)
```

- **`windowHasUnlock == true`** (the modern-times default: almost every night has unlocks) → trust
  `Unlock` → apply the fix.
- **Real activity but ZERO unlocks** → untrusted (no-lock / **Smart-Lock-at-home** / dropped
  `KEYGUARD_HIDDEN`) → keep the fallback so a real day is not collapsed into "sleep".

This is `UnlockSignalReliability` collapsed to its cheapest form: one boolean per window. The
catastrophic failure (false "you slept" at HIGH confidence — against the PTSD ethic in `HANDOFF` §1)
only happens for zero-unlock windows, which this check catches for free.

### 13.3 Why not a ring-duration cap

A duration cap cannot isolate an alarm: **call/notification rings are short (~1–45 s, already
dropped by the 60 s floor); an alarm holds the screen for *minutes*, which overlaps genuine
lock-screen use.** No single second-count separates "alarm ringing" from "reading on the lock
screen". `Unlock` (absent for both, present for neither) is the clean discriminator. Duration/pattern
return later only to **sub-classify** `LOCKED_SCREEN_ACTIVITY` (ring vs check vs sustained) =
`FORCED_INTERRUPTION` / pattern detection.

### 13.4 What `LOCKED_SCREEN_ACTIVITY` buys us (why preserve, not delete)

`Unlock`-only is clean for *"what breaks sleep"* but lossy for *"what was active"*. It would erase
genuine locked activity: answered calls, lock-screen media/notification/camera/voice — and, most
important for this population, **repeated lock-screen checking without unlocking (night-time
hyper-vigilance)**. Preserving these as `LOCKED_SCREEN_ACTIVITY` lets us later surface "you checked
the phone N times without unlocking" **without** claiming you woke — a real clinical signal for PTSD.
This is why the split beats Option A's deletion.

### 13.5 Scope / tests

- **Test impact:** exactly one existing test changes — `meaningfulThresholdIsConfigurable` (its
  no-unlock glow becomes `LOCKED_SCREEN_ACTIVITY` in a reliable window); reframe it to assert the
  classification + that the quiet is not broken.
- **New tests:** `reliableUnlock_lockedActivityDoesNotCutSleep`;
  `zeroUnlockWindow_durationFallbackKept` (no-lock safety); `unlockedUse_stillBreaksQuiet`.
- **Out (still):** pattern detection, scoring, overlay, personalization, `KEYGUARD_SHOWN` as a
  response signal (document only), ring-duration cap (rejected, §13.3).

---

## 14. Bug `0.3` design — bridge brief awakenings; the *sleep-label* is a tunable layer

Observed 2026-06-21: a 34-second `Unlock` at 05:36 (followed by ~2h08m of quiet) was treated as the
morning wake. Reported 6h24m ending 05:36 and "Fragmented", instead of ~8.5h (23:12→07:45) with one
brief awakening. Root cause: the headline (`primaryRest` / `restPattern` / `firstUseAfterPrimaryRest`)
uses the **longest single quiet block**, not the **bridged main sleep run** that `mainEnd` /
`awakenings` already computes.

> **Component boundary.** Stage: **timeline bridging only** (block level, after quiet blocks are
> built). **Input:** the ordered sleep blocks + their sleep-breaking events, *however that set was
> defined*. **Output:** the bridged main run + per-interruption measurements. It does **not**
> classify individual screen-ons and does **not** read `0.2`'s categories — it sees only "a
> sleep-breaking event" and the quiet around it. Independently buildable/testable/shippable, even
> before `0.2`.

### 14.1 Continuity Gate (the bridge)

For each **sleep-breaking interruption** (whatever the upstream stage admitted as one), look
**forward** (retrospective — decidable only after the fact):

- **Meaningful quiet returns after it** → it is an **interruption** (bridge it; sleep continues).
- **Sustained activity continues (no quiet returns)** → it is the **terminal wake**.

**Fix:** route the headline (`primaryRest`, `restPattern`, `firstUseAfterPrimaryRest`) through the
**bridged run** (`mainEnd`), not the longest single block. The bridging mechanism already exists; it
just isn't what the structural model reports. Also: `sleepLike.size >= 2 -> FRAGMENTED` must not fire
when the extra block is merely one sleep split by a single bridged interruption (that is
`CONSOLIDATED`, ≤1 awakening). And confidence must not read HIGH on an un-bridged (under-reported)
number.

### 14.2 The analyzer computes MEASUREMENTS; the LABEL is a separate tunable policy

This is the seam we want open, so "what we call *return-to-sleep* vs *awakening*" can be decided
later **without touching detection logic.** Per `Unlock`-interruption the analyzer emits raw,
policy-free measurements:

- `activeDurationMillis` — how long the user was active (the interruption span between the two sleep
  blocks).
- `returnedQuietMillis` — how long the quiet/sleep that resumed afterward lasted.
- `timeOfNight` — clock position (display metadata only, never a classifier input — per
  `SCHEDULE_AGNOSTIC_DESIGN`).

### 14.3 Sleep-label policy (DEFERRED — boundaries are a product/clinical decision, TBD)

A thin, tunable layer maps the measurements to a label. **Parameters named now; values decided
later.** Strawman starting points in brackets — all explicitly open:

| Condition (on an `Unlock`-interruption) | Label |
|---|---|
| `returnedQuiet < MIN_RETURN_TO_SLEEP` [~90 min?] | **not a return** → this `Unlock` is the terminal wake |
| returns, `activeDuration <= MICRO_CHECK_MAX` [~2 min?] | **brief check / micro-arousal** (glanced, back to sleep) |
| returns, `activeDuration <= AWAKENING_MAX` [~30 min, current `awakeningMaxMinutes`] | **awakening** (counted) |
| returns, `activeDuration > AWAKENING_MAX` | **DEFERRED choice:** "long awakening" (bridge + flag) *or* split into two sleep periods |

Design rules for this layer:
- The boundaries (`MIN_RETURN_TO_SLEEP`, `MICRO_CHECK_MAX`, `AWAKENING_MAX`) are **product/clinical
  knobs**, not detection constants — live in config, re-tunable without re-deriving anything.
- A **micro-check** may be reported separately from a full **awakening** (clinically, a glance ≠ a
  20-minute wake), but **both bridge** (neither ends sleep) as long as quiet returned.
- **Downstream note (a separate component, NOT `0.3`):** a later *night-disturbance / quality* view
  composes **both** outputs at display time — `0.3`'s `Unlock`-interruptions **and** `0.2`'s
  `LOCKED_SCREEN_ACTIVITY` (no-unlock checks, hyper-vigilance). Only `Unlock`-interruptions move the
  sleep-window boundary. This composition lives in the display layer; `0.3` itself never reads
  `LOCKED_SCREEN_ACTIVITY`.

### 14.4 Tests

- `briefCheckBridges_consolidatedNotFragmented` — the 06-21 shape: 23:12→07:45 one run, 1 awakening,
  `CONSOLIDATED`, `firstUseAfterPrimaryRest ≈ 07:45` (not 05:36).
- `sustainedMorningIsTerminalWake` — an `Unlock` followed by dense activity ends sleep.
- `interruptionMeasurementsExposed` — `activeDuration` / `returnedQuiet` available per interruption,
  independent of the label policy (guards the seam).
- Label-boundary tests are added **with** the label policy, once its values are decided.

### 14.5 Scope

`0.2` and `0.3` are **design only**, post-freeze, pending an explicit go-ahead (like `1b`). Out:
pattern detection, scoring, overlay, personalization, `KEYGUARD_SHOWN` as response signal. The
same-second event-ordering sanity check (Tier-2) is still a separate later item.

### 14.6 Read-only audit of `0.3` (2026-06-21, no patch)

#### Central finding

**The bridged main sleep run is already computed in `NightPatternAnalyzer` — and then thrown away.**
The headline and History instead report the **single longest quiet block**, not the **bridged main
rest episode**.

- `mainEnd` (`NightPatternAnalyzer.kt:46–64`) expands a run from `primary` across gaps ≤
  `awakeningMaxMinutes` (30 min) and yields `mainEnd` + `awakenings`. But `mainEnd` is a **local
  variable** — not a field on `NightPatternResult`; the run's start (`sleepLike[lo].start`) is never
  captured. For 06-21: `mainEnd = 07:45`, `awakenings = [05:36]` — **the bridge already worked**.
- `firstMorning` (`:89–91`) = first use after `mainEnd` (= 07:45, the correct wake) is **also
  computed and discarded** (used only for flags/confidence). Meanwhile
  `firstUseAfterPrimaryRest` (`:146–148`) = first use after the *primary block* (= 05:36, the brief
  check) is what gets **stored and shown**.

So `0.3` is a **pure reporting bug**: the right values exist; the headline/History read the wrong
ones. No new threshold is needed for the core fix (the existing 30-min `awakeningMaxMinutes` already
bridged this night).

#### Isolation from `0.2`

`0.3` does **not** touch event classification or `buildRealUseInteractions`. The bridged run is
derived from **quiet blocks** (already downstream of `REAL_USE`). Re-aggregating blocks and reporting
from the bridged episode is **downstream only** — independent of `0.2` and of `UnlockSignalReliability`.

#### Touch points (all downstream of classification)

| Area | File / location | Change |
|---|---|---|
| Expose bridged episode | `NightPatternAnalyzer` (`:46–64`, result fields) | capture `[sleepLike[lo].start, mainEnd]` + its awakenings as a result field; logic already exists |
| `restPattern` | `NightPatternAnalyzer.kt:141–145` | do **not** mark `FRAGMENTED` merely because `sleepLike.size >= 2` when the blocks are one episode joined by a short bridged interruption; base it on bridged-episode count / awakenings |
| `firstUseAfter` | `:89–91` vs `:146–148` | headline/storage should use first use after the **bridged episode** (`firstMorning`), not after `primaryRest` |
| Storage | `NightRecord.kt`, `NightRecordMapper.kt:24–31` | add a field for the bridged episode → bump `STORAGE_SCHEMA_VERSION` |
| History (estimated sleep) | `InteractionHistory.longInactivities` (`:21–24`) | use the bridged episode instead of `primaryRest` + `secondaryRests` blocks |
| UI | `MainActivity` `formatNightSummary:291/294/286`, `formatNightCardBody`, `renderEstimatedSleep:257–272`, `renderHistory:391–401` | read the new field |

#### Design decision (fixed here)

**Do not change the meaning of `primaryRest`.** It stays the **single longest quiet block** (it is
part of the rank-based structural model in `SCHEDULE_AGNOSTIC_DESIGN`). Instead **add a new field**
for the bridged run — working name `mainRestEpisode` / `bridgedSleepEpisode` / `displayedSleepEpisode`
— and point the headline + History at it. `primaryRest` remains available as a finer-grained detail.

#### Migration question (documented, NOT decided, NOT implemented)

History renders **stored** `NightRecord`s, so old nights will not reflect the fix unless recomputed.
Decide later between:
- **Forward-only:** the fix applies to newly collected/re-collected nights only; old records keep
  their pre-fix headline.
- **Re-analyze history:** recompute stored nights from `NightRecord.events` (kept on every record),
  so the History list corrects retroactively.

Do **not** implement migration now. (Re-analysis is *possible* because raw events are retained, but
it interacts with `analyzerVersion` / `schemaVersion` provenance — a separate decision.)

#### Future tests (add with the patch)

- `shortUnlockedUseFollowedByQuiet_bridgesPrimaryRest`
- `firstUseAfterPrimaryRest_isFinalMorningUseNotShortInterruption`
- `oneShortAwakeningStillConsolidated`
- `historyUsesBridgedEpisode`
- `longUnlockedWakeDoesNotBridge`
- `multipleSignificantAwakeningsCanBeFragmented`

### 14.7 Implementation status + deferred internal inconsistency

**`0.3` IMPLEMENTED (freeze lifted for it on 2026-06-21).** Added `MainRestEpisode` +
`firstUseAfterMainRest` (analyzer), `StoredMainEpisode` + nullable record fields (storage,
`STORAGE_SCHEMA_VERSION` → 2, non-destructive), bridged-episode routing in `InteractionHistory` and
the `MainActivity` formatters (with `primaryRest` fallback), and the new `restPattern` rule. Full
suite green; debug APK rebuilt. **Migration is forward-only** (decided): new/re-collected nights get
`mainRestEpisode`; pre-v2 records fall back to `primaryRest`. `primaryRest` meaning unchanged.

**Deferred / known internal inconsistency (NOT in scope now):** the legacy clock-model
`nightPattern` (`NightPatternAnalyzer.kt:94–108`) still returns `FRAGMENTED_SLEEP_LIKE` for a night
split by a single brief bridged awakening, while the displayed structural `restPattern` now correctly
returns `CONSOLIDATED`. `nightPattern` is **internal and not shown** (Phase 2 made `restPattern` drive
the UI), so this does not affect the user-facing report. Left untouched on purpose; align it only if
the clock layer is retired (`SCHEDULE_AGNOSTIC_DESIGN` Phase 3) or by an explicit separate decision.
