# SETTINGS-APPEARANCE-3.6.6.md — G1, the settings shell and the appearance choice

Phase G of [ANDROID-3.6.6-PLAN.md](ANDROID-3.6.6-PLAN.md), slice **G1**. Two
screens, one preference, and one deliberate change to a control that already
shipped.

Frozen sources: `settings` **2517:2758** (light) / **2517:3725** (dark) and
`settings-appearance` **2517:2817** / **2517:3784**, in
`tools/figma-export/screens-3.6.6/snapshots/`.

---

## 1 · The entry point

**The 40x40 header control on HOME, ABOUT US and the empty COLLECTION now opens
`settings`.** It opened `profile-guest` from G-A3 until here.

Nothing in the FINAL Figma file shows how `settings` is reached, and there is no
spare control to reach it with — both HOME (`2393:1670`) and ABOUT US
(`2413:61`) carry a *second* 40x40 node at exactly the same `x=334, y=12`, and
both are `visible: false`. Two overlapping circles is not a layout the file is
offering.

What the file *does* say is that `settings` draws `Row / Профиль` as its first
row, with a value and a chevron. So Settings is the parent surface and the
profile is a destination inside it, and one control pointing at the parent is the
arrangement the design already describes.

Changed: the glyph (person → gear, `ic_settings_entry`) and the content
description. Unchanged: the 40x40 circle, its plate, its position, its three
hosts, and the four bottom-bar destinations beside it.

The rename that followed is mechanical — `view_profile_entry` →
`view_settings_entry`, `profile_entry_*` → `settings_entry_*`,
`ProfileEntryTest` → `SettingsEntryTest`. The control's name now says what it
does.

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
ThemeMode.SYSTEM  ->  AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM   "system"
ThemeMode.LIGHT   ->  AppCompatDelegate.MODE_NIGHT_NO              "light"
ThemeMode.DARK    ->  AppCompatDelegate.MODE_NIGHT_YES             "dark"
```

Stored by `ThemeStore` in its own `myata_appearance` preferences file, key
`theme_mode`, written with `apply()`. Words rather than ordinals, so reordering
the enum cannot silently repoint everybody's choice. Anything unparseable —
including an absent key — resolves to `SYSTEM`.

Its own file, not one of the identity stores: an appearance belongs to the
device, so signing out must not change it and signing in as somebody else must
not carry theirs across. `AccountDeletionCleanup` does not touch it.

### Migration for existing installs: there is none

Nothing is written at install, at upgrade, or by opening the screen and leaving
it. An install arriving from 3.6.5 has no key, `ThemeStore.read` answers
`SYSTEM`, and `MODE_NIGHT_FOLLOW_SYSTEM` is what AppCompat was already doing. The
upgrade is a no-op *by construction* rather than by a step somebody has to run,
which is why there is no version number in that file and no backfill.

### How a choice is applied

```
ThemeStore.write(mode)                              the choice reaches disk first
(activity as AppCompatActivity).delegate
    .localNightMode = mode.nightMode()              then the window is told
```

The second call runs `applyDayNight()`. `uiMode` is **not** in `MainActivity`'s
`configChanges`, so the activity is recreated; `MainActivity.onCreate` reads
`ThemeStore` before `super.onCreate` and comes back in the chosen appearance, and
`applySystemBarAppearance()` re-runs on that path — as its own KDoc already
anticipated. The Navigation back stack survives, so the listener stays on the
appearance screen and watches it repaint. That is what the frozen note —
*«Тема применяется сразу, без перезапуска.»* — promises.

Writing before applying is not cosmetic: the recreated activity reads the value
in `onCreate`, so a write that happened second would race its own recreation.

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

`TvThemeIsolationTest` proves it three ways: `TvTheme` resolves identical colours
under a night configuration for every one of the three modes; no TV drawable
resolves to a different file under one; and the process default is still
`MODE_NIGHT_FOLLOW_SYSTEM`. A fourth test asserts the *mobile* theme does change
under night, so the TV assertions cannot pass for the trivial reason that nothing
resolves per-theme.

No TV source, layout, drawable or colour was touched by G1.

## 6 · Accepted limitations

Both are recorded rather than solved, per the owner's decision for G1.

### Системная on API 24–28 resolves to Light

`MODE_NIGHT_FOLLOW_SYSTEM` follows a platform-wide dark setting that arrived at
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
| the three profile-row outcomes | `SettingsProfileRowTest` (unit) |
| settings geometry, type and colour, light + dark, 320–412dp | `SettingsLayoutTest` |
| appearance geometry, the tall first row, the reserved check slot | `AppearanceLayoutTest` |
| choosing, persisting, repainting in place, the untouched process default | `AppearanceSelectionTest` |
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
