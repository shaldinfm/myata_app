# SETTINGS-APPEARANCE-3.6.6.md — G1, the settings shell and the appearance choice

Phase G of [ANDROID-3.6.6-PLAN.md](ANDROID-3.6.6-PLAN.md), slice **G1**. Two
screens, one preference, and one deliberate change to a control that already
shipped.

Frozen sources: `settings` **2517:2758** (light) / **2517:3725** (dark) and
`settings-appearance` **2517:2817** / **2517:3784**, in
`tools/figma-export/screens-3.6.6/snapshots/`.

---

## 1 · The entry point

**A second 40x40 control on the HOME header, beside the profile control, opens
`settings`.** The profile control is unchanged and still opens the profile in one
tap.

### The design does not specify this, and that was established rather than assumed

The `.fig` was decoded directly — ZIP → `canvas.fig` → Kiwi schema + zstd
document, all **265,954 nodes**. The findings:

- **Zero prototype metadata.** No node carries `prototypeInteractions`, none
  carries the legacy `transitionNodeID`, and there are no prototype starting
  points. The page named `прототип` is seven old-app screens with nothing wired.
- **"Настройки" appears exactly twice in the whole file** — as the `Heading 1` of
  `settings` and `settings_dark`. It is in no menu row, no button, no tab, no
  header, anywhere.
- Neither profile screen has a Settings row; neither overflow menu has one.

So the frozen design draws **no path to `settings` at all**. The entry is a
product decision the frame cannot settle, and it was delegated to this
implementation. It is recorded here as an owner-delegated decision, in the same
class as the `О нас` header title and the Threads-for-Twitter swap.

### Why the HOME header, and what was rejected

| option | why not |
|---|---|
| The hidden 40x40 slot on HOME / ABOUT US (`2393:1670`, `2413:61`) | Its geometry decodes to a circle of r≈4.6 at (6.5,6.9) plus a contour to the (18,18) corner closing with three straight segments — **a magnifier**. It is a search control the design switched off, and repurposing a control whose intent is known is exactly what this correction exists to stop. |
| PLAYER overflow | `Menu / Плеер` is the player's own actions — Найти трек, Таймер сна, Сообщить о проблеме, История эфира. Settings is not one of them, and none of the four exists yet, so the slot stays reserved rather than becoming a menu with one unrelated row. |
| COLLECTION overflow | `Menu / Коллекция` is the two exports. Same objection, plus the control is `visible:false` on the empty frame, which would have made Settings unreachable with an empty collection. |
| A row on `profile-guest` / `profile-authenticated` | Inverts the nesting the frozen `settings` screen draws (`Row / Профиль` makes the profile a child of Settings) and creates a Settings ↔ Profile cycle that grows the back stack. It also needs a new section header on **two** frozen screens — more invention than it first appears. |
| A fifth bottom-bar destination | The frozen `BottomNavBar` is four items on every screen. Settings is not a peer of Главная / Плеер / Коллекция / О нас. |
| Retarget the profile control (what G1 shipped) | The profile must stay one tap from HOME. |

The chosen addition is the smallest one that is semantically right:

- It lands in **space the frozen band already leaves empty**. The greeting
  occupies 16..201 and the profile control 334..374; 133dp between them is
  unused. The new control takes 286..326, 8dp before the profile control.
  **Nothing frozen moves** — the greeting keeps its leading anchor, the profile
  control keeps its trailing one, the band keeps its 64dp.
  `SettingsEntryTest.the_new_control_moves_nothing_that_was_already_there`
  measures exactly that rather than trusting it.
- It **reuses the existing component**: the same `bg_profile_entry` plate, the
  same 40dp box, the same 16x16 glyph inset baked into a 40-unit viewport. The
  only new artwork is the gear itself.
- The bottom bar is untouched, and the nesting the frozen `settings` frame draws
  is preserved exactly: Settings contains `Row / Профиль`, and Settings is never
  entered *from* a profile, so `HOME > settings > profile` is as deep as it goes
  and there is no cycle.

**HOME only.** ABOUT US and the empty COLLECTION carry the profile control too and
deliberately do not get this one: HOME is the front door and is always one tap
from the bottom bar, so one entry is enough and three would be three places to
keep in step.

### Two earlier attempts, and why they are recorded

**G1** retargeted the shared 40x40 control to Settings and put the profile behind
`Settings > Аккаунт > Профиль`. **G1a** put `Настройки` on the PLAYER and
COLLECTION overflows. Both were withdrawn by the owner. They are named here
because the tests that rule them out — `profile_control_still_opens_the_profile`,
`the_collection_overflow_is_still_only_its_own_actions`, and `PlayerLayoutTest`'s
reserved-slot assertion — only make sense against what they were guarding.

## 2 · Two sections, not five

The frozen `settings` frame draws five sections. G1 draws **Аккаунт → Профиль**
and **Внешний вид → Тема**, and nothing else.

Stream quality, the sleep timer, Last.fm, the report form and the about-app row
are each their own slice and none of them exists. This is deliberately *not* the
treatment `Row / Аватар` gets on profile-authenticated, which is drawn inert with
a chevron that does nothing: an inert Аватар row names a feature the account
genuinely has and cannot reach yet, whereas a row reading `Таймер сна —
Выключен` or `Last.fm — Не подключён` states a **fact** about a feature with no
implementation behind it to make the fact true or false.

`SettingsLayoutTest.theUnbuiltSectionsAreAbsentRatherThanInert` holds this, so a
later edit that quietly adds one back is visible in review.

## 3 · The `Row / Профиль` value

Three outcomes, decided in `SettingsProfileRow`:

| condition | value |
|---|---|
| not signed in — guest, anonymous, signed out, or a deletion in flight | `Не вошли` (the frame's own string) |
| signed in, session names an address | the address |
| signed in, no address available | `Вошли` |

**The address is the session's, not the identity's.** `IdentityStore` does not
have one: `markRegistered` persists a uid and nothing else on purpose. The
address comes from `EmailAuthBackend.api(context).currentAccount()` →
`AccountInfo.email` → `ProfileAccount.email`, which is the same path
`ProfileAuthenticatedFragment` already renders the account card from. No identity
or auth contract moved for this row.

The ordering is `ProfileAuthenticatedFragment.verifySession`'s exactly — routing
first (which reconciles), the session read second — so the two screens cannot
disagree about who this device is. The `signedIn` bit and the row's tap
destination are the *same* `ProfileRoute.destination` answer, so the value and
the screen it opens cannot contradict each other.

`AccountInfo.email` is nullable for real reasons (an account created by other
means, a session that has not restored yet). The account card answers that with
`Email недоступен`, which is right on a card whose subject is the account; on a
one-line settings row it is noise about a field the row was not promising, so the
row falls back to `Вошли` — the weaker claim, and still a true one.

## 4 · The appearance model

Three options and no fourth. A true-black / AMOLED variant was considered for
this screen and dropped — see `screens-3.6.6/PR23-CONCEPT-REVIEW.md`.

```
ThemeMode.SYSTEM  ->  AppCompatDelegate.MODE_NIGHT_UNSPECIFIED   "system"
ThemeMode.LIGHT   ->  AppCompatDelegate.MODE_NIGHT_NO            "light"
ThemeMode.DARK    ->  AppCompatDelegate.MODE_NIGHT_YES           "dark"
```

**Системная is `MODE_NIGHT_UNSPECIFIED`, not `MODE_NIGHT_FOLLOW_SYSTEM`.** The two
follow the system identically once applied, but only one is free to assign:
`UNSPECIFIED` is AppCompat's own unset value, so assigning it is not a change,
while assigning the explicit `FOLLOW_SYSTEM` *is* — and it was measured recreating
`MainActivity` on **every cold start**. `AppearanceSelectionTest` counted two
activity creations on a plain launch and is why the mapping is what it is.

Stored by `ThemeStore` in its own `myata_appearance` preferences file, key
`theme_mode`, written with `apply()`. Words rather than ordinals, so reordering
the enum cannot silently repoint everybody's choice. Anything unparseable —
including an absent key — resolves to `SYSTEM`.

Its own file, not one of the identity stores: an appearance belongs to the
device, so signing out must not change it and signing in as somebody else must
not carry theirs across. `AccountDeletionCleanup` does not touch it.

### Migration for existing installs: there is none

Nothing is written at install, at upgrade, or by opening the screen and leaving
it. An install arriving from 3.6.5 has no key, `ThemeStore.read` answers `SYSTEM`,
and `SYSTEM` installs `MODE_NIGHT_UNSPECIFIED` — the exact state the activity was
in before G1, with no override at all. The upgrade is a no-op *by construction*
rather than by a step somebody has to run, which is why there is no version number
in that file and no backfill.

A stored value this build cannot parse is treated the same way — and is **left on
disk unchanged**. Rewriting it to `system` would be the app silently migrating a
preference on the listener's behalf, and a downgrade that understood the original
would find it gone.

### How a choice is applied

```
ThemeStore.write(mode)                              the choice reaches disk first
(activity as AppCompatActivity).delegate
    .localNightMode = mode.nightMode()              then the window is told
```

The second call runs `applyDayNight()`. `uiMode` is **not** in `MainActivity`'s
`configChanges`, so the activity is recreated; the recreated activity reads
`ThemeStore` in `attachBaseContext` and comes back in the chosen appearance, and
`applySystemBarAppearance()` re-runs on that path — as its own KDoc already
anticipated. The Navigation back stack survives, so the listener stays on the
appearance screen and watches it repaint. That is what the frozen note —
*«Тема применяется сразу, без перезапуска.»* — promises.

Writing before applying is not cosmetic: the recreated activity reads the value on
its way in, so a write that happened second would race its own recreation.

### Where the mode is assigned, and why it is not `onCreate`

`MainActivity.attachBaseContext`, before `super`. That is the last point at which
the night mode is still an *input* to the activity rather than a change to it: the
delegate has no base context yet, so it folds the mode into the one it is about to
build, and the activity is created once with the right configuration.

Assigned in `onCreate` instead — where the first implementation put it — the
delegate is already attached, so it applies the mode immediately, and applying it
is a configuration change the activity does not handle. Measured on API 24: a cold
start on a stored Тёмная created `MainActivity` **twice**, a second full activity
creation on every launch for anybody who had chosen a theme.
`AppearanceSelectionTest.a_cold_start_on_stored_dark_does_not_recreate` caught it
and is what holds the fix in place. Системная was unaffected either way, because
`MODE_NIGHT_UNSPECIFIED` is not a change.

Choosing the mode that is already current does nothing at all — no write, no
assignment, no recreation.

### Selection presentation

The frozen frame only ever draws Системная selected, so the rest is a decision:

- **Системная keeps its 72dp height and its sub-label in every state.** The
  sub-label describes what Системная *is*, not that it is current, and a row that
  grew and shrank would shift the two rows under it every time the listener
  changed their mind.
- Selection is the **2dp `primary` stroke** (`bg_settings_row_selected`) and the
  **check at x=318**, and nothing else. The plate, radius and fill are identical
  on all three rows in both states; an inside stroke grows inwards, so no row
  shifts by the extra pixel.
- The check is toggled with `INVISIBLE`, not `GONE`, so the 24dp slot stays
  reserved on all three rows and the labels do not reflow.

## 5 · Android TV

**`AppCompatDelegate.setDefaultNightMode` is never called anywhere in this app.**

It is a static, process-wide switch. `TvMainActivity` is an `AppCompatActivity`
in this same process and the `<application>` theme it sits under is now a DayNight
tree, so a process-wide night mode set from a phone screen would reach a TV
surface that cannot open that screen. `delegate.localNightMode` is scoped to one
activity, which makes "TV is unaffected" structural rather than a claim.

`NoProcessWideNightModeTest` proves the call is absent from production source at
all — a JVM test, so CI gates on it — because a single `setDefaultNightMode` on a
path no behaviour test walks would change TV and no runtime assertion here would
ever see it.

`TvThemeIsolationTest` then proves it three ways: `TvTheme` resolves identical colours
under a night configuration for every one of the three modes; no TV drawable
resolves to a different file under one; and the process default is still
`MODE_NIGHT_FOLLOW_SYSTEM`. A fourth test asserts the *mobile* theme does change
under night, so the TV assertions cannot pass for the trivial reason that nothing
resolves per-theme.

No TV source, layout, drawable or colour was touched by G1.

## 6 · Accepted limitations

Both are recorded rather than solved, per the owner's decision for G1.

### Системная on API 24–28 resolves to Light

Following the system means following a platform-wide dark setting that arrived at
**API 29**, and `minSdk` is 24. On 24–28 there is normally nothing for it to
follow on an ordinary phone, so Системная is Light there. Светлая and Тёмная are
unaffected, and Тёмная is how a listener on those releases gets a dark app at
all.

### The platform starting window follows the system, not the choice

`Theme.Myata.Splash` is the only theme the system can read before any app code
runs, and it resolves `@color/background` against the **system** uiMode. Somebody
who chooses Тёмная on a light device sees a light starting window and then a dark
first frame.

No night-mode API can reach a window created before the process exists, so this
is **not** a consequence of choosing an activity-local mode — a process-wide
default would flash identically. Fixing it means a separate splash theme selected
some other way, which is a design question rather than a defect.

## 7 · Verification

| what | how |
|---|---|
| the enum, its stored form, corrupt values | `ThemeModeTest` (unit) |
| `setDefaultNightMode` absent from production source | `NoProcessWideNightModeTest` (unit) |
| the three profile-row outcomes | `SettingsProfileRowTest` (unit) |
| settings geometry, type and colour, light + dark, 320–412dp | `SettingsLayoutTest` |
| appearance geometry, the tall first row, the reserved check slot | `AppearanceLayoutTest` |
| choosing, persisting, repainting in place, the untouched process default | `AppearanceSelectionTest` |
| what Системная installs on the live delegate, fresh install == explicit system, corrupt value not rewritten, Dark→System clears the override, cold start costs one creation | `AppearanceSelectionTest` |
| HOME→Settings→Appearance→Dark→recreate→Back→Back, bottom bar at every step | `ThemeRecreationPlaybackTest` |
| the retargeted control, both back paths, no minted identity | `SettingsEntryTest` |
| TV isolation | `TvThemeIsolationTest` |
| ten changes over a live controller | `ThemeRecreationPlaybackTest` |

### The one manual step

`ThemeRecreationPlaybackTest` asserts the *connection* survives ten changes — the
same controller, never released, never duplicated — and deliberately starts no
stream: that would reach a real host from a suite whose network boundary is
replaced, and leave a live session behind for whatever runs next.

The audible half is a manual check:

```bash
adb logcat -c && adb logcat | grep MyataPlayback
```

Start a stream, open Настройки → Тема, alternate Тёмная/Светлая ten times, and
confirm the audio does not stutter or stop and no `STOP` / reconnect appears in
the log.

---

## What G1 did not do

No Sleep Timer, no Last.fm, no Report a problem, no stream quality, no about-app
row, no avatar work, no Supabase, no TV redesign, no new Gradle dependency, and
no change to `IdentityStore` or any auth contract.
