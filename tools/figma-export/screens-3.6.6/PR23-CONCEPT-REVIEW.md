# Review of the PR #23 concepts against the repaired canonical language

PR #23 (`design/v366-light-and-new-screens`) predates the Figma export and the
canonical repair. It was written before there was any way to read the real file,
so its palette is a reconstruction. This is a review of it against the repaired
canonical pages, not an acceptance of it.

**Verdict: keep the flows, replace the token layer, drop three decisions.**
PR #23 stays a draft and is superseded by `screens-3.6.6/`.

---

## 1. The palette does not match the real file

Every core role in PR #23 is close to the canonical value and wrong. Close is
the problem: a two-point drift reads as correct in review and produces a build
that never quite matches the design.

### Dark

| role | PR #23 | canonical | |
|---|---|---|---|
| `background` | `#0B1D31` | **`#0f253e`** | different |
| `surface` | `#132E4A` | **`#142d47`** | different |
| `surfaceContainer` | `#1B4163` | **`#1c4771`** | different |
| `navigationContainer` | `#102A44` | **`#142d47`** | different |
| `primary` | `#69E5BE` | **`#5fd9b4`** | different |
| `outline` | `#5A82A3` | **`#466d8f`** | different |
| `textSecondary` | `#C3D3DF` | **`#b3c4d1`** | different |
| `textPrimary` | `#F5F7FA` | `#f5f7fa` | matches |
| `onPrimary` | `#0F253E` | `#0f253e` | matches |

Nine core roles, seven wrong.

### Light

| role | PR #23 | canonical | |
|---|---|---|---|
| `background` | `#F8F9FA` | `#f8f9fa` | matches |
| `surface` | `#FFFFFF` | `#ffffff` | matches |
| `primary` | `#1C4771` | `#1c4771` | matches |
| `surfaceContainer` | `#F3F4F5` | **`#f8f9fa`** | different |
| `outline` | `#D4D9DE` | **`#e1e3e4`** | different |
| `navigationContainer` | `#FFFFFF` | **`#edeeef`** | different |
| `textPrimary` | `#003056` | **`#191c1d`** | see below |

### The `textPrimary` error is the serious one

PR #23 has no `textHeading` role at all. It sets `textPrimary` to `#003056` — the
canonical **heading** colour — so in Light every body string, every artist line,
every timestamp would render in heading navy. The canonical pages keep two roles
apart:

- `textHeading` `#003056` — screen and section titles
- `textPrimary` `#191c1d` — body text, track titles, history rows

They collapse to `#f5f7fa` in Dark, which is why a reconstruction built from Dark
would never notice the split existed.

**Action: the palette in `spec/tokens.mjs` is the canonical one and replaces
PR #23's wholesale.** No value from PR #23's token block survives except where it
already agreed.

---

## 2. What PR #23 got right and is kept

- **The flow inventory.** Sign in, create account, profile as guest, profile
  authenticated, settings, appearance, cloud sync, Last.fm — that is the right
  set, and it is carried over unchanged.
- **Guest as a first-class state.** The app must stay fully usable without an
  account. Kept, and made explicit on `profile-guest`.
- **The accent policy for brand colours.** PR #23's observation that
  `#00E5FF`, `#FFFF00` and `#FFCCFF` are unreadable as content on white
  (1.4:1, 1.07:1, 1.3:1) is correct and well argued, and the container/content
  split is the right resolution. It does not apply to any screen in this batch —
  none of them uses a brand accent — but it should be preserved for when the
  stream cards are designed. **This is the one part of PR #23 worth merging on
  its own terms.**
- **The settings grouping.** Account / appearance / playback / integrations /
  other. Kept, with Sleep Timer added under playback.

---

## 3. What is dropped

| PR #23 decision | Why dropped |
|---|---|
| A separate AMOLED/true-black theme | The canonical dark background is `#0f253e`, a navy. A true-black variant is a second dark theme to design, test and maintain, and it does not follow from anything in the file. `settings-appearance` offers System / Light / Dark only. |
| `focusRing` as a distinct cyan `#0090A3` | No canonical node has a focus state, and `secondary` itself is `PROPOSED` with no confirmed usage. The new screens use a 2px `primary` border for focus — one fewer invented colour. |
| Two splash options | Out of scope for this batch. The splash is entangled with the cold-start fix already merged for issue #9, so it should be designed against that behaviour rather than in the abstract. |

---

## 4. Screens replaced

Each PR #23 concept has a successor in `spec.json`, rebuilt on canonical
primitives rather than redrawn:

| PR #23 preview | successor |
|---|---|
| `auth-sign-in` | `auth-sign-in` |
| `auth-create-account` | `auth-create-account` |
| `auth-loading`, `auth-error` | folded into the field/button states; a full-screen spinner is not used anywhere else in the app |
| `profile-guest` | `profile-guest` |
| `profile-authenticated` | `profile-authenticated` |
| `settings` | `settings` |
| `settings-appearance` | `settings-appearance` |
| — | `settings-sync` (new; PR #23 named cloud sync but never drew it) |
| — | `settings-lastfm` (new; same) |
| `ui-kit` | not reproduced — the canonical `UI KIT / Radio Myata Dark` page is the real one |
| `splash-option-a/b` | dropped, see above |

---

## 5. Recommendation for PR #23 itself

Leave it **draft and unmerged**. It is useful as the record of where the accent
policy came from. Once `screens-3.6.6/` is approved, close PR #23 with a pointer
to it rather than merging — merging would put a second, conflicting token table
in the repo, which is the exact failure mode the canonical work exists to prevent.
