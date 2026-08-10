# A0 · Android TV baseline and isolation design

Phase A0 of [ANDROID-3.6.6-PLAN.md](ANDROID-3.6.6-PLAN.md). Goal: make it
impossible for the mobile 3.6.6 token migration to change TV, and provable that
it did not.

**Status: audit and isolation design complete. Baseline capture is BLOCKED on the
environment, so the isolation is NOT implemented.** See
[Baseline status](#b--baseline-capture--blocked).

---

## A · Resource dependency inventory

Traced transitively from `TvMainActivity`, the three TV fragments, their layouts,
and the manifest.

### The coupling is one theme

```
AndroidManifest
  <application android:theme="@style/AppTheme">
  <activity .TvMainActivity android:theme="@style/AppTheme">   <-- the only real link
```

`AppTheme` is shared between TV and the mobile application default. Everything
else TV touches is either TV-only or reached *through* that theme.

### What TV consumes

| kind | resources | shared with mobile? |
|---|---|---|
| style | `AppTheme` | **yes — the coupling point** |
| style | `RoundedCornerImage` | TV-only |
| colour | `main_fragment`, `white`, `black`, `teal_200`, `teal_700` | reached **only via `AppTheme`**, not referenced by TV layouts |
| font | `muller_light` | TV-only |
| font | `muller_regular` | **shared** (7 mobile layouts) |
| drawable | `bg_tv_stream_button_new`, `btn_back_tv`, `btn_pause_tv`, `btn_play_tv`, `card_gold_tv`, `card_myata_tv`, `card_xtra_tv`, `logo_tv`, `myata_bg_tv`, `myata_bg_load_tv`, `gradient_scrim_bottom`, `tv_banner` | TV-only |
| drawable | `zaglushka_logo` | **shared** (4 mobile references) |
| string | `app_name` | shared, not a visual risk |

**The TV drawables carry their colours as inline hex** — `#FF3F7B`, `#1C4771`,
`#00E5FF`, `#FFFF00`, `#FFCCFF`, `#E6404040` and so on, written directly into the
vector and layout files. That is normally a smell; here it is the reason TV is
nearly immune to a colour-resource migration. Only the theme attributes can reach
it.

### Finding worth flagging before A3

`AppTheme0`–`AppTheme9` are **not dead code**. `MainActivity` does:

```kotlin
val theme = (0..9).random()
when(theme){ 0->{ setTheme(R.style.AppTheme0) } … }
```

The ten differ in exactly one item — `android:windowBackground` →
`@drawable/screen0…screen9` — so **the mobile app shows one of ten random window
backgrounds per launch**. That is a product behaviour, and the A3 theme collapse
has to preserve or deliberately retire it, not delete it by accident.

`AppTheme` itself is *not* one of the ten. It has no `windowBackground` and adds
`windowFullscreen`, `windowIsTranslucent`, `windowDisablePreview` and
`statusBarColor`. TV depends on those, which is convenient: forking `AppTheme`
does not touch the random-background mechanism at all.

---

## B · Baseline capture — BLOCKED

**No Android TV emulator or system image exists in this environment.**

```
AVDs available     : Myata_API36
  device profile   : pixel_7        (phone)
  tag.id           : google_apis    (not android-tv)
  hw.dPad          : no
  resolution       : 1080x2400 @ 420dpi
system images      : android-36/google_apis/x86_64 only
```

A phone AVD cannot produce a TV baseline: no leanback profile, no D-pad, wrong
density and aspect. Capturing screenshots there and calling them a TV baseline
would be worse than having none, because the later comparison would silently
prove nothing.

**To unblock, one of:**

1. Install a TV system image and create a TV AVD — roughly 1–2 GB download:
   ```
   sdkmanager "system-images;android-34;android-tv;x86_64"
   avdmanager create avd -n Myata_TV_API34 -k "system-images;android-34;android-tv;x86_64" -d tv_1080p
   ```
2. Or connect a physical Android TV device over `adb`.

Either is a change to the machine's SDK, so it needs your go-ahead rather than
being done unilaterally.

### What the baseline must record when it runs

Per screenshot: emulator/API/device profile, app commit, resolution and density,
theme in effect, and the exact navigation path taken.

| screen | states |
|---|---|
| splash | initial, transition out |
| stream selection | each card focused (MYATA, GOLD, XTRA), and unfocused |
| player | per station, playing and paused, play/pause focused and unfocused, back button focused |
| player metadata | title and artist populated, and the empty/placeholder state |

---

## C · Behavioural smoke baseline — prepared, not executed

Blocked by the same missing emulator. The checklist below is derived from the
code so it is ready to run unchanged.

From `TvPlayerFragment` and `TvStreamSelectionFragment`:

- initial focus lands on `btnPlayPause` (player) and `cardMyata` (selection);
- returning to the player restores focus to the **current** station's button —
  `gold` → `btnStreamGold`, `myata_hits` → `btnStreamXtra`, otherwise
  `btnStreamMyata`;
- `KEYCODE_DPAD_CENTER` and `KEYCODE_ENTER` both activate;
- `setOnFocusChangeListener` drives the focus visuals on both screens;
- Back is handled by `OnBackPressedDispatcher` in `TvMainActivity`.

Checks: D-pad up/down/left/right traversal order · focus visuals appear and clear ·
activation via centre and enter · switching MYATA/GOLD/XTRA · play/pause · Back
from player and from selection · metadata updates on track change.

**No speculative fixes.** If something is already broken, record it as a
pre-existing condition; do not repair it inside A0.

---

## D · Proposed isolation — smallest that works

Fork the one shared theme, pin its colours, touch nothing else.

**1. `values/colors_tv.xml`** *(new)* — today's literal values, so a later edit to
`colors.xml` cannot reach TV:

```xml
<color name="tv_window">#000000</color>        <!-- was main_fragment -->
<color name="tv_on_primary">#FFFFFFFF</color>  <!-- was white -->
<color name="tv_on_secondary">#FF000000</color><!-- was black -->
<color name="tv_secondary">#FF03DAC5</color>   <!-- was teal_200 -->
<color name="tv_secondary_variant">#FF018786</color> <!-- was teal_700 -->
```

**2. `TvTheme`** in `values/themes.xml` **and** `values-v29/themes.xml` — a
verbatim copy of today's `AppTheme` with the five colours swapped for the aliases
above. `values-v29` keeps `android:forceDarkAllowed=false`, which also stops the
system dark-forcing TV later.

**3. `AndroidManifest.xml`** — one attribute:

```xml
<activity android:name=".TvMainActivity" android:theme="@style/TvTheme">
```

**4. Nothing else changes.** TV layouts, drawables, fonts and fragments are left
exactly as they are. No layout is copied.

### Guard list

These are shared and must not be deleted or repurposed by the mobile phases
without giving TV its own copy first:

- `@font/muller_regular` — TV + 7 mobile layouts
- `@font/muller_light` — TV-only, but lives in the shared `font/` folder
- `@drawable/zaglushka_logo` — TV + 4 mobile references
- `@drawable/gradient_scrim_bottom` — TV-only despite the generic name

---

## Risks

| risk | mitigation |
|---|---|
| `<application android:theme="@style/AppTheme">` still applies before an activity theme resolves | TV's activity theme overrides it; verify the splash window specifically in the baseline |
| A3 collapses `AppTheme` and forgets `TvTheme` inherits from the same Material parent | `TvTheme` gets an explicit parent, not one inherited by name |
| the random-window-background behaviour is deleted during A3 | flagged above; decide to keep or retire it deliberately |
| `forceDarkAllowed` missing below API 29 | pre-29 has no system dark mode, so TV is unaffected |
| isolation looks correct but shifts something by a pixel | this is exactly why the baseline gates the change |

---

## Suggested PR boundary

**A0 · TV isolation** — one PR, four files, no behaviour change:

```
res/values/colors_tv.xml        new
res/values/themes.xml           + TvTheme
res/values-v29/themes.xml       + TvTheme
AndroidManifest.xml             TvMainActivity theme
```

Merge gate: TV screenshots and focus paths identical to the baseline captured
**before** the change, on the same AVD and commit.

Blocks A2 and A3. Does not block A1.
