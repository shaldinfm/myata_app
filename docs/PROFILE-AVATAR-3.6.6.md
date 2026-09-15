# G6a · Profile avatar selection

`profile-avatar` 2523:137 (light) / 2517:3678 (dark), entered from `Row / Аватар` on
`profile-authenticated` 2517:2671 / 2517:3638.

## What ships

- **Entry.** `Row / Аватар` opens the picker. It is the row the frame gives a chevron;
  the account card's circle is not drawn as a control, so it is not one.
- **Picker.** Back band and `Выбор аватара`; the 96dp `Текущий аватар` preview with a
  2dp `primary` ring; the grid (76dp cells, 1dp `outline` ring, 18dp row gutters); the
  selected cell gets a 2dp `primary` ring and the 24dp badge with the check; `Сохранить`
  (358×52, r12, `primary`).
- **24 avatars, 4 columns × 6 rows.** The frame drew 4×4. The approved set is 24, so the grid
  grows downwards with the frame's own cell, gutters, rings and badge, and the screen scrolls
  to `Сохранить` (content 890dp tall; on the 914dp API 36 phone the button starts about 52dp
  below the fold). No smaller cells, no paging, no sticky button - owner decision 2026-09-14.
- **Choosing vs saving.** A tap moves the ring, badge and preview immediately and writes
  nothing. `Сохранить` writes and returns. Back discards. Saving the avatar the account
  already has, or with nothing chosen, just returns.
- **Profile card.** With a known avatar its artwork fills the 64dp circle and the initial
  steps aside. Otherwise the card is exactly the frame's. The picker hands the saved key back
  to the profile (`RESULT_SAVED_AVATAR`), so the card is right even when a fast Save pops back
  to a profile view that was never re-created - see Validation.
- **No bottom bar, no Mini Player** on the picker (`MainActivity` list + `NavScreen.PUSHED`).
- **Navigation.** Row click checks the current destination and the action is
  `launchSingleTop`; leaving checks the destination too. A double tap opens one picker.

## Persistence - account-synced

`user_metadata.avatar_id` on the Supabase auth user, written with the existing GoTrue
`updateUser { data }` call (`EmailAuthApi.updateAvatar`). No table, column, migration,
endpoint or Storage object - and the key is deleted with the auth row, which is the
invariant `ACCOUNT-DELETION.md` and migration 0004 already record for this picker.

- The value is a key (`myata-06`), not an index, so reordering can never swap an avatar.
- GoTrue merges `data`, so `display_name` is untouched.
- The write is refused unless the live session is the registered identity, checked at
  load and again at the moment of writing.
- `updateUser` replaces the session's user (`updateCurrentUser = true`); the profile reads
  the session, so it shows the new avatar at once and after a restart, including offline.
- Another signed-in device picks it up the next time an account surface opens online
  (Profile, Settings, or HOME once a minute) - see "Account surfaces and cross-device refresh".
- Guests have no avatar: the guest frames have no avatar row, and nothing is stored locally.
- A failed write (offline, expired session) keeps the picker open with the choice ringed
  and shows a toast; nothing is stored.

## Account surfaces and cross-device refresh (G6a client fixes, 2026-09-14)

- **Settings `Row / Профиль`** shows the account's `display_name` (via `ProfileAccount.displayName`,
  the card's own rule). An account with no usable name shows `Пользователь`; an account this
  device cannot read right now shows `Вошли`; a guest `Не вошли`. The row never shows the email.
- **HOME's 40×40 profile control** shows the account's avatar when `avatar_id` is a known key,
  and the generic person glyph for a guest, no avatar, or an unknown key (owner decision,
  overriding the earlier "HOME stays generic"). Same size, hit target and routing to Settings.
  ABOUT US and the empty COLLECTION keep the glyph.
- **Selected badge check**: `avatar_badge_check`, #F5F7FA light / #0F253E dark, opaque -
  owner-specified. The decoded 2523:154 stroke reads #E3E8ED; the owner's value supersedes it.
  `Сохранить` keeps `avatar_on_primary`. The check also looked broken for a second reason: the
  selected ring was the cell's `foreground`, which Android draws above every child, so the 76dp
  ring's arc crossed the badge through the check's joint in the badge's own colour and cut the
  check in two. The ring is now a view between the artwork and the badge, as Figma layers them.
- **Cross-device refresh.** Surfaces read the user stored with the session, which supabase-kt
  replaces only on a token refresh or a sign-in; live validation showed a second device keeping
  the old avatar through relaunches. `EmailAuthApi.refreshAccount()` calls
  `retrieveUserForCurrentSession(updateSession = true)` - a `GET /auth/v1/user` whose answer
  replaces the session's user and is saved to storage. `AccountRefresh` runs it when an account
  surface opens: Profile and Settings every time, HOME at most once a minute. Registered accounts
  with a matching session only. Any failure (offline, token, server) keeps the cached account and
  never signs out. No polling, no realtime, no backend change.
- **Refresh vs. Save ordering** (found in final validation). A refresh started when the card
  opened could land after a Save: it redrew the pre-save avatar and, because it stores the
  user in the session, could leave that pre-save user there for HOME and Settings. Avatar
  writes now run under the refresh lock (`AccountRefresh.write`), so a refresh and a save never
  overlap; and the card ignores a refresh that started before a handed-back Save.
  `ProfileAvatarTest` `n` and `o` hold a refresh open to prove each half.
- **HOME on a cold start** (found in the production smoke). supabase-kt restores the stored
  session in the background after the client is created; HOME read the account before that
  finished, got null, and drew the guest header. Online the refresh redrew it a second later;
  offline it stayed a guest header until HOME was resumed again. A registered install's HOME
  now waits for the restore (`EmailAuthApi.awaitSessionRestored`, i.e. the Auth plugin's
  `awaitInitialization` - a local storage read, no request) before reading. A guest never
  waits. `AccountSurfacesTest` `k`-`o` hold the restore open past HOME's first frame; without
  the wait `k`, `m`, `n` and `o` fail on what HOME draws.
- **No guest frame for a known account** (owner report: HOME's header, Settings' name and the
  card's name blinked out and back when navigating). The session was never empty - a refresh
  swaps one authenticated session for the next in one step - but each recreated view starts
  from its layout (`Привет!` + glyph, `Не вошли`, an invisible card) until its read returns.
  `KnownAccount` keeps the last account this process verified, in memory only, and each surface
  paints it synchronously when its view is created, for the same registered uid only; the read
  that follows still decides, and only a definitive "no account" clears it. A cold start begins
  empty. `AccountSurfacesTest` `p`, `q`, `r` hold every session read open; without the paint all
  three fail on exactly the reported frames.
- **Cold start: the splash waits for the local restore** (owner decision, option 2). A new
  process knows no account, and HOME's first frame used to be the guest header for 0.3-0.5 s
  (recorded). `StartupAccountGate` keeps the launch splash (`setKeepOnScreenCondition`) for a
  REGISTERED install only, until `prepareSession()` + `awaitSessionRestored()` + the session
  read settle - local only, never the account refresh. Ceilings: `RESTORE_TIMEOUT_MS` = 1 s on
  the restore itself (storage load measured 0.21-0.49 s; the only phase an expired token could
  take to the network) and `TIMEOUT_MS` = 3 s on the whole wait (client construction measured
  1.05-1.45 s on the slowest QA emulator; full gate 1.2-2.0 s over 10 real cold starts). The
  3 s ceiling is a main-thread timer: coroutine timeouts are cooperative and building the
  client is a blocking call, so only a timer the IO work cannot hold up guarantees release
  (`StartupGateTest` d3; without the timer that case held the splash for 44.9 s). The
  outcome goes into `KnownAccount` and HOME repaints in the same main-thread step that releases
  the splash. On a throw or a timeout the splash is released anyway and HOME shows a neutral
  header (no greeting, no glyph, control still opens Settings) until its own read settles; a
  definitive "no session" is the guest header. Guests are never held. `StartupGateTest` a-g;
  recorded: 3 online and 2 offline cold starts on production with 0 guest and 0 neutral
  frames after the splash, and 3 guest cold starts on API 24 with the guest header straight
  away.
  Open: when a stored session's token has already expired and cannot be renewed offline, the
  plugin reports no session, and routing (`ProfileRoute`) treats that as signed out. Keeping the
  account there is a routing decision, not a display one, and is not changed here.
  Not exercised: a stored session whose access token has already expired, which the plugin
  refreshes over the network before it treats it as a session. This change does not alter
  that case on any surface.

## Decided, because the frames are silent

| Question | Decision |
|---|---|
| Default with nothing saved | The initial on `primary` - the frame's own profile card. The picker opens with **no** cell ringed. |
| `Текущий аватар` with nothing saved | The same initial disc at 96dp (Onest Medium 36sp, the card's 24sp scaled by 96/64). |
| Unknown / blank stored key | Treated as none - initial, nothing ringed. Never a guessed avatar. The pre-release `m3-NN` keys fall here. |
| Wider than 390dp | Cells stay 76dp, the 16dp margins stay, the three column gutters share the extra (25dp at 411dp). Narrower than 358dp content, cells shrink so four fit. |
| Space under `Сохранить` | 24dp plus the navigation bar (`avatar_scroll_bottom_clearance`, the frame's own 702 - 678). Not the shared 154dp `content_bottom_clearance`: that reserves room for the Mini Player, which is hidden here, and HOME, COLLECTION, ABOUT, Settings, auth and Profile all use it, so it stays unchanged and the picker passes its own value to `applyAuthInsets`. |
| `Сохранить` label and check colour | `avatar_on_primary`: #E3E8ED light (this frame's value; every other filled button is #FFFFFF), #0F253E dark. |
| Spoken descriptions | `Аватар N из 24`, radio-button role with checked state and `Выбран` / `Не выбран`; preview and card say which avatar or `аватар не выбран`. |

## Assets

24 owner-created avatars, `drawable-nodpi/avatar_myata_01.webp` … `avatar_myata_24.webp`,
384×384 WebP q95 (alpha), 18-34 KB each, **615 KB total**. Produced by
`tools/avatars/export_avatars.py --source <candidate folder>` from the approved candidate
masters (1254px PNGs, kept outside the repo). `tools/avatars/selection.json` is the approved
mapping; `tools/avatars/manifest.json` records, per avatar, the source file, its SHA-256, the
selected version, the fitted circle, what preparation changed, and the output file's hash.

Technical preparation only - the artwork is not altered:

- **Fit:** the largest circle with no transparent pixels, pulled in 4 source px, so off-centre,
  slightly non-round or canvas-clipped discs leave no crescent (1.5-3.4% of the radius).
- **Opacity:** the masters' discs are alpha ≈250 (≈2% see-through); inside the circle alpha is
  set to 255.
- **Strays:** pixels outside the circle are dropped.
- **Edge:** resampled in premultiplied alpha, then an analytically anti-aliased circle; the
  manifest's `edge_luminance_step` (outer ring vs just inside) stays within ±2.5 - no halo.
- **Quality:** q85/90/95/100/lossless were measured; q95 kept (PSNR ≥34.8 dB over both themes;
  q100 adds 228 KB for +0.3 dB). WebP does not carry the sources' C2PA manifests; the manifest
  keeps their SHA-256 instead.

### Final mapping (grid order)

| Row | Col 1 | Col 2 | Col 3 | Col 4 |
|---|---|---|---|---|
| 1 | myata-01 ← `myata_06` | myata-02 ← `myata_19` | myata-03 ← `myata_18v2` | myata-04 ← `myata_16` |
| 2 | myata-05 ← `myata_08` | myata-06 ← `myata_10` | myata-07 ← `myata_17` | myata-08 ← `myata_04` |
| 3 | myata-09 ← `myata_15` | myata-10 ← `myata_03` | myata-11 ← `myata_23` | myata-12 ← `myata_11` |
| 4 | myata-13 ← `myata_12` | myata-14 ← `myata_20` | myata-15 ← `myata_02v2` | myata-16 ← `myata_22` |
| 5 | myata-17 ← `myata_13` | myata-18 ← `myata_21` | myata-19 ← `myata_24` | myata-20 ← `myata_09` |
| 6 | myata-21 ← `myata_05` | myata-22 ← `myata_14` | myata-23 ← `myata_07` | myata-24 ← `myata_01v2` |

Revised versions used: `myata_18v2`, `myata_02v2`, `myata_01v2`. Originals kept over their
revisions: `myata_05`, `myata_12`.

## Figma fidelity (2026-09-14)

Measured against `profile-avatar` 2523:137 / 2517:3678 decoded from `RadioMyata.fig`, at the
frame's own 390dp width (API 36 at 443dpi, a Figma parity density; QA otherwise runs at the
default 420dpi). The only structural change is 4×4 → 4×6 using the frame's row geometry.

| Element | Figma | Android before | Android after |
|---|---|---|---|
| Header band | 64 | 64.0 | 64.0 |
| Back icon slot / glyph | 24 at (12,20); glyph x 7..15, y 4..20, 1.8 | 24 at (12.2,19.8); same path | same |
| Heading | x 56, Montserrat Medium 24/32, centre y 32 | x 56.4, 24sp/32, centre y 31.7 | same |
| Current | 96 at y 96, centred; 2 inside ring | 96 at (147,96) | same |
| Caption | x 16, y 208, 358×20, Onest Regular 14/20, centred | (16,208) 358×20, 14/20 | same |
| Grid top | 244 | 244.2 | 244.2 |
| Cell | 76 | **75.8** (199px) | **76.2** (200px; 199.5 exact) |
| Column x | 16 / 110 / 204 / 298, right edge 374 | 16 / 110.1 / 204.2 / 298.3, right 373.4 | 16 / 110.1 / 203.8 / 297.9, right **374.0** |
| Column gutter | 18 | 18.3 (48px) | 17.9 (47px) |
| Row y (rows 1-6) | 244 / 338 / 432 / 526 / 620 / 714 | 244.2 / 337.9 / 431.6 / 525.3 / 619.0 / **712.8** | 244.2 / 338.3 / 432.4 / 526.1 / 620.2 / **714.3** |
| Row gutter | 18 | 17.9 | 17.9 / 17.5 (47/46px, no drift) |
| Normal ring | 1 inside, #E1E3E4 / #466D8F | 1dp `outline` | same |
| Selected ring | 2 inside, #1C4771 / #5FD9B4 | 2dp `primary` | same |
| Badge | 24 at (52,52), primary; check 1.8 #E3E8ED / #0F253E | 24 at (51.8,51.8) | 24 at (52.2,52.2) |
| Last row → Сохранить | 24 | 24.0 | 24.0 |
| Сохранить | 358×52 r12 at x 16; label Montserrat Medium 21/28 | y **812.6**, 358×52, 21/28 | y **814.5** |
| Below Сохранить | 24 | 24 + nav bar | 24 + nav bar |
| Content end (4×6) | 890 | **888.8** | **890.7** |
| Light: bg / heading / caption / label | #F8F9FA / #003056 / #42474E / #E3E8ED | exact | exact |
| Dark: bg / heading / caption / label | #0F253E / #F5F7FA / #B3C4D1 / #0F253E | exact | exact |

**One confirmed deviation, fixed:** `AvatarGridLayout` rounded the cell and gutter to whole
pixels before testing the fit. At the frame's exact width that overshot by a pixel, so cells
shrank to 199px, and six rows of whole-pixel steps moved `Сохранить` 1.4dp up; the last column
also stopped 2px short of the margin. Sizes are now exact until each edge is placed, columns come
from the drawn cell so the last one ends on the margin, and rows come from the exact pitch.
Pixel audit on screenshots: every element within one device pixel of the Figma render.

**Not changed, by design:** wider than 390dp, the three column gutters share the extra width so
the grid keeps the 16dp margins of `Сохранить` (25dp at 411dp). At 390dp it is the frame exactly.

`ProfileAvatarLayoutTest` now pins all of the above at 320/360/390/412dp in both themes; values
at the end of a chain of rounded boxes are allowed 2px.

## Artwork provenance and release considerations

**History.** Until 2026-09-14 the picker carried 16 temporary images from the Figma frame. 15
of them were Google Avatar Project renders by Janet Mac & Patrick Dias embedded in the Material 3
Design Kit (XMP `dc:rights`). Their redistribution rights in a commercial APK were not clearly
established: the kit file is labelled CC BY 4.0 on Figma Community, but nothing grants rights to
those specific commissioned renders. The owner decided they would not ship. **None of them are in
the APK any more**: the 16 old resources were overwritten under the same names and 8 new ones
added.

**The shipped set** is owner-created and cleared for Radio Myata; every source PNG carries an
OpenAI `gpt-image` C2PA manifest.

**Known release consideration (documented, not a selection criterion - owner decision
2026-09-14).** A visual comparison against the 16 kit renders available in the Figma file found
that several shipped avatars re-create a specific kit render's character, pose and props:

| Shipped | Source | Closely resembles kit render |
|---|---|---|
| myata-01 | `myata_06` | VR headset, orange turtleneck |
| myata-05 | `myata_08` | hand-to-chin pose on yellow |
| myata-08 | `myata_04` | sunglasses, coffee cup, puffer jacket |
| myata-10 | `myata_03` | bald bearded man with puppy |
| myata-12 | `myata_11` | raised arm with megaphone |
| myata-13 | `myata_12` | bouquet with bee, red glasses |
| myata-15 | `myata_02v2` | red hair, make-up brush |
| myata-17 | `myata_13` | beanie with spray can |
| myata-20 | `myata_09` | fur-hood parka, yellow scarf |
| myata-21 | `myata_05` | helmet, skateboard |
| myata-23 | `myata_07` | bike messenger at the handlebars |
| myata-24 | `myata_01v2` | red-haired student with pencil |

Partial overlap: myata-16 (`myata_22`, rider over handlebars with parcels) and myata-19
(`myata_24`, hand on cheek with pencil). Only 16 of the kit's 30 renders could be compared. This
is a provenance observation, not legal advice. Incidental text or marks, illegible at 64dp:
myata-10 mug, myata-22 sign, myata-16 pizza mark.

## Stable IDs

`myata-01` … `myata-24`. Renamed from `m3-01` … `m3-16` on 2026-09-13 and extended to 24 on
2026-09-14, both before any account saved one: **no migration and no alias**. An `m3-NN` value,
`myata-00` or `myata-25` is an unknown key and resolves to no avatar (unit-tested).

## Live persistence test (gated - needs explicit owner GO)

Nothing has been written to production. Every run so far used the fake auth seam.

- **Preconditions**
  - Explicit GO.
  - An existing, dedicated test account owned by the owner. No new `auth.users` row: a test
    cannot clean one up.
  - The owner signs in on the device themselves; credentials are never handled by the agent.
  - A debug build installed normally, not through instrumentation (the test runner blocks live
    Supabase). A marker log line proves the device runs the new APK.
- **Scope of writes:** only `user_metadata.avatar_id` on that one test account.

Steps:

1. API 36, signed in: the profile shows the initial.
2. Choose `myata-11`, `Сохранить` → back on the profile with the avatar; logcat `SupabaseAuth: avatar updated`.
3. The owner checks the dashboard: `avatar_id = "myata-11"`, `display_name` unchanged.
4. Force-stop and relaunch online → avatar shown. Airplane mode and relaunch → still shown (stored session).
5. Offline: choose another avatar and `Сохранить` → toast, picker stays, the dashboard is unchanged.
6. API 24, same account signed in by the owner → shows `myata-11`.
7. Change to `myata-03` on API 36 → API 24 shows it after relaunch or token refresh (record how long it took).
8. Sign out → guest profile. Sign back in → the avatar comes back from the server.
9. Cleanup is the owner's choice: leave `avatar_id`, or remove it in the dashboard. Deleting the
   test account to prove the key goes with the row is a separate GO.

## Validation

- **Unit:** `ProfileAvatarsTest` - 24 keys, order and drawables, metadata key, resolve (incl.
  `m3-NN`, `myata-00`, `myata-25`), default, restored choice, what Save writes, grid geometry.
- **Instrumented, `ProfileAvatarTest` (API 36 and API 24):**
  - Default and unknown key.
  - Choosing in the top, middle and bottom rows.
  - Save, read back and relaunch; no-op save; Back discards; failed save; double Save.
  - Double row tap and back stack.
  - **`j`:** after scrolling, the whole of `myata-24` and `Сохранить` are on screen, and saving
    from there works.
  - **`k`:** the profile card applies the picker's handed-back key without its view being
    re-created, and "verify again" routes a signed-out install to the guest screen.
  - `k` guards a real bug found during integration: run straight after the capture pass, a Save
    0.36s after the picker opened popped back to the *same* profile view (the forward transaction
    had not settled), which never re-read the account and kept showing no avatar. `k` fails with
    the fix disabled and passes with it.
- **Capture:** `ProfileAvatarCaptureTest` (opt-in `captureAvatar=true`) writes the review
  screenshots: profile, picker at top/middle/bottom with a selection in each section, the
  preview, and the profile after Save, light and dark.
- **Not validated:** a live `updateUser` against production - see the gated plan above.

## Formerly failing neighbours

`ProfileAuthenticatedTest` `b_…`, `h_…`, `r_s_t_…` and
`ProfileEntryTest.back_returns_to_the_screen_that_opened_it_and_restores_the_bar` failed on
`dbb8e75` because they predated G4a's HOME → Settings → Profile route. #98 (`11b14d1`) fixed
them, and G6a is rebased on it.
