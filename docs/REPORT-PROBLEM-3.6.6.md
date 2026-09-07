# Сообщить о проблеме — 3.6.6 (G3)

The frozen frames, the decisions taken against them, and what is still open.
Companion to [SLEEP-TIMER-3.6.6.md](SLEEP-TIMER-3.6.6.md) and
[SETTINGS-APPEARANCE-3.6.6.md](SETTINGS-APPEARANCE-3.6.6.md), whose rules this
slice inherits rather than restates.

## The frames

| id | light | dark | size |
|---|---|---|---|
| `report-empty` | 2517:2129 | 2517:3096 | 390×950 |
| `report-filled` | 2517:2172 | 2517:3139 | 390×950 |
| `report-sending` | 2517:2215 | 2517:3182 | 390×950 |
| `report-error` | 2517:2258 | 2517:3225 | 390×1022 |
| `report-success` | 2517:2317 | 2517:3284 | 390×456 |
| `sleep-timer-menu-active` (row 3) | 2517:2093 | — | 260×264 |
| `settings` (`Прочее`) | 2517:2758 | 2517:3725 | 390×792 |

---

## 1 · Two doors, one screen

Both frozen frames draw the entry: `Menu / Плеер` row 3, and
`Settings > Прочее`. Both ship, both open the same destination in the same state,
and `ReportEntryPointsTest.both_doors_open_the_same_screen_in_the_same_state`
compares the resting state field by field rather than settling for "both navigate
somewhere".

Player-menu-only was never viable: the listener reporting *«Музыка не
запускается»* is by definition not mid-playback. It is the same argument the sleep
timer's own frame note makes for being in both places — *"a timer you can only
reach mid-playback is hard to find."*

## 2 · The Player menu is whole again

G2 shipped the overflow with one row and **10 / 10** padding instead of the frozen
**10 / 50**, by owner decision, and recorded the debt in three places with a test
holding the temporary number. Its condition, verbatim: *"Restoring
`player_overflow_menu_pad_bottom` to 50dp is part of shipping the next Player
action."*

G3 is that action.

| | one row (G2) | two rows (G3) |
|---|---|---|
| width | 260dp | 260dp |
| top / bottom padding | 10 / **10** | 10 / **50** |
| height | 68dp | **160dp** |

160 = `10 + 2×52 + 46`, which is the frozen two-row `Menu / Коллекция` at 260×160
exactly. `SleepTimerSurfacesTest` measured the temporary 68 and now measures the
160 — the mechanism worked, and the deviation recorded in that document's §9 is
**discharged**.

`Найти трек` and `История эфира` are still absent rather than inert, and the two
shipping rows keep their frozen relative order: nothing was promoted to close the
gap. In a build with no report endpoint the menu is G2's again, one row and 10 / 10
— because that owner decision was about a one-row surface, and that is again what
is being drawn.

## 3 · Settings

`Section / Прочее` + `Row / Сообщить о проблеме` (no value, chevron, `message`
glyph). `Интеграции` stays absent entirely; `Качество потока` and `О приложении`
stay absent inside the sections that are drawn. The rule has not moved since G1 —
only the membership of its two lists, which is exactly what
`SettingsLayoutTest.theUnbuiltSectionsAreAbsentRatherThanInert` records.

## 4 · Transport

```
Android app  ->  our own Apps Script Web App  ->  Telegram Bot API  ->  a private chat
```

`report-success`'s own frame note settles the shape: *"The app posts to our own
endpoint. The Telegram bot token lives on that endpoint and never ships in the
APK."*

**A handoff was ruled out, not overlooked.** A `mailto:`, a Sharesheet or a `t.me`
deep link can produce none of `report-sending`, `report-error` or `report-success`
— hand the message to another app and this one never learns whether it was sent,
so all three frozen screens become claims it cannot make.

Apps Script because it is the precedent already in the tree: `FeedbackRepository`
has posted reactions to one for a long time, over the same OkHttp client, in the
same form-encoded shape. **A separate deployment**, though — reports carry free
text and diagnostics, reactions carry a track name, and they must not share an
endpoint, a sheet or a chat. No Supabase expansion, no new infrastructure class.

The endpoint code and its setup are in
[tools/report-endpoint/](../tools/report-endpoint/), **for review and not
deployed**.

### `ok` means delivered

The client treats a 200 as success only if the body carries `"ok"`. Apps Script
answers 200 to almost anything, including its own uncaught exceptions, so without
that check a Telegram outage would show the listener the terminal "Спасибо!"
screen for a message nobody received — and they could not send it again.

### Config gating

`report.properties` → `BuildConfig.REPORT_ENDPOINT` → `ReportConfig.isConfigured`,
the untracked-file route Supabase and release signing already use. **Unconfigured
is an ordinary state, and in it both entry points are absent.** Not defensive
coding: a form that cannot post is a feature that does not exist, and a row opening
it is the dead control the rollout rule exists to prevent. Every build today is in
that state, including CI and every fresh clone.

The Gradle script fails the build if anything token-shaped appears in
`report.properties`; `ReportConfigTest` re-checks at runtime.

## 5 · What is sent, and what cannot be

Two halves, kept apart structurally so the card and the payload cannot disagree —
both are projections of one `ReportDiagnostics.Snapshot`.

**User-authored** — the chosen category (as a stable wire key, never the label) and
the optional free text, verbatim, capped at 2000.

**Automatic** — exactly the six lines the card draws:

| line | value | why it identifies nobody |
|---|---|---|
| app | `3.6.5 (202611)` | the same for every install of a build |
| device | `Xiaomi Redmi Note 12` | a model, not a serial |
| android | `14 (API 34)` | a platform version |
| network | `Wi-Fi` / `Мобильная сеть` / `Другая сеть` / `Нет сети` | the transport **class** only |
| last error | a Media3 constant name + how long ago, or `нет` | a code from a closed set |
| stream | `MYATA` / `GOLD` / `XTRA` | which public stream was selected |

The network line is a class and never a network: no SSID, no operator, no IP. An
SSID is very often a household name or an address — the exact thing the card's
own «Личные данные не отправляются.» promises is not being sent.

**Never sent:** access or refresh tokens, `apikey` or `Authorization`, `auth.uid()`,
email, display name, avatar, Collection / reaction / listening-history contents,
`ANDROID_ID`, serial, IMEI, advertising id, location, logcat, stack traces,
exception messages. The report path constructs no Supabase client, so a signed-in
and a signed-out listener post byte-identical envelopes apart from their own words.
`ReportPayloadTest` asserts this against the serialised body, including a negative
list of forbidden field names and value shapes; `ReportProblemFlowTest` asserts it
again at the transport boundary in a real run of the real screen.

## 6 · `LastPlaybackError`

`PlaybackLog` already computes the error identity at the moment it happens and
writes it to logcat — which is unreachable from a listener's phone. **Issue #15
(playback stopping by itself) is open precisely because no diagnostic data ever
reaches us**, and `PROJECT_STATUS.md` says closing it needs a `MyataPlayback` log
from a real affected device. This is the smallest thing that makes the frozen
diagnostics line true.

It is **passive**, and that is a constraint rather than a description:

- one write, at the *existing* `onPlayerError` observation point, placed after the
  recovery decision has already been taken;
- nothing in playback reads it — no branch, no reconnect, no classification;
- deleting the file would change no playback behaviour at all.

It holds a sanitised Media3 constant name and a monotonic timestamp. **Not** a
stack trace, not `PlaybackException.message`, not the cause's message, not a URL —
those are written by libraries this project does not control and routinely carry
hosts and paths. In memory only: the value dies with the process, which is the
correct lifetime, because an error from a previous launch is not evidence about
this one. `LastPlaybackErrorTest` asserts the exclusions against a realistic
exception carrying a stream URL and a token in its cause.

## 7 · Owner corrections to the frozen copy

Three, all recorded at their strings and asserted in tests, so restoring the frozen
text is a deliberate act rather than an accident.

**D5 — the success body.** The frame reads *"Сообщение отправлено. Если
понадобится, / мы ответим в Telegram."* There is no Telegram handle, no contact
field and no reply channel anywhere in this flow — the report is one-way by
construction — so the promise is one the app cannot keep. Shipped instead:

> Сообщение отправлено.
> Вы помогаете нам улучшать приложение.

The type, the colour and the two-line shape are the frame's; the sentence is true
instead of false, and the gaps around it are wider by §8.3.

The second line first read *"Спасибо, что помогаете нам улучшать приложение."*,
which put «Спасибо» twice on a card whose headline is already «Спасибо!». The owner
rewrote it to address the listener rather than thank them again.

**D4 — the stream line.** The frame reads *"Поток — Мята FM, 128 kbps"*. No
canonical bitrate exists anywhere in the app, so the stream is named — `MYATA` /
`GOLD` / `XTRA`, the names HOME already uses — and nothing is invented after it.

**D3 — the last-error line.** The frame reads *"Последняя ошибка — HTTP 403, 2 мин
назад"* and assumes an error always exists. A fresh process has seen none, so the
honest value is `нет` rather than a hidden line or an invented code. A missing line
would also make the card six entries in one report and five in the next — and "no
error was recorded" is itself evidence: it separates *the stream failed* from *the
stream was fine and the listener still could not hear anything*.

## 8 · Known deviations from the frozen geometry

Three. One is an expression of the frozen rhythm rather than a change to it; two
are owner decisions taken after seeing the first build on a real screen.

### 8.1 The diagnostics list is a 24dp pitch, not a 20dp box with a 4dp gap

Figma authors six 20-high text boxes at 50, 74, 98, 122, 146, 170. Reproduced
literally that is twelve independent `dp` values stacked, and at this project's QA
density (420dpi, 2.625) both 20 and 4 land on a half pixel and round **up**: the
pitch comes out at 64px against an exact 63, and by the sixth line the list had
drifted 2.2dp and the card 3.1dp — which then pushed the Send button off its anchor
and the error banner with it.

Each line is now a 24dp box with its text centred and no gaps. The text centres land
on 60, 84, 108, 132, 156, 180 — identical to the frozen boxes' own centres, because
a 20 box inside a 24 pitch is centred in it — and 24dp is 63px exactly, so nothing
accumulates. `ReportProblemLayoutTest` asserts the **centres** for that reason.

### 8.2 The category rows take an 8dp gap (reverses D6)

The frozen frame stacks five 64-high rows on a **64 pitch** — a zero gap, so the
block reads as one slab. G3 first shipped that literally, as owner decision D6; on
a device it read as five rows crushed together, and the owner reversed it.

They now take **8dp**, the same gap between plates that every other list in this app
uses (`profile_row_margin_top`, on Settings, the profile and the appearance screen).
8 rather than 10 from the range given, because 8 is the number the design system
already has — a 10 here would be a sixth spacing value existing only on this screen.

Pitch is 72, and everything below the list moves down by 32:

| | frozen | shipped |
|---|---|---|
| categories | 110..430 | **110..462** |
| `descLabel` | 446 | **478** |
| `Input` | 476..596 | **508..628** |
| `Diagnostics` | 612..850 | **644..882** |
| `Button` | 874 | **906** |
| `Banner / error` → button | 874 → 946 | **906 → 978** |

Nothing horizontal moves, and the row itself — 358×64, r12, glyph at 32, label at
72, check at 334 — is untouched.

### 8.3 The success card breathes wider than the frozen frame

Figma packs it to 236: 32 padding, the 64 badge, 20 to the headline, 12 to the body,
32 below. On a real screen that reads as text pressed into a frame rather than as a
confirmation, and the owner asked for air on all four gaps. Each grows by one 8dp
step — the increment the rest of the app moves in:

| | frozen | shipped |
|---|---|---|
| card padding top | 32 | **40** |
| badge → headline | 20 | **28** |
| headline → body | 12 | **20** |
| card padding bottom | 32 | **40** |
| card height | 236 | **268** |
| `Готово` | 380 | **412** |

The card's own 120 anchor, its 358 width, the 64 badge centred at 147, the type and
the colours are all the frame's. The two text boxes (32 and 22) did **not** grow:
the air is around the text, not inside it.

### Two related notes, not deviations

- **`report-sending` has no spinner.** The frame draws the button with the label
  «Отправляем…» and the disabled fill and nothing else — the word is the progress
  state. The auth screens swap a label for an indicator because their frames have no
  such label to change; this one does.
- **Every deviation above is pinned by a test.** `ReportProblemLayoutTest` measures
  the 8dp gap, the 72 pitch, the four success gaps and the 268 card, in both themes
  at 320/360/390/412dp — so drifting back toward the frame, or further away from it,
  fails there rather than landing quietly.

## 9 · Android TV

Unchanged. `TvPlayerFragment`, `TvStreamSelectionFragment` and `TvSplashFragment`
share no symbol with `SettingsFragment`, `PlayerOverflowMenu` or the report screen;
the TV surface has no overflow menu and no Settings screen to add a row to, and the
transport is in-app HTTP with no external handoff that could leak across.

The standing shared-process caveat still applies and is not touched here: night mode
stays activity-local, and nothing in this slice calls `setDefaultNightMode`.

## 10 · Before merge

1. **Deploy the endpoint** ([tools/report-endpoint/](../tools/report-endpoint/)) and
   put its `/exec` URL in `report.properties`.
2. **Live-validate both directions.** A real send arriving in the chat, *and* —
   more importantly — a deliberately broken `TELEGRAM_CHAT_ID` producing
   `{"ok":false}` and the app showing `report-error` rather than a false
   "Спасибо!". That is the one property of this feature that cannot be checked from
   the app, and it is the one that would silently lose reports.
3. ~~**Decide retention**~~ — **done: 30 days.**

## 11 · Retention

The reports chat uses **Telegram's own auto-delete at 30 days**, set by the owner
on that chat. It is deliberately not code:

- **Nothing on our side persists a report.** The endpoint forwards to Telegram and
  writes nothing — no sheet, no Drive file, no Properties, no cache of content
  (the rate limiter stores an integer). Telegram is the only copy, so deleting
  there deletes everything.
- **The 30 days are enforced by the system that holds the data**, not by a job of
  ours that could quietly stop running and leave a growing archive nobody checks.
- **No export or archive is required for G3.** Adding a sheet alongside the chat
  would be a second copy of listeners' free text *and* one that outlived the
  retention period, turning a bounded store into a permanent one.

This is the whole retention story: the diagnostics are already non-identifying by
§5, the only free text is what the listener chose to type, and it is gone in 30
days.
