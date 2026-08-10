# Final design audit — live 3.6.6 proposal pages

Read-only. Nothing in Figma was changed, and no fix below has been applied.

| | |
|---|---|
| light page | `3.6.6 PROPOSALS - LIGHT` — 29 top-level frames, exported 2026-08-10T07:44:22.219Z |
| dark page | `3.6.6 PROPOSALS - DARK` — 29 top-level frames, exported 2026-08-10T07:45:07.213Z |
| findings | **26 blocking**, 774 to review, 32 informational — grouped into 114 distinct items |

## Flow coverage

| flow | states | missing |
|---|---|---|
| Sleep Timer | 8/8 | — |
| Report a Problem | 5/5 | — |
| Broadcast History | 5/5 | — |
| Collection | 2/2 | — |
| Auth / Profile | 5/5 | — |
| Settings | 4/4 | — |

## Severity

- **blocking** — content is lost or the layout cannot be reproduced in Android.
- **review** — needs an owner decision. Nothing is changed without approval.
- **info** — expected, or noted for the record.

## Blocking (8)

### text (6)

- **light** · `sleep-timer-custom` — **×2**
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - e.g. `sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Часы > value`, `sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Минуты > value`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.
- **light** · `sleep-timer-custom-invalid` — **×2**
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - e.g. `sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Часы > value`, `sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Минуты > value`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.
- **light** · `profile-authenticated`
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - `profile-authenticated > Account card > Avatar > initial`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.
- **dark** · `sleep-timer-custom_dark` — **×2**
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - e.g. `sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Часы > value`, `sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Минуты > value`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.
- **dark** · `sleep-timer-custom-invalid_dark` — **×2**
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - e.g. `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Часы > value`, `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Минуты > value`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.
- **dark** · `profile-authenticated_dark`
  - box is 28px against a 32px line height and an ancestor clips - the glyphs are cut
  - `profile-authenticated_dark > Account card > Avatar > initial`
  - *Proposed:* Set vertical resizing to Hug, or raise the height to 32px. Both keep the current x/y.

### history (2)

- **light** · `history-content` — **×8**
  - row is not an auto-layout frame, so it cannot grow with a long title
  - e.g. `history-content > Screen > Broadcast History List > History Item / CRYOGEN`, `history-content > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки`, `history-content > Screen > Broadcast History List > History Item / CITY WALLS`, …
  - *Proposed:* Restore Horizontal auto-layout, padding 13, gap 8, cross-axis align Top, vertical Hug. That reproduces the current single-line height of 74.
- **dark** · `history-content_dark` — **×8**
  - row is not an auto-layout frame, so it cannot grow with a long title
  - e.g. `history-content_dark > Screen > Broadcast History List > History Item / CRYOGEN`, `history-content_dark > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки`, `history-content_dark > Screen > Broadcast History List > History Item / CITY WALLS`, …
  - *Proposed:* Restore Horizontal auto-layout, padding 13, gap 8, cross-axis align Top, vertical Hug. That reproduces the current single-line height of 74.

## To review (88)

### parity (5)

- `sleep-timer-menu-active`
  - structure diverges: light "VECTOR:disc" vs dark "VECTOR:icon"
  - *Proposed:* Check whether the divergence is intentional.
- `settings`
  - node count differs: light 59, dark 60
  - *Proposed:* Compare the two frames; a deliberate per-theme difference is fine, an accidental extra or missing layer is not.
- `settings`
  - structure diverges: light "VECTOR:Vector" vs dark "FRAME:Asset slot / logo/lastfm (PENDING)"; light "TEXT:label" vs dark "VECTOR:Vector"; light "TEXT:value" vs dark "TEXT:label"; light "FRAME:chevron" vs dark "TEXT:value"
  - *Proposed:* Check whether the divergence is intentional.
- `settings-lastfm`
  - node count differs: light 27, dark 25
  - *Proposed:* Compare the two frames; a deliberate per-theme difference is fine, an accidental extra or missing layer is not.
- `settings-lastfm`
  - structure diverges: light "FRAME:Asset slot / logo/lastfm (PENDING)" vs dark "VECTOR:Vector"; light "VECTOR:Vector" vs dark "TEXT:h"; light "TEXT:h" vs dark "TEXT:b0"; light "TEXT:b0" vs dark "TEXT:b1"
  - *Proposed:* Check whether the divergence is intentional.

### text (2)

- **light** · `history-content` — **×16**
  - is 181px wide inside the 179px column "Text" - 2px hangs out
  - e.g. `history-content > Screen > Broadcast History List > History Item / CRYOGEN > Text > title`, `history-content > Screen > Broadcast History List > History Item / CRYOGEN > Text > artist`, `history-content > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки > Text > title`, …
  - *Proposed:* Set the text to Fill container, or widen "Text" to 181. Text wraps at its own width, so the wrap point today is 181, not the column width.
- **dark** · `history-content_dark` — **×16**
  - is 181px wide inside the 179px column "Text" - 2px hangs out
  - e.g. `history-content_dark > Screen > Broadcast History List > History Item / CRYOGEN > Text > title`, `history-content_dark > Screen > Broadcast History List > History Item / CRYOGEN > Text > artist`, `history-content_dark > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки > Text > title`, …
  - *Proposed:* Set the text to Fill container, or widen "Text" to 181. Text wraps at its own width, so the wrap point today is 181, not the column width.

### hidden (2)

- **light** · `settings-lastfm` — **×2**
  - hidden node <FRAME> 24x24
  - e.g. `settings-lastfm > Not connected > Asset slot / logo/lastfm (PENDING)`, `settings-lastfm > Connected > Asset slot / logo/lastfm (PENDING)`
  - *Proposed:* Delete it if it is a leftover; keep it only if it documents an alternate state.
- **dark** · `settings_dark`
  - hidden node <FRAME> 24x24
  - `settings_dark > Row / Last.fm > Asset slot / logo/lastfm (PENDING)`
  - *Proposed:* Delete it if it is a leftover; keep it only if it documents an alternate state.

### constraints (60)

- **light** · `sleep-timer-select` — **×11**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-select > Bottom Sheet / Таймер сна`, `sleep-timer-select > Bottom Sheet / Таймер сна > Sheet row / 15 минут`, `sleep-timer-select > Bottom Sheet / Таймер сна > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-custom` — **×6**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-custom > Bottom Sheet / Своё время`, `sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Часы`, `sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Минуты`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-custom-invalid` — **×6**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-custom-invalid > Bottom Sheet / Своё время`, `sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Часы`, `sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Минуты`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-active` — **×13**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-active > Bottom Sheet / Таймер сна активен`, `sleep-timer-active > Bottom Sheet / Таймер сна активен > Sheet row / 15 минут`, `sleep-timer-active > Bottom Sheet / Таймер сна активен > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-active-custom` — **×13**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-active-custom > Bottom Sheet / Таймер сна активен (своё время)`, `sleep-timer-active-custom > Bottom Sheet / Таймер сна активен (своё время) > Sheet row / 15 минут`, `sleep-timer-active-custom > Bottom Sheet / Таймер сна активен (своё время) > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-menu-active` — **×8**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-menu-active > Menu / Плеер (таймер активен)`, `sleep-timer-menu-active > Menu / Плеер (таймер активен) > Menu row / Найти трек`, `sleep-timer-menu-active > Menu / Плеер (таймер активен) > Menu row / Найти трек > Menu / action icon > Icon / disc > disc`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-cancelled` — **×2**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-cancelled > Snackbar / Таймер отключён`, `sleep-timer-cancelled > Snackbar / Таймер отключён > Icon / timerOff > timerOff`
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `sleep-timer-completed`
  - spans the parent width (left 0, right 0) but is pinned Left only
  - `sleep-timer-completed > Snackbar / Таймер сработал`
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `report-empty` — **×26**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-empty > Header - TopAppBar`, `report-empty > Header - TopAppBar > Button:margin > Icon / back`, `report-empty > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `report-filled` — **×27**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-filled > Header - TopAppBar`, `report-filled > Header - TopAppBar > Button:margin > Icon / back`, `report-filled > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `report-sending` — **×27**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-sending > Header - TopAppBar`, `report-sending > Header - TopAppBar > Button:margin > Icon / back`, `report-sending > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `report-error` — **×29**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-error > Header - TopAppBar`, `report-error > Header - TopAppBar > Button:margin > Icon / back`, `report-error > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `report-success` — **×9**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-success > Header - TopAppBar`, `report-success > Header - TopAppBar > Button:margin > Icon / back`, `report-success > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `history-loading` — **×11**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `history-loading > Screen`, `history-loading > Screen > Header - TopAppBar > Button:margin > Icon / back`, `history-loading > Screen > Header - TopAppBar > Heading 1 > История эфира`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `history-empty` — **×8**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `history-empty > Header - TopAppBar`, `history-empty > Header - TopAppBar > Button:margin > Icon / back`, `history-empty > Header - TopAppBar > Heading 1 > История эфира`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `find-track-sheet` — **×5**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `find-track-sheet > Bottom Sheet / Найти трек`, `find-track-sheet > Bottom Sheet / Найти трек > Sheet row / Spotify`, `find-track-sheet > Bottom Sheet / Найти трек > Sheet row / Apple Music`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `history-error` — **×9**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `history-error > Header - TopAppBar`, `history-error > Header - TopAppBar > Button:margin > Icon / back`, `history-error > Header - TopAppBar > Heading 1 > История эфира`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `collection-track-sheet` — **×8**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `collection-track-sheet > Bottom Sheet / Действия с треком`, `collection-track-sheet > Bottom Sheet / Действия с треком > Sheet row / Spotify`, `collection-track-sheet > Bottom Sheet / Действия с треком > Sheet row / Apple Music`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `collection-overflow-menu` — **×3**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `collection-overflow-menu > Menu / Коллекция`, `collection-overflow-menu > Menu / Коллекция > Menu row / Экспорт в TXT`, `collection-overflow-menu > Menu / Коллекция > Menu row / Экспорт в CSV`
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `auth-sign-in` — **×17**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `auth-sign-in > Header - TopAppBar`, `auth-sign-in > Header - TopAppBar > Button:margin > Icon / back`, `auth-sign-in > Header - TopAppBar > Heading 1 > Вход`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `auth-create-account` — **×16**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `auth-create-account > Header - TopAppBar`, `auth-create-account > Header - TopAppBar > Button:margin > Icon / back`, `auth-create-account > Header - TopAppBar > Heading 1 > Создать аккаунт`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `profile-guest` — **×15**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `profile-guest > Header - TopAppBar`, `profile-guest > Header - TopAppBar > Button:margin > Icon / back`, `profile-guest > Header - TopAppBar > Heading 1 > Профиль`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `profile-authenticated` — **×15**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `profile-authenticated > Header - TopAppBar`, `profile-authenticated > Header - TopAppBar > Button:margin > Icon / back`, `profile-authenticated > Header - TopAppBar > Heading 1 > Профиль`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `settings` — **×20**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `settings > Header - TopAppBar`, `settings > Header - TopAppBar > Button:margin > Icon / back`, `settings > Header - TopAppBar > Heading 1 > Настройки`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `settings-appearance` — **×7**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `settings-appearance > Header - TopAppBar`, `settings-appearance > Header - TopAppBar > Button:margin > Icon / back`, `settings-appearance > Header - TopAppBar > Heading 1 > Тема`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `settings-sync` — **×16**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `settings-sync > Header - TopAppBar`, `settings-sync > Header - TopAppBar > Button:margin > Icon / back`, `settings-sync > Header - TopAppBar > Heading 1 > Синхронизация`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `settings-lastfm` — **×15**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `settings-lastfm > Header - TopAppBar`, `settings-lastfm > Header - TopAppBar > Button:margin > Icon / back`, `settings-lastfm > Header - TopAppBar > Heading 1 > Last.fm`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `history-content` — **×4**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `history-content > Screen`, `history-content > Screen > Header - TopAppBar > Button:margin > Icon / back`, `history-content > Screen > Header - TopAppBar > Heading 1 > История эфира`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `profile-avatar` — **×7**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `profile-avatar > Header - TopAppBar`, `profile-avatar > Header - TopAppBar > Button:margin > Icon / back`, `profile-avatar > Header - TopAppBar > Heading 1 > Выбор аватара`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **light** · `profile-avatar`
  - sits 0px from the right edge but is pinned Left
  - `profile-avatar > Avatar cell 6 > Selected badge`
  - *Proposed:* Set horizontal constraint to Right so it survives a width change. No pixel moves.
- **dark** · `sleep-timer-select_dark` — **×11**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-select_dark > Bottom Sheet / Таймер сна`, `sleep-timer-select_dark > Bottom Sheet / Таймер сна > Sheet row / 15 минут`, `sleep-timer-select_dark > Bottom Sheet / Таймер сна > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-custom_dark` — **×6**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-custom_dark > Bottom Sheet / Своё время`, `sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Часы`, `sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Минуты`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-custom-invalid_dark` — **×6**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время`, `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Часы`, `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Минуты`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-active_dark` — **×13**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-active_dark > Bottom Sheet / Таймер сна активен`, `sleep-timer-active_dark > Bottom Sheet / Таймер сна активен > Sheet row / 15 минут`, `sleep-timer-active_dark > Bottom Sheet / Таймер сна активен > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-active-custom_dark` — **×13**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-active-custom_dark > Bottom Sheet / Таймер сна активен (своё время)`, `sleep-timer-active-custom_dark > Bottom Sheet / Таймер сна активен (своё время) > Sheet row / 15 минут`, `sleep-timer-active-custom_dark > Bottom Sheet / Таймер сна активен (своё время) > Sheet row / 15 минут > Sheet / leading icon > Icon / clock > clock`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-menu-active_dark` — **×7**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-menu-active_dark > Menu / Плеер (таймер активен)`, `sleep-timer-menu-active_dark > Menu / Плеер (таймер активен) > Menu row / Найти трек`, `sleep-timer-menu-active_dark > Menu / Плеер (таймер активен) > Menu row / Таймер сна`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-cancelled_dark` — **×2**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `sleep-timer-cancelled_dark > Snackbar / Таймер отключён`, `sleep-timer-cancelled_dark > Snackbar / Таймер отключён > Icon / timerOff > timerOff`
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `sleep-timer-completed_dark`
  - spans the parent width (left 0, right 0) but is pinned Left only
  - `sleep-timer-completed_dark > Snackbar / Таймер сработал`
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `report-empty_dark` — **×26**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-empty_dark > Header - TopAppBar`, `report-empty_dark > Header - TopAppBar > Button:margin > Icon / back`, `report-empty_dark > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- **dark** · `report-filled_dark` — **×27**
  - spans the parent width (left 0, right 0) but is pinned Left only
  - e.g. `report-filled_dark > Header - TopAppBar`, `report-filled_dark > Header - TopAppBar > Button:margin > Icon / back`, `report-filled_dark > Header - TopAppBar > Heading 1 > Сообщить о проблеме`, …
  - *Proposed:* Set horizontal constraint to Left and right. Pure metadata - no pixel moves.
- … and 20 more distinct items of this kind

### sheet (14)

- **light** · `sleep-timer-select`
  - clipsContent is off
  - `sleep-timer-select > Bottom Sheet / Таймер сна`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `sleep-timer-custom`
  - clipsContent is off
  - `sleep-timer-custom > Bottom Sheet / Своё время`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `sleep-timer-custom-invalid`
  - clipsContent is off
  - `sleep-timer-custom-invalid > Bottom Sheet / Своё время`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `sleep-timer-active`
  - clipsContent is off
  - `sleep-timer-active > Bottom Sheet / Таймер сна активен`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `sleep-timer-active-custom`
  - clipsContent is off
  - `sleep-timer-active-custom > Bottom Sheet / Таймер сна активен (своё время)`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `find-track-sheet`
  - clipsContent is off
  - `find-track-sheet > Bottom Sheet / Найти трек`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **light** · `collection-track-sheet`
  - clipsContent is off
  - `collection-track-sheet > Bottom Sheet / Действия с треком`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `sleep-timer-select_dark`
  - clipsContent is off
  - `sleep-timer-select_dark > Bottom Sheet / Таймер сна`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `sleep-timer-custom_dark`
  - clipsContent is off
  - `sleep-timer-custom_dark > Bottom Sheet / Своё время`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `sleep-timer-custom-invalid_dark`
  - clipsContent is off
  - `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `sleep-timer-active_dark`
  - clipsContent is off
  - `sleep-timer-active_dark > Bottom Sheet / Таймер сна активен`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `sleep-timer-active-custom_dark`
  - clipsContent is off
  - `sleep-timer-active-custom_dark > Bottom Sheet / Таймер сна активен (своё время)`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `find-track-sheet_dark`
  - clipsContent is off
  - `find-track-sheet_dark > Bottom Sheet / Найти трек`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.
- **dark** · `collection-track-sheet_dark`
  - clipsContent is off
  - `collection-track-sheet_dark > Bottom Sheet / Действия с треком`
  - *Proposed:* Turn it on so children cannot paint over the rounded corners. No geometry change.

### history (2)

- **light** · `history-content` — **×4**
  - "time" is not at the same x in every row: 14, 15
  - e.g. `time`, `Album art`, `Text`, …
  - *Proposed:* Rows should share one anchor. Fix by making the rows consistent auto-layouts rather than by dragging.
- **dark** · `history-content` — **×4**
  - "time" is not at the same x in every row: 14, 15
  - e.g. `time`, `Album art`, `Text`, …
  - *Proposed:* Rows should share one anchor. Fix by making the rows consistent auto-layouts rather than by dragging.

### assets (3)

- `-`
  - 3 hidden 'logo/lastfm' slot(s) remain beside real artwork: light · settings-lastfm, light · settings-lastfm, dark · settings_dark
  - *Proposed:* The mark has been supplied, so these are leftovers. Deleting them changes nothing visually.
- **light** · `profile-avatar`
  - 15 of 16 avatar cells are empty rings - the pending slot was deleted rather than filled
  - *Proposed:* Expected while the Material 3 avatars are outstanding, but nothing in the file now records what goes in them. Consider restoring a named placeholder, or treat this note as the record.
- **dark** · `profile-avatar_dark`
  - 15 of 16 avatar cells are empty rings - the pending slot was deleted rather than filled
  - *Proposed:* Expected while the Material 3 avatars are outstanding, but nothing in the file now records what goes in them. Consider restoring a named placeholder, or treat this note as the record.

## Informational (18)

### autolayout (2)

- **light** · `history-content` — **×8**
  - 6.06px of slack on a fixed axis with nothing set to grow
  - e.g. `history-content > Screen > Broadcast History List > History Item / CRYOGEN > Button / find track`, `history-content > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки > Button / find track`, `history-content > Screen > Broadcast History List > History Item / CITY WALLS > Button / find track`, …
  - *Proposed:* Harmless, but Hug or a growing child would make the intent explicit.
- **dark** · `history-content_dark` — **×8**
  - 6.06px of slack on a fixed axis with nothing set to grow
  - e.g. `history-content_dark > Screen > Broadcast History List > History Item / CRYOGEN > Button / find track`, `history-content_dark > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки > Button / find track`, `history-content_dark > Screen > Broadcast History List > History Item / CITY WALLS > Button / find track`, …
  - *Proposed:* Harmless, but Hug or a growing child would make the intent explicit.

### spacing (2)

- **light** · `sleep-timer-menu-active`
  - 4 evenly spaced children (gap 4), positioned absolutely
  - `sleep-timer-menu-active > Menu / Плеер (таймер активен)`
  - *Proposed:* A vertical auto-layout with gap 4 would reproduce the exact same pixels and make it robust. Zero pixel change - safe to apply on approval.
- **dark** · `sleep-timer-menu-active_dark`
  - 4 evenly spaced children (gap 4), positioned absolutely
  - `sleep-timer-menu-active_dark > Menu / Плеер (таймер активен)`
  - *Proposed:* A vertical auto-layout with gap 4 would reproduce the exact same pixels and make it robust. Zero pixel change - safe to apply on approval.

### sheet (14)

- **light** · `sleep-timer-select`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-select > Bottom Sheet / Таймер сна`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `sleep-timer-custom`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-custom > Bottom Sheet / Своё время`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `sleep-timer-custom-invalid`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-custom-invalid > Bottom Sheet / Своё время`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `sleep-timer-active`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-active > Bottom Sheet / Таймер сна активен`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `sleep-timer-active-custom`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-active-custom > Bottom Sheet / Таймер сна активен (своё время)`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `find-track-sheet`
  - uniform corner radius 28 (top and bottom)
  - `find-track-sheet > Bottom Sheet / Найти трек`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **light** · `collection-track-sheet`
  - uniform corner radius 28 (top and bottom)
  - `collection-track-sheet > Bottom Sheet / Действия с треком`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `sleep-timer-select_dark`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-select_dark > Bottom Sheet / Таймер сна`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `sleep-timer-custom_dark`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-custom_dark > Bottom Sheet / Своё время`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `sleep-timer-custom-invalid_dark`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `sleep-timer-active_dark`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-active_dark > Bottom Sheet / Таймер сна активен`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `sleep-timer-active-custom_dark`
  - uniform corner radius 28 (top and bottom)
  - `sleep-timer-active-custom_dark > Bottom Sheet / Таймер сна активен (своё время)`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `find-track-sheet_dark`
  - uniform corner radius 28 (top and bottom)
  - `find-track-sheet_dark > Bottom Sheet / Найти трек`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.
- **dark** · `collection-track-sheet_dark`
  - uniform corner radius 28 (top and bottom)
  - `collection-track-sheet_dark > Bottom Sheet / Действия с треком`
  - *Proposed:* On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing.

