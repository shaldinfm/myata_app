# Report endpoint

The server half of **G3 · Сообщить о проблеме** — a Google Apps Script Web App.
Nothing here is or may become a secret.

**Status: deployed by the owner and live-validated.** `Code.gs` is the source of
what was deployed; the deployment itself, its Script Properties and its `/exec`
URL live outside this repository and are never recorded in it. Gate A proved
delivery through both entry points, Gate B proved that a failure is reported as a
failure — see *Live validation* below.

Editing `Code.gs` here changes nothing on its own: the live endpoint keeps serving
the version it was deployed with until the owner deploys a new one.

```
Android app  ->  this Web App  ->  Telegram Bot API  ->  a private chat you own
```

## Why this shape

| | |
|---|---|
| **Why an owner endpoint at all** | The frozen design has a sending state, an error banner that keeps what the listener typed, and a success screen. A `mailto:`, a Sharesheet or a `t.me` deep link can produce none of the three — hand the message to another app and the report app never learns whether anything was sent, so all three screens become claims it cannot make. `report-success`'s own frame note settles it: *"The app posts to our own endpoint."* |
| **Why Apps Script** | It is the precedent this repo already ships. `FeedbackRepository` has posted reactions to an Apps Script Web App for a long time, over the same OkHttp client, in the same form-encoded shape. No new infrastructure class, and no Supabase expansion. |
| **Why a separate deployment** | Reports carry free text and device diagnostics; reactions carry a track name. They must not share an endpoint, a sheet or a chat. |
| **Why the token is here** | A bot token in an APK is extractable by anyone who downloads the app, and lets them post as the bot and read its updates. It lives in Script Properties, where rotating it is an edit rather than a release. |

## The contract the Android client depends on

**Request** — `POST`, `application/x-www-form-urlencoded; charset=UTF-8`, exactly
eight always-present string fields:

| field | values |
|---|---|
| `category` | `playback_wont_start` · `stopped_by_itself` · `headphones` · `ui` · `other` |
| `message` | the listener's own words; may be empty; ≤ 2000 chars |
| `app_version` | `3.6.5 (202611)` |
| `device` | `Xiaomi Redmi Note 12` |
| `android` | `14 (API 34)` |
| `network` | `Wi-Fi` · `Мобильная сеть` · `Другая сеть` · `Нет сети` |
| `last_error` | a Media3 constant name plus how long ago, or `нет` |
| `stream` | `MYATA` · `GOLD` · `XTRA` |

**Response** — 200 with a JSON body. `{"ok":true}` → the app shows
`report-success`. Anything else → `report-error`.

> **`ok:true` must mean the Telegram send itself succeeded.**
> The client matches the **value** — `"ok"\s*:\s*true` — and treats everything else
> as a failure, including every `{"ok":false,...}` this endpoint can return. Apps
> Script answers 200 to almost anything, including its own uncaught exceptions, so
> without this a Telegram outage would thank the listener for a message nobody
> received — and the success screen is terminal, so they could not send it again.
>
> The Android half is `ReportAck`, and `ReportAckTest` checks it against every
> answer this file can produce. Matching the key alone — the first implementation —
> made every `{"ok":false}` read as a delivered report, which would also have made
> the forced-failure validation gate pass while proving the opposite.

## What the endpoint never receives

Because the client never sends it: access or refresh tokens, `apikey` or
`Authorization` headers, `auth.uid()`, email, display name, avatar, Collection /
reaction / listening-history contents, `ANDROID_ID`, serial, IMEI, advertising id,
SSID, IP, location, logcat, stack traces, or exception messages. The report path
constructs no Supabase client, so a signed-in and a signed-out listener post
byte-identical envelopes apart from their own words. `ReportPayloadTest` asserts
this against the serialised body, and `ReportProblemFlowTest` again at the
transport boundary.

## Setup — owner, once, outside this repository

1. Create the bot with **@BotFather**. Keep the token out of every file.
2. Create a **private** group or channel for reports; add the bot; get its numeric
   chat id.
3. [script.google.com](https://script.google.com) → New project → paste `Code.gs`.
4. **Project Settings → Script Properties**:
   - `TELEGRAM_BOT_TOKEN` — from BotFather
   - `TELEGRAM_CHAT_ID` — e.g. `-1001234567890`
5. **Deploy → New deployment → Web app**, *Execute as: Me*, *Who has access: Anyone*.
6. Put the `/exec` URL in `report.properties` as `REPORT_ENDPOINT`
   (template: `report.properties.example`).

`report.properties` is untracked. **Never** commit it, and never paste the URL into
an issue, a commit message or a screenshot. The Gradle script fails the build if
anything token-shaped appears in that file, and `ReportConfigTest` re-checks at
runtime.

## Verifying a deployment, before trusting it

```bash
curl -sS -L "$REPORT_ENDPOINT"
```

`{"ok":true,"service":"myata-report","version":1}` — the health check.

```bash
curl -sS -L -X POST "$REPORT_ENDPOINT" \
  --data-urlencode 'category=other' \
  --data-urlencode 'message=проверка эндпоинта' \
  --data-urlencode 'app_version=3.6.5 (202611)' \
  --data-urlencode 'device=curl' \
  --data-urlencode 'android=0 (API 0)' \
  --data-urlencode 'network=Wi-Fi' \
  --data-urlencode 'last_error=нет' \
  --data-urlencode 'stream=MYATA'
```

`{"ok":true}` and a message in the chat. Then check the failure path is honest:
break `TELEGRAM_CHAT_ID` temporarily and confirm the same call answers
`{"ok":false,...}` rather than `{"ok":true}` — that is the single most important
property of this endpoint, and the one that cannot be checked from the app.

Note the `-L`. An Apps Script `/exec` POST answers **302** to
`script.googleusercontent.com`, and the redirect target carries `doPost`'s output —
the redirect does **not** re-run the script or reach `doGet`. OkHttp follows it by
default, which is why the Android client needs no special handling; it is also why
a `curl` without `-L` shows an empty body and looks like a broken endpoint.

## Findings from the server review, and what changed

The proposal was reviewed before deployment. Four things came out of it; all four
are fixed in this file or in the client, and none of them changes the request or
response contract above.

| # | finding | where |
|---|---|---|
| 1 | The client accepted any body containing the **key** `"ok"` — so every `{"ok":false}` this endpoint returns read as a delivered report, and the forced-failure gate would have passed while proving the opposite. | client: `ReportAck` now matches `"ok"\s*:\s*true`, with `ReportAckTest` over every answer below |
| 2 | `UrlFetchApp.fetch` throws with the full URL in its message, and the URL contains the bot token — so `console.error(err)` wrote the token into the execution log. | `redact()`, applied at the only log site |
| 3 | Telegram caps a message at 4096 characters and HTML-escaping expands: 2000 ampersands become 10000 characters, so such a report could never be sent, retry included. Worst case measured at 14875. | escaped-length budgets, worst case now 3975 |
| 4 | Setup told the owner to add a `SHARED_SECRET` property that nothing reads — protection that is not there. | removed from the setup steps |

## Live validation — done

Run against the deployed endpoint on the project's minSdk (API 24), so the oldest
supported device is the one that was proven.

| gate | what it did | result |
|---|---|---|
| **A1** | Player door → `Другое`, one send | success screen; **exactly one** message delivered |
| **A2** | Settings door → `Проблема с интерфейсом`, one send | success screen; **exactly one** more delivered |
| **B** | chat id temporarily invalidated, one send | **`report-error`**, category and typed text preserved, «Отправить ещё раз» offered, **nothing delivered** |
| **B-retry** | chat id restored, one tap on the same preserved form | success; delivered |

Gate B is the one that matters. Before the `ReportAck` fix it would have shown
«Спасибо!» for a message nobody received — and passed, while proving the opposite.

Android logcat across all four runs contained no endpoint URL, deployment id,
host, token-shaped string, `api.telegram.org`, `apikey`, `Authorization`, Supabase
key, or the report text itself. The failure path surfaced no reason at all: the
listener sees one frozen sentence and the detail stays in `Failed(detail)`,
unlogged.

## Redaction spot-check

`redact()` reads the two Script Properties, which sounds alarming and is not: it
uses them only as search needles, so its output is the input with secrets removed
— a real value can appear in the output only if it was already in the input.

Both of its branches were exercised against **this file's real `redact()`**, with
`PropertiesService` stubbed to fabricated values, so no real secret existed in the
process at all:

| input | output |
|---|---|
| a fabricated token | `<token>` |
| `chat_id=<fabricated chat>` | `chat_id=<chat>` |
| `…/bot<fabricated token>/sendMessage` | `…/bot<token>/sendMessage` |
| an `Error` carrying both | both replaced |
| an unknown token-shaped value | `<token>` |
| `telegram http 400: {"ok":false,…}` | unchanged — nothing to remove |

[redact-spotcheck.gs.txt](redact-spotcheck.gs.txt) is a throwaway helper for
confirming the same thing in the Apps Script editor. It is `.gs.txt` so it cannot
be mistaken for deployable code, and running it creates no deployment.

## Abuse

Unauthenticated by design: the frozen flow works with no account, and requiring one
would exclude exactly the listeners most likely to be reporting a problem. Control
therefore lives on the endpoint — a per-field size cap, a coarse per-minute intake
ceiling via `CacheService`, and the Apps Script daily quota as a hard ceiling. A
shared secret is deliberately **not** implemented as a client-sent header: a secret
shipped in an APK is not a secret. If the URL is ever abused, rotate the deployment
— a one-line change to `report.properties` and one app release.

## Open

- ~~**Retention.**~~ **Decided: 30 days, by Telegram's own auto-delete.** The
  reports chat is set to auto-delete after 30 days, which is an owner setting on
  that chat and not code — nothing here reads, writes or schedules it, and nothing
  needs changing to honour it. It also means the 30 days are enforced by the only
  system that holds the data, rather than by a job that could quietly stop running.
- **A sheet as well as a chat.** Trivial to add (`SpreadsheetApp.openById(...)`),
  and deliberately **not** done. A second sink is a second copy of listeners' free
  text — and now also a copy that would outlive the 30-day retention, quietly
  turning a bounded store into a permanent one. G3 requires no archive or export.
