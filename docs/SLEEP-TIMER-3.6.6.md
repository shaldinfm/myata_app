# SLEEP-TIMER-3.6.6.md — G2, one deadline and the two doors to it

Phase G of [ANDROID-3.6.6-PLAN.md](ANDROID-3.6.6-PLAN.md), slice **G2**. One
feature, two entry points, and a deadline that belongs to neither of them.

Frozen sources, all in `tools/figma-export/screens-3.6.6/`:

| frame | light | dark | size |
|---|---|---|---|
| `sleep-timer-select` | 2517:1937 | 2517:2904 | 358×424 |
| `sleep-timer-custom` | 2517:1969 | 2517:2936 | 358×398 |
| `sleep-timer-custom-invalid` | 2517:1993 | 2517:2960 | 358×398 |
| `sleep-timer-active` | 2517:2017 | 2517:2984 | 358×505 |
| `sleep-timer-active-custom` | 2517:2056 | 2517:3023 | 358×505 |
| `sleep-timer-menu-active` | 2517:2093 | 2517:3060 | 260×264 |
| `sleep-timer-cancelled` | 2517:2116 | 2517:3083 | 358×56 |
| `sleep-timer-completed` | 2517:2122 | 2517:3089 | 358×56 |

plus canonical `Menu / Плеер` **2444:18763** (206×264) and `Row / Таймер сна` on
`settings` **2517:2758** / **2517:3725**.

---

## 1 · The Player overflow opens, with one row

The frozen menu has four: Найти трек, **Таймер сна**, Сообщить о проблеме,
История эфира. This ships the second one and nothing else.

### Why not four rows with three disabled

The rule is already written down, in
[SETTINGS-APPEARANCE-3.6.6.md](SETTINGS-APPEARANCE-3.6.6.md) §2 and held by
`SettingsLayoutTest.theUnbuiltSectionsAreAbsentRatherThanInert`: an inert row is
tolerable when it names a feature the app **genuinely has** and cannot reach yet —
`Row / Аватар` on profile-authenticated — and is not tolerable when it names a
feature with no implementation behind it to make the claim true. Найти трек,
Сообщить о проблеме and История эфира have none. Three greyed rows would be three
dead controls asserting that something exists.

### Why the slot opens now when G1 left it closed

G1's objection, verbatim, was that the overflow would become *"a menu with one
**unrelated** row"* — it was being asked to carry `Настройки`, which is not one of
the player's own actions. `Таймер сна` is one of the four. That is the whole
difference, and it is the same rule the COLLECTION overflow lives under: a menu
carries its own screen's actions and nothing else. Settings is still reached from
the HOME header and is still not on this menu; `SettingsEntryTest` still says so.

### The width is the frozen 260 from the start

`sleep-timer-menu-active`'s note: *"The canonical menu is 206 wide, which cannot
hold a label plus a trailing value. Widened to 260. This is the only change
proposed to the existing menu."* Adopted now rather than when the trailing value
first appears, because re-widening the same surface later would be a second change
to it.

The `10 top / 52 pitch / 50 bottom` padding is the frozen frame's own — stated in
`spec/primitives.mjs:186` and reproduced identically by the two-row
`Menu / Коллекция` at 260×160. It is asymmetric, and it is what the design says in
both places. **See §9 for the one open question this raises.**

### The reserved slot becomes the control it was reserving

`fragment_player.xml`'s `Space` becomes an `ImageView` in the same 32×39 box, so
the header label stays centred exactly where it was and nothing frozen moves.
`PlayerLayoutTest`'s assertion is inverted rather than deleted: it used to say
*nothing may be drawn here yet*, and now says *whatever is drawn here may not
resize the slot, and must do something*.

## 2 · Settings, because the frozen screen asks for it

`settings`'s own note: *"Revised from PR #23: the same groups, but Sleep Timer is
surfaced here as well as in the player menu, because a timer you can only reach
mid-playback is hard to find."*

So the `Воспроизведение` section arrives with one row. `Качество потока` stays
absent even though its section is now drawn — nothing behind it exists, and the
rule did not change. Both doors open **the same sheet over the same state**;
`SleepTimerSurfacesTest.theMenuAndTheSettingsRowSayTheSameThingAboutTheSameTimer`
is what stops them drifting.

## 3 · Ownership

**`MediaPlayerService` owns the timer.** Not a ViewModel, not a Fragment, not the
sheet — `onTaskRemoved` deliberately keeps playing after the Activity is gone, so
anything UI-scoped would evaporate in exactly the case the feature exists for: the
phone face down, the app swiped away, the radio still on.

```
SleepTimerSheet ──ACTION=sleep_timer_{set,cancel,undo,sync}──▶ MediaPlayerService
                                                                │ Handler.postDelayed
                                                                │ SleepTimerStore
                                                                ▼
StreamsViewModel ◀──LocalBroadcast "sleep_timer_state"──────────┘
   └─ LiveData → menu trailing · sheet · Settings row · snackbars
```

Commands use the intent idiom every other UI→service command already uses, and
state comes back on the `LocalBroadcastManager` channel `play` / `pause` /
`buffering` / `metadata_update` already use. Neither direction is new machinery.
The service is exported and its `stop` action has always been reachable from
outside, so a timer command grants no capability that was not already there — and
arming is refused outright on TV (§7).

**Scheduling is a `Handler`, not an `AlarmManager`.** The timer can only *do*
anything while playback is running, and while playback is running the service is
foreground and holds a `PARTIAL_WAKE_LOCK`, so the CPU is up and Doze cannot defer
the callback. An exact alarm would additionally need `USE_EXACT_ALARM` on API 31+,
which Play restricts to alarm-clock and calendar apps — a radio sleep timer does
not qualify, and would gain nothing.

## 4 · Persistence

`myata_sleep_timer`, its own file, the `ThemeStore` shape. Five keys:

| key | type | why |
|---|---|---|
| `deadline_elapsed_ms` | Long | `SystemClock.elapsedRealtime()`. **The authority.** |
| `boot_id` | Int | `Settings.Global.BOOT_COUNT` at arming |
| `duration_minutes` | Int | so the sheet can tick the row that was chosen |
| `is_custom` | Boolean | preset or `Своё время` |
| `generation` | Long | monotonic, so a restart cannot reuse one |

**No wall-clock deadline is stored.** «Воспроизведение остановится в 23:47» is
derived at draw time as *wall clock now + monotonic time remaining*, so a timezone
move, a DST step or an NTP correction changes that string and does not move the
moment playback stops. `SleepTimerStoreTest.the_record_holds_no_wall_clock_deadline`
reads the file's key set, so adding one back "for the subtitle" fails the build.

The service is the only writer. The UI reads through `SleepTimerStore.peek` for a
cold value before the service has answered, and is otherwise told.

### Reboot detection

`Settings.Global.BOOT_COUNT` — API **24**, which is this app's `minSdk`, readable
without permission.

**Why not `elapsedRealtime() < armedAt`.** That is not a detector. `elapsedRealtime`
restarts at zero after a reboot and then *climbs*, so a device that had been up 20
minutes when a 30-minute timer was armed and has been up 40 minutes since
rebooting reads as ordinary progress: `now > armedAt`, `now < deadline`, and a
plausible positive remainder. The comparison only fires inside the narrow window
before the new uptime overtakes the old one, and every reboot after that window is
missed. `SleepTimerStoreTest.a_record_from_another_boot_is_foreign_even_though_its_deadline_is_in_the_future`
is that exact case.

An unreadable `BOOT_COUNT` is stored as `BootIdentity.UNKNOWN` and matches
nothing in either direction: a record that cannot prove which boot it belongs to
is discarded. Losing a timer to a process restart is a smaller failure than firing
one that belongs to a previous boot. There is **no `BOOT_COMPLETED` receiver**
anywhere in the app.

### Reconciliation

Every read is a reconciliation. `reconcileSleepTimer` runs on service creation —
including the `START_STICKY` recreation after a process death — and on every
`sleep_timer_sync`, which is what the sheet, the Player and Settings each ask for
as they resume. Its four outcomes:

| on disk | outcome |
|---|---|
| nothing | off |
| another boot, or an unprovable one | record cleared, off |
| this boot, deadline passed | **expired now**, record cleared |
| this boot, deadline ahead | adopted, generation advanced, rescheduled |

So a `Handler` that was delayed while nothing was playing cannot leave a dead
timer looking armed on a screen that has just been opened.

### Duplicate scheduling

One `generation`, advanced by every arm, cancel, undo and clear. The scheduled
`Runnable` captures the value it was posted with and compares before doing
anything, so a replaced, cancelled, undone or re-adopted timer's outstanding
callback is a no-op instead of a second expiry.

## 5 · Expiry

**Expiry is the app's existing pause, called by the timer instead of by a finger.**
This app has no true pause: `ForwardingPlayer.pause()` clears `userWantsPlayback`
and calls `stop()` **without** clearing the playlist. Expiry takes that same path.

```kotlin
onPlaybackNoLongerWanted("sleep_timer_expired")   // mandatory, see below
exoPlayer.stop()                                  // playlist NOT cleared
```

| | |
|---|---|
| Media3 state | `STATE_IDLE`, `isPlaying=false`, `mediaItemCount > 0` |
| foreground service / notification | untouched — Media3 drops out of foreground itself, exactly as after a user pause |
| mini-player | stays, showing Play (`hasPlaybackSession` is `mediaItemCount > 0`) |
| PLAYER screen | central control returns to Play, from the existing `pause` broadcast |
| Play afterwards | resumes normally through `resume_from_stop`, re-preparing to the live edge |
| snackbar | `Таймер сна завершён / Продолжить`, once |

`onPlaybackNoLongerWanted` **before** `stop()` is load-bearing, not tidy. It is
what tells the recovery machinery the silence was asked for; without it
`STATE_ENDED` handling would read the stop as a dropped connection and reconnect
within seconds, and the timer would look broken.

**Expiry with nothing playing** (D5) clears the timer, issues no playback command
and raises no snackbar. Nothing resumes on its own, ever — `Продолжить` is
`togglePlayPause()`, not a new playback entry point.

## 6 · Cancel and undo

`Вернуть` restores the **original absolute deadline** (D4). A 30-minute timer with
10 minutes left is worth 10 minutes to an undo, not 30.

```
Off ──set(m)──▶ Armed(deadline = now + m, gen++)
Armed ──set(m')──▶ Armed(deadline = now + m', gen++)      replacement, not extension
Armed ──cancel──▶ Off, snapshot = the cancelled timer if its deadline is still ahead
Off+snapshot ──undo──▶ Armed(snapshot.deadline, gen++)    the SAME instant
Off+snapshot ──undo after the deadline passed──▶ Off, snapshot dropped
Off+snapshot ──set(m)──▶ Armed(...), snapshot dropped     a new choice replaces it
Armed ──deadline──▶ Off  (+ stop, if anything was playing)
```

The snapshot lives in the service, in memory only. It is a one-gesture affordance
that lasts as long as a Snackbar, not state anybody should find again after a
restart, and keeping it out of the store is what stops it competing with the one
record that is meant to be durable. The Fragment never reconstructs a timer: it
asks the service to put back the one the service is still holding.

## 7 · Android TV

TV UI is unchanged and nothing in G2 is reachable from it. `MainActivity`
redirects any device reporting `UI_MODE_TYPE_TELEVISION` or no touchscreen into
`TvMainActivity` before the mobile UI exists, and no TV fragment or layout has an
overflow, a Settings screen or a sheet —
`SleepTimerSurfacesTest.androidTvHasNoWayToReachTheTimer` inflates all four TV
layouts and says so.

The service is shared, and it is exported, so the guard also lives **in the
service**: `armSleepTimer` refuses when `isTv`. That is the only place that is
true for every caller, including one outside the app.

## 8 · Playback semantics that did not change

An explicit Pause or Stop leaves the timer armed (D5) — the timer is a promise
about the clock, not about the current session. The `stop` action still destroys
the service; the record outlives it and the next thing that reaches the service
re-adopts the same deadline.

Nothing in recovery, metadata polling, the notification, the foreground contract,
Supabase, auth or identity was touched. `AccountDeletionCleanup` does not clear the
timer store — a sleep timer belongs to the device, like an appearance, and not to
an account.

## 9 · Open questions for the owner

1. **The frozen 50dp bottom padding on the menu.** It is the canonical frame's own
   (`Menu / Плеер` is 264 tall for four rows: 10 + 4×48 + 3×4 + 50) and
   `Menu / Коллекция` repeats it exactly. Reproduced faithfully here. With **one**
   row it is 10dp above and 50dp below, which reads as slack rather than as
   design — see the QA screenshot on the PR. Say the word and it becomes a
   symmetric 10/10 until the other three rows land.
2. **`Своё время`'s default when nothing is armed** is 1 ч 0 мин, on the reasoning
   that the four presets already cover 15–60 so somebody opening the picker wants
   something longer. The frozen frame shows 1 ч 30 мин as example content only.
