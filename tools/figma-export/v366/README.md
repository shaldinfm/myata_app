# Radio Myata 3.6.6 — Light mode and the missing screens

Design proposal for 3.6.6. **Nothing here is implemented in Android**, and no Android
code changes with it.

It continues the approved dark design in [`../dark-theme/`](../dark-theme/) rather than
replacing it: same layout system, same components, same geometry. What it adds is the
light half of the theme pair and the screens that never existed — auth, profile and
settings — plus two directions for the splash.

## Files

| File | Role |
|---|---|
| `design.json` | Machine-readable spec: light **and** dark token sets, accent policy with measured contrast, screen inventory, auth flow, settings groups |
| `preview.html` | Renderer. One component set, rendered per theme from CSS custom properties |
| `render-previews.mjs` | Screenshots every frame into `previews/` |
| `previews/*.png` | 31 rendered screens + a UI kit per theme |
| `previews/manifest.json` | Name, file and size of every preview |

## Why light is not "dark with a white background"

The dark accents are chosen against `#0B1D31`. Dropped onto white they fail badly:
myata cyan `#00E5FF` is 1.4:1, gold `#FFFF00` is 1.07:1, xtra pink `#FFCCFF` is 1.3:1.
None is legible as text or as an icon.

So each brand colour has **two roles** in light mode:

- as a **container** it keeps the vivid dark-mode value and carries dark text on top;
- as **content** (text, icon, stroke) it uses a darkened variant.

| Stream | Container | Content | Contrast on white |
|---|---|---|---|
| MYATA | `#00E5FF` | `#0090A3` | 4.7:1 |
| GOLD | `#FFFF00` | `#7A6A00` | 5.2:1 |
| XTRA | `#FFCCFF` | `#A03D8F` | 4.6:1 |

Both content variants already exist in the app's palette, so nothing new is invented.

Other light-specific decisions, all in `design.json`:

- **Elevation cannot be read as a lighter fill on white.** Light separates surfaces with
  a 1px outline plus a soft shadow; `surface` and `surfaceElevated` are deliberately equal.
- The **bottom navigation** becomes white with a top divider, where dark uses a distinct
  `navigationContainer` fill.
- The **mini-player stays a filled primary block in both themes** — it is the one element
  that must read as "something is playing" at a glance.
- **Artwork is never tinted.** Light adds a 1px outline so pale covers do not bleed into
  the white surface.

## Screens

**Existing, in light (11):** home · player · collection · collection-empty · about ·
player-menu · collection-menu · music-service sheet · sleep-timer sheet ·
report-problem sheet · full-history.

**New, in light and dark (8 × 2):** sign in · create account · auth error · auth loading ·
profile (authenticated) · profile (guest) · settings · settings → appearance.

**Splash (2):** option A and option B — see below.

Plus one UI kit per theme: colour, typography, buttons and their states, controls, nav
and mini-player.

## Auth, deliberately small

**Guest is a first-class way to use the app, not a degraded mode.** Playback, all three
streams, history and on-device favourites work with no account. An account buys exactly
one thing: favourites in the cloud.

Three steps, no more: sign in, or create account, or continue as guest. There is no
onboarding carousel, no sign-up wall, and no social login — social login and email
verification are not decided, so they are not drawn.

The error state is inline on the form rather than a dialog, and **the entered values are
kept** — nothing is more annoying than a wrong password clearing the email too. The
loading state disables the inputs and turns the primary button into a progress
indicator; nothing else on the screen moves.

## Settings

Grouped list with a slot for every agreed future feature — Appearance (System / Light /
Dark), Account (profile, cloud favourites), Services (Last.fm), Support (donate, report,
about), and Sign out. **Last.fm and cloud favourites are drawn, not wired.** For a guest,
the cloud-favourites row is disabled with a one-line reason instead of being hidden, so
the feature is discoverable.

## Splash: two options

- **Option A — keep the 10 random artworks** (`dark-splash-option-a.png`). Distinctive,
  already loved, and zero risk. It stays inconsistent with a branded first impression and
  keeps ten themes in the Android theme table.
- **Option B — one branded splash** (`dark-splash-option-b.png`). Consistent, easier to
  theme for light/dark, and it removes `AppTheme0..9` from the migration surface.

**Recommendation: keep A for 3.6.6.** The random art is part of the app's character, and
dropping it is a product decision that has nothing to do with the redesign. It costs one
extra theme attribute to carry, which is cheap. Option B is worth revisiting once the
rest of the redesign has landed and the brand mark is final.

Nothing about splash changes in Android code either way in this PR.

## Regenerating

```powershell
# Uses an already-installed Chrome; nothing is downloaded.
$env:PLAYWRIGHT_REQUIRE_FROM='<path to a package.json whose node_modules has playwright-core>'
node tools/figma-export/v366/render-previews.mjs
```

`CHROME_PATH` overrides Chrome detection.

**No third-party album artwork.** Every cover in these previews is the app's own
`zaglushka_*` placeholder drawable, which is tracked, so the previews regenerate
identically on a clean clone. Stream banners are the app's own vector drawables
converted to SVG at render time; type is Muller from `app/src/main/res/font`.

## What still needs a decision before Android work

1. Sign-off on the light token set in `design.json` — it is the input to the theme resources.
2. Splash: option A or B.
3. Whether the tab bar gains a Profile destination or profile stays behind the header
   avatar. These previews show the avatar entry point and put Settings behind Profile.
