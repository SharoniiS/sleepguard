# SleepGuard — design system (from the Base44 reference)

Distilled from the Base44 React/Tailwind app to implement in **Jetpack Compose**. This is the visual
source of truth; the data/flow is unchanged (see [`ARCHITECTURE.md`](ARCHITECTURE.md)).

## Palette (HSL → approx hex)

| Token | HSL | hex |
| --- | --- | --- |
| background | `232 56% 10%` | `#0B0F28` deep navy |
| card | `230 49% 15%` | `#141A39` |
| foreground (text) | `228 72% 92%` | `#DCE2F9` |
| muted-foreground | `228 50% 72%` | `#A6B4DC` |
| primary / accent | `223 100% 62%` | `#3D74FF` vivid blue |
| secondary | `226 55% 32%` | `#243C7E` |
| border | `228 35% 22%` | `#2A3350` |
| destructive | `0 72% 51%` | red |
| status: consolidated (green) | `142 70% 45%` | `#22C35D` |
| status: fragmented (amber) | `38 92% 55%` | `#F6A823` |
| awakening / cyan | `190 70% 55%` | `#3CC1DD` |
| pre-quiet (purple) | `~280 60% 60%` | `#A78BFA` |
| radius | `--radius: 1rem` | **16dp** |

## Typography (Google Fonts, Hebrew)
- **Rubik** — headings / display (`font-display`, `font-heading`).
- **Assistant** — body (`font-body`).
- mono — for times/HH:MM and the raw log.
→ Add both as downloadable/bundled fonts in Compose. Weights used: 400–800.

## Surface & effect recipes (the "sg-" classes)
- **Glass card** (`sg-card`): `card` @ ~50% opacity + blur + 1px white-6% border + 16dp corners +
  shadow `0 2px 20px rgba(0,0,0,.2)` + inset top highlight `inset 0 1px 0 rgba(255,255,255,.02)`.
- **Raised card** (`sg-card-raised`): card @ ~70% + stronger shadow `0 4px 30px rgba(0,0,0,.3)` — for
  primary metrics.
- **Blue glow ring** (`sg-glow-ring`): `0 0 40px rgba(61,116,255,.10), 0 0 80px rgba(61,116,255,.04)`.
- **Divider**: 1px horizontal gradient, transparent → `border@35%` → transparent (ghost variant @18%).
- **Active nav line**: 2px gradient `primary@70% → primary@15%`, under the active tab.
- **Awakening dot**: cyan, glow + 3s pulse animation.

### Key gradients (keep these exact)
- Card bg: `linear-gradient(160deg, hsl(230 49% 16%), hsl(230 56% 10%) 60%, hsl(228 40% 8%))`.
- Hero radial: `radial-gradient(ellipse 70% 60% at 50% 38%, #2563eb, #081440 50%, #010511)`.
- Owl-insight bg: `linear-gradient(145deg, hsl(230 42% 12%), hsl(226 36% 9%) 50%, hsl(228 32% 8%))`.
- Timeline quiet block: `linear-gradient(90deg, primary@35%, primary@55%, primary@35%)`.

## Owl assets (already hosted — download to `res/drawable`)
- Full owl (hero/empty state): `https://media.base44.com/images/public/6a31992791506b1e6305a01e/713c605d5_.png`
- Circular icon (sm/md): `https://media.base44.com/images/public/6a31992791506b1e6305a01e/b5b88f3cf_.png`
- Hero banner art: `https://media.base44.com/images/public/6a31992791506b1e6305a01e/9e48f6d4d_ChatGPTImageJun17202601_34_55AM.png`
- Questionnaire reference image: `.../5338b7bc2_questionare2.png`

⚠️ Today there is **one** owl pose. The "reactive owl per state" vision needs a matched **set**
(calm/scrolling/restless/nightmare) generated in the same style — that's a later asset task.

## Components (port to Compose)
- **HeroBanner** — rounded-3xl banner: radial bg + glow halos + sparkle stars + owl image, with a
  bottom gradient scrim and title/subtitle overlaid. `default` 16:9, `compact` 16:7 (used in Report).
- **SleepSummaryCard** (Home/glance) — gradient card: Moon icon in a glowing rounded tile, big
  `quietStart – quietEnd` (LTR), divider, big duration, chips row (pattern + availability), ghost
  divider, two metrics (pre-sleep use = purple Smartphone, interruptions = amber Zap).
- **SleepSummaryTimelineBar** — track with quiet-block gradient fill + activity segments + white event
  dots + cyan awakening glow, clustered time pills above with leader lines, 7-tick axis, legend
  (מנוחה / פעילות משוערת), footnote for estimated pre-quiet.
- **OwlInsight** — gradient card, owl with blue blur aura, pill badge "תובנת הינשוף", message text.
- **SleepSummaryHistoryRow** — gradient card: status dot (green consolidated / amber fragmented) +
  date (weekday short) + `start–end` (LTR mono) + duration; optional interruptions line; pattern chip;
  animated progress bar (width = duration / max-in-list), colored by pattern.
- **InfoCard** (Report grid) — small card, accent-colored icon (blue/purple/cyan/amber) + label + value.
- **QuestionnaireModal** — bottom-sheet/modal: header, yes/no toggles (סיוטים/קנאביס/אלכוהול),
  medications **Select** (defaults + saved customs under "תרופות שמורות" + "אחר…" → free text),
  free note, "שמור". (Our app drops the modal — it's a full sub-screen.)
- **BottomNav** — 4 tabs (בית / לילה אחרון / היסטוריה / מידע נוסף), active = primary color + top gradient line.

## Data label mappings (mirror in Compose)
- pattern: `CONSOLIDATED → רצוף`, `FRAGMENTED → מקוטע`, `MINIMAL_REST → פעיל ברובו / מנוחה קצרה`.
- availability: `HIGH → נתונים מלאים`, `MEDIUM → נתונים חלקיים`, `LOW → נתונים מועטים`.
- raw event labels: `Screen On → מסך נדלק`, `Screen Off → מסך נכבה`, `Unlocked → פתיחה`, `Locked → נעילה`.
- Report summary sentences (Base44): CONSOLIDATED "פעילות הטלפון הייתה רצופה…", FRAGMENTED "זוהו מספר
  הפסקות…", MINIMAL_REST "זמן המנוחה היה קצר…". (Our latest copy uses "חלון השינה שנצפה…" — keep that.)

## Animation conventions
- Entrance: fade + slide-up (`y: 20 → 0`), **staggered** by ~0.05–0.10s per card.
- Owl bubble: scale/opacity spring. Modal: spring from bottom.
- → Compose: `AnimatedVisibility` / `animateFloatAsState` + per-item delay. Keep it calm/slow.

## Notes & alignment with our app
- **Medications**: Base44 saves custom meds on the user profile; our app already saves them to the
  Room **`medications`** table — same idea, on-device. Presets match (ריטלין/בנזודיאזפינים/נוגדי דיכאון).
- **"ערוך זמנים" / userCorrection** (QuietWindowEditor): **deferred** in our app — skip for now.
- **No auth / demo / sync** in our app — ignore Base44's `useAuth`, `DEMO_USER_ID`, `SyncBanner`.
- The **legacy NightSession path** in Home/History is superseded by the SleepSummary path — port the
  SleepSummary branch only.
