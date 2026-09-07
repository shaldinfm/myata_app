# Report endpoint — for owner review

The server half of **G3 · Сообщить о проблеме**. `Code.gs` is a Google Apps Script
Web App proposal. **It is not deployed, and deploying it is a separate step the
owner authorises explicitly.** Nothing here is or may become a secret.

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

## Abuse

Unauthenticated by design: the frozen flow works with no account, and requiring one
would exclude exactly the listeners most likely to be reporting a problem. Control
therefore lives on the endpoint — a per-field size cap, a coarse per-minute intake
ceiling via `CacheService`, and the Apps Script daily quota as a hard ceiling. A
shared secret is deliberately **not** implemented as a client-sent header: a secret
shipped in an APK is not a secret. If the URL is ever abused, rotate the deployment
— a one-line change to `report.properties` and one app release.

## Open

- **Retention.** Telegram keeps these messages until someone deletes them. If a
  retention period is wanted, it is a policy decision and a chat-side chore, not
  code here.
- **A sheet as well as a chat.** Trivial to add (`SpreadsheetApp.openById(...)`),
  and deliberately not done: a second sink is a second copy of listeners' free
  text, and it should be a deliberate decision rather than a default.
