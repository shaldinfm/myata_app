# Splash / app launch audit

Step 1 of the splash migration task: audit before implementation. **No app code is
changed by this document.**

Base: `main` @ `9ae5d93`. Built and measured with the debug APK
(`versionName 3.6.5`, `versionCode 202611`) on `Myata_API36` (API 36, 1080x2400,
420dpi) and `Myata_Probe_API24` (API 24).

> **What the owner decided.** No new Splash design is to be invented, and the ten
> random artworks stay as they are without being declared FINAL. The scope of the
> work became a launch-experience **behaviour** fix: remove the dead time
> ([§0](#0-headline), item 2) and fix the bottom bar drawing on the splash
> (mismatch #1). Both are done on this branch; the evidence is in
> [`tools/qa/splash/`](../tools/qa/splash/). Everything below is the audit as it
> stood, and is left as the record of why. Two corrections to it:
>
> - The cold-start dead time here is quoted as ~4.7 s from a single run on a cold
>   machine. A controlled four-run series later put it at **~3.6 s** on API 36 and
>   **~1.3 s** on API 24; those numbers, in
>   [`launch-timing.json`](../tools/qa/splash/launch-timing.json), supersede it.
> - [§3](#3-final-figma-nodes-and-assets) is unchanged and still stands: there is
>   no FINAL Splash, and nothing in the fix pretends otherwise.

---

## 0. Headline

Two findings decide what this task can and cannot do.

1. **There is no FINAL Splash design.** The canonical 3.6.6 Figma export has ten
   top-level frames per theme page and none of them is a splash. The splash was
   explicitly dropped from the design batch — see [§3](#3-final-figma-nodes-and-assets).
   Step 2 of the task ("migrate to the owner-approved FINAL design") has no
   source to migrate to.
2. **The current launch shows the user nothing at all for the whole cold start.**
   `AppTheme` sets `android:windowDisablePreview=true`, so there is no starting
   window on any API level, and on API 31+ the platform splash screen never
   appears. Measured on the API 36 emulator: the launcher stayed on screen for
   **~4.7 s** after the tap on a cold start and **~6.8 s** after process death,
   with no feedback of any kind. See [§5](#5-measured-launch-behaviour).

Everything else below is subordinate to those two.

---

## 1. Exact current implementation

The "splash" is **not** a splash screen in the platform sense. It is the app's
first navigation destination, drawn inside the one and only Activity.

### The launch path

| Step | Where |
|---|---|
| Launcher intent → `MainActivity` (`MAIN` + `LAUNCHER`, `singleTask`) | [AndroidManifest.xml:39](../app/src/main/AndroidManifest.xml#L39) |
| `LayoutInflaterCompat.setFactory2` (typography), then `super.onCreate` | [MainActivity.kt:80](../app/src/main/java/com/example/musicplayerapp/MainActivity.kt#L80) |
| `setTheme(AppTheme0..9)`, picked at random per launch | [MainActivity.kt:84](../app/src/main/java/com/example/musicplayerapp/MainActivity.kt#L84) |
| TV/no-touchscreen redirect → `TvMainActivity`, `finish()` | [MainActivity.kt:106](../app/src/main/java/com/example/musicplayerapp/MainActivity.kt#L106) |
| `StreamsViewModel` created — its `init` starts the MediaController, metadata polling and `refreshPlaylists()` | [StreamsViewModel.kt:181](../app/src/main/java/com/example/musicplayerapp/StreamsViewModel.kt#L181) |
| `setContentView(activity_main)`, `bottomNavView` forced `GONE` | [MainActivity.kt:120](../app/src/main/java/com/example/musicplayerapp/MainActivity.kt#L120) |
| NavHost starts at `splashFragment` | [navgraph.xml:6](../app/src/main/res/navigation/navgraph.xml#L6) |
| `SplashFragment` reads `android.R.attr.windowBackground` off the theme and puts that same drawable into a `centerCrop` `ImageView` | [SplashFragment.kt:48](../app/src/main/java/com/example/musicplayerapp/fragments/SplashFragment.kt#L48) |
| On `PlaylistsState.READY` (or a non-empty playlist list) → `enterApp()` → navigate to `home`, popping the splash | [SplashFragment.kt:94](../app/src/main/java/com/example/musicplayerapp/fragments/SplashFragment.kt#L94) |
| `MainFragment.onResume()` sets `bottomNavView` `VISIBLE` | [MainFragment.kt:115](../app/src/main/java/com/example/musicplayerapp/fragments/MainFragment.kt#L115) |

So the splash visual is the **random window background, drawn twice**: once by the
DecorView as `windowBackground`, once by the fragment's `ImageView` on top of it.

### The artwork

`res/drawable-nodpi/screen0.png` … `screen9.png` — ten full-bleed illustrations,
**1080x1921 each**, 63–179 KB. They are the legacy "заглушка" brand artwork: a
tiled organic pattern with a large Myata **M** mark, one colourway each. There is
no text layer and no logo asset in the splash — the mark is baked into the bitmap.

`drawable-nodpi/` is deliberate and must stay (an unqualified `drawable/` means
mdpi, which decoded these at 2835x5043 / 57 MB and killed the instrumented suite —
documented at [themes.xml:68](../app/src/main/res/values/themes.xml#L68)).

### The error state

`fragment_splash.xml` also carries a hidden offline/failure layer (title, message,
Retry button) shown when the playlist load gives up — issue #9. It uses
`@color/black` at 80% and `@color/white`, `@font/montserrat_black` and
`@font/montserrat_bold`, i.e. **hardcoded colours and the pre-3.6.6 font family**,
not the semantic roles or the Onest tokens the rest of the app moved to.

---

## 2. Themes involved

| Style | Role | Relevant items |
|---|---|---|
| `Theme.Myata.Base` | root of the mobile tree, `Theme.MaterialComponents.DayNight.NoActionBar` | semantic roles; `android:forceDarkAllowed=false` on v29+ |
| `AppTheme` | **the launch theme** — `application android:theme` in the manifest; `MainActivity` declares none of its own | `windowDisablePreview=true`, `windowIsTranslucent=true`, `windowFullscreen=true`, `statusBarColor=@color/main_fragment` (`#000000`), no `windowBackground` of its own → falls through to `?android:colorBackground` = `@color/background` (`#F8F9FA` / `#0F253E`) |
| `AppTheme0..9` | applied in `onCreate` **after** `super.onCreate`, before `setContentView` | `android:windowBackground=@drawable/screenN`; **none** of the three window flags above |
| `TvTheme` | TV only, deliberately not in this tree | same window flags, TV palette |

Two consequences worth stating plainly:

- The theme that governs the **starting window** is `AppTheme` (the manifest one).
  The theme that governs the **splash pixels** is `AppTheme0..9` (the runtime one).
  They are different themes with different backgrounds. Today that mismatch is
  invisible only because `windowDisablePreview` suppresses the starting window
  entirely.
- `RandomWindowBackgroundTest` ([androidTest](../app/src/androidTest/java/com/example/musicplayerapp/RandomWindowBackgroundTest.kt))
  asserts all three flags are **absent** from `AppTheme0..9` and that each maps to
  its own `screenN`. Any theme change here must keep that test honest.

Light/Dark: `screen0..9` have **no `-night` variant**, so the splash is byte-identical
in Light and Dark. The launch theme's `background` role *is* day/night aware, and
`statusBarColor` is pinned to `#000000` in both.

---

## 3. FINAL Figma nodes and assets

**None exist. This is the blocker.**

`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json` are the
frozen FINAL pages. Their complete top-level frame lists:

| Light — `CURRENT ANDROID UI - LIGHT` (`2388:366`) | Dark — `CURRENT ANDROID UI — DARK` (`2436:531`) |
|---|---|
| `HOME` `2393:1628` | `UI KIT / Radio Myata Dark` `2436:532` |
| `PLAYER` `2396:30727` | `HOME_dark` `2444:10350` |
| `COLLECTION` `2399:31129` | `PLAYER_dark` `2444:18225` |
| `COLLECTION pusto` `2429:185` | `COLLECTION_dark` `2444:18376` |
| `ABOUT US` `2411:31594` | `COLLECTION pusto_dark` `2444:18479` |
| `play/pause` `2484:68`, `2484:61` | `ABOUT US_dark` `2444:18567` |
| `Selected indicator` `2436:1367` | `Menu / …` `2444:18707` |
| `Menu / …` `2444:18763` | `Bottom Sheet / …` `2444:18729` |
| `Bottom Sheet / …` `2444:18768` | `play/pause` `2484:132`, `2484:136` |

A search of **every** Figma JSON in `tools/figma-export/` for node names matching
`splash|заставк|сплеш|запуск|логотип` returns no splash node on any page —
canonical, proposals, baselines or snapshots.

The reason is on record. `tools/figma-export/screens-3.6.6/PR23-CONCEPT-REVIEW.md`
dropped the two splash concepts from PR #23:

> Two splash options — Out of scope for this batch. The splash is entangled with
> the cold-start fix already merged for issue #9, so it should be designed against
> that behaviour rather than in the abstract.

and lists `splash-option-a/b` under "screens replaced" as **dropped**.

`docs/ANDROID-3.6.6-PLAN.md` §B does name `fragment_splash.xml` as a Phase B file,
but Phase B's stated input is "the frozen design", and for this screen the frozen
design has nothing in it.

**So there is no owner-approved FINAL splash to match, and no approved splash
artwork or typography.** `screen0..9` are legacy assets that predate the 3.6.6
work; they are not FINAL, and nothing in the canonical export supersedes them.

---

## 4. Current-vs-FINAL visual mismatches

With no FINAL splash frame, these are mismatches against the **rest** of the
migrated app rather than against a splash design. All are real and visible.

| # | Mismatch | Evidence |
|---|---|---|
| 1 | **The FINAL bottom navigation bar renders on top of the legacy splash artwork** for ~0.5–0.7 s on every cold launch, on both API 24 and API 36. `MainFragment.onResume()` flips `bottomNavView` visible when the transaction commits, while the splash view is still fading out (250 ms) and HOME has not drawn. | `launch-sequence.json`, phase `splash-with-nav-bar`, both runs |
| 2 | **The artwork is magnified and side-cropped on modern aspect ratios.** The bitmaps are 1080x1921 (≈16:9). `centerCrop` on a 20:9 window scales by 2400/1921 = 1.249 and discards **20 % of the artwork width** (10 % each side). The M mark is clipped on the right on the API 36 device. On a 16:9 device it fits exactly. This is aspect-driven, so it is identical at 320/360/390/412 dp for a given aspect ratio. | `launch-sequence.json`, `artworkGeometry` |
| 3 | **No Dark treatment.** `screen0..9` have no `-night` variant, so the splash is the same in both themes while every migrated screen now resolves semantic roles. | resource qualifiers |
| 4 | **The splash error state is un-migrated.** Hardcoded `@color/black`/`@color/white`, Montserrat rather than the Onest tokens, a 24dp-corner `MaterialButton` on `@color/myata_accent` — none of it uses the 3.6.6 roles or `TextAppearance.Myata.Onest.*`. | `fragment_splash.xml` |
| 5 | **No platform splash on API 31+.** Nothing brands the launch on Android 12+; the system splash is suppressed. See below. | `launch-sequence.json`, phase `handoff` |
| 6 | The launch theme's status bar is pinned `#000000` (`@color/main_fragment`) while the migrated shell uses `@color/background`. | `themes.xml` |

---

## 5. Measured launch behaviour

Method, devices and caveats: [`tools/qa/splash/README.md`](../tools/qa/splash/README.md).
The measurements are committed as
[`tools/qa/splash/launch-sequence.json`](../tools/qa/splash/launch-sequence.json).
Recordings and stills stay local, per `tools/qa/.gitignore` — and a committed
splash PNG would prove less than usual here, since the artwork is picked at random
per launch.

### API 24 — legacy path (`Myata_Probe_API24`)

Cold launch, `force-stop` then `am start`:

1. **~5.5 s of launcher.** No starting window, no preview — `windowDisablePreview`
   is doing exactly what it says.
2. One frame of the pre-12 **scale-up launch animation**, the app window growing
   over a **black** surround.
3. Splash artwork, full screen, **~1.6 s**.
4. Bottom navigation bar appears **over** the artwork (mismatch #1).
5. HOME.

Status bar is visible over the app (the runtime `AppThemeN` carries no
`windowFullscreen`). No blank intermediate frame between the animation and the
artwork.

### API 31+ — platform SplashScreen (`Myata_API36`, API 36)

**The platform splash screen never appears.** At 60 fps the launcher→app handoff
is a single-frame hard cut straight to the splash artwork: no icon, no
`windowSplashScreenBackground`, no exit animation. This is consistent with
`windowDisablePreview=true` together with `windowIsTranslucent=true` on the launch
theme; the app neither uses nor fights the platform splash, it suppresses it.

`androidx.core:core-splashscreen` is **not** a dependency, and there is no
`windowSplashScreen*` attribute anywhere in `res/`.

Measured sequences:

| Scenario | Result |
|---|---|
| **Cold** (`force-stop` → launch) | ~4.7 s launcher, no feedback → hard cut to artwork → artwork ~1.4 s → nav bar over artwork ~0.7 s → HOME, playlist cards still empty and filling for ~1 s |
| **Warm** (HOME key → relaunch, process alive) | **No splash.** Straight back to HOME. Correct — the splash destination was already popped. |
| **Process death** (`kill -9`, pid 4624 → 5081, then relaunch) | **No splash.** ~6.8 s of launcher with no feedback, then HOME directly — the navigation state is restored past the splash. |
| **First run after install** | The API 33+ `POST_NOTIFICATIONS` dialog lands on the launch, producing a `Resume → Stop → Restart → Resume` cycle in the activity log. Not a duplicate Activity launch — one `ActivityRecord` throughout. |

No duplicate Activity launch was observed in any scenario. No white flash was
observed in any scenario; the only non-app colour seen is the black surround of
the API 24 scale-up animation.

**The dominant defect is the dead time.** Because there is no starting window, the
user gets zero acknowledgement of the tap for the entire cold start. That is the
precise problem the platform splash screen exists to solve, and this app has opted
out of it on every API level.

### Not measured

Playback/session restoration across a launch was not exercised — that belongs to
the implementation's validation pass, not to a read-only audit, and the scope bars
touching playback.

---

## 6. Startup / navigation logic coupled to the splash

Anything that changes the splash has to keep all of this working:

- **The splash gate is a network gate.** `enterApp()` fires on
  `PlaylistsState.READY` or a non-empty `playlistList`. `refreshPlaylists()` runs
  inside `withTimeoutOrNull(PLAYLISTS_LOAD_BUDGET_MS)` with exponential backoff.
  There is **no fixed timer** — the splash is exactly as long as the playlist
  fetch, which is the issue #9 design. It is not an artificial delay, but it *is*
  a real block on the first usable frame.
- **`MAX_SPLASH_WAIT_MS = 15_000`** is a backstop that shows the error state, not
  a delay before entry.
- **`registerDefaultNetworkCallback`** auto-retries when connectivity returns, and
  is unregistered in `onDestroyView`.
- **`hasNavigated`** guards against the two observers both firing `enterApp()`.
- **The navigation action pops the splash inclusively**
  (`popUpTo=splashFragment`, `popUpToInclusive=true`), which is why warm launch and
  process recreation correctly skip it.
- **`bottomNavView` visibility** is toggled from `MainActivity.onCreate`,
  `SplashFragment.onCreateView`, `MainFragment`, `InfoFragment` and
  `FavoritesFragment` — five places, and mismatch #1 lives in that spread.
- **The random theme choice happens before the splash exists** and is asserted by
  `RandomWindowBackgroundTest` plus `tools/qa/themes/verify-random-themes.mjs`.
  It is product behaviour, not incidental.
- **The TV redirect** (`isTvMode || !hasTouchScreen`) runs before any of this and
  calls `finish()`.
- **`StreamsViewModel.init`** starts the MediaController connection and metadata
  polling. The bound `MediaPlayerService` keeps the process alive — `am kill` on a
  backgrounded app was a no-op for exactly this reason.

Out of scope but worth one line: **`TvSplashFragment` has a hardcoded
`postDelayed(…, 2000)`** — the only genuinely artificial splash delay in the
codebase. TV is frozen and out of this task's scope.

---

## 7. Platform limitations

- **On API 31+ a splash screen cannot be a design surface.** The system owns it:
  a background colour, an icon (optionally animated, 1 s cap), an optional
  branding image at the bottom, and a fixed circular icon mask. A full-bleed
  illustration cannot be reproduced there. Any "exact FINAL parity" requirement
  for a full-screen image would have to be met by the *app's* first frame, not by
  the system splash.
- **The app has no adaptive launcher icon** (no `mipmap-anydpi-v26`), so the
  platform splash would show the legacy bitmap `ic_launcher`, which is not
  designed for the splash icon mask.
- **`windowIsTranslucent=true` on the launcher activity** suppresses the launch
  transition and the splash-screen exit animation. Removing it is a window-behaviour
  change that reaches beyond the splash and needs its own verification.
- **The current splash duration is bounded by the network**, not by the platform.
  Keeping the platform splash on screen until the playlists arrive would need
  `setKeepOnScreenCondition`, which is exactly the "delay the first frame for
  branding" behaviour the task forbids — unless it is bounded to the work the app
  genuinely cannot render without.
- **10 random full-screen bitmaps cannot become a platform splash background.**
  `windowSplashScreenBackground` takes a colour; `windowSplashScreenAnimatedIcon`
  takes one drawable resolved at install time from the theme, not at runtime.
  The per-launch randomness can only survive inside the app's own first frame.

---

## 8. Proposed mapping, API 24–30 vs API 31+

This is the shape the migration would take **once a FINAL design exists**. It is a
proposal for the owner to approve or replace, not a decision.

| | API 24–30 | API 31+ |
|---|---|---|
| Starting window | A dedicated launch theme with a real `windowBackground` (a layer-list: the FINAL background colour + centred logo), replacing `windowDisablePreview=true`. Kills the dead time. | Platform SplashScreen via `androidx.core:core-splashscreen`, which back-ports the same theme attributes to 24+ so one theme drives both. |
| Branding | The layer-list drawable, drawn by the framework before any app code runs. | `windowSplashScreenBackground` = FINAL splash background role; `windowSplashScreenAnimatedIcon` = the approved mark; icon needs to be authored for the circular mask. |
| Handoff | Starting window → app first frame. Same background colour on both sides, so no jump. | `installSplashScreen()` in `MainActivity.onCreate` **before** `setContentView`; default exit, no `setKeepOnScreenCondition` unless the owner approves a bounded one. |
| App-side splash | The existing `SplashFragment` destination stays and keeps the issue #9 gate, error state and retry. | Same. |
| Random artwork | Stays as the fragment's own background — the only place per-launch randomness can live. | Same. |
| Dark | Launch theme background from the `background` semantic role, which is already day/night. | `windowSplashScreenBackground` from the same role. |

Two things that must be settled before any of that is written:

- Whether the ten random artworks remain the splash at all, or whether the FINAL
  splash is a single branded frame. That is a product decision, not an
  implementation one.
- Whether `windowIsTranslucent` / `windowFullscreen` may leave `AppTheme`. They
  are asserted-absent on `AppTheme0..9` today; making the launch theme sane means
  touching them.

---

## 9. Owner decisions needed

The task says to stop for owner decisions if FINAL parity conflicts with mandatory
Android system splash behaviour. It does, and worse — there is no FINAL to compare
against. Blocking questions:

1. **What is the FINAL Splash?** It was dropped from the 3.6.6 batch and never
   drawn. Nothing can be migrated until it is designed and frozen like the other
   screens, with light and dark nodes in the canonical export.
2. **Do the ten random artworks survive?** They cannot be the API 31+ system
   splash. They can only be the app's own first frame.
3. **Should the dead time be fixed independently of the design?** Removing
   `windowDisablePreview` and giving the launch a real starting window is a
   behaviour fix, not a visual migration, and it is the single largest improvement
   available. It could ship on its own — but it needs a background colour and a
   mark, which is a design input again, even if a minimal one.
4. **May mismatch #1 (nav bar over the splash) be fixed now?** It is a two-line
   ordering bug, visible on every cold launch, and independent of the FINAL design.

Until at least #1 is answered, implementation cannot start.
