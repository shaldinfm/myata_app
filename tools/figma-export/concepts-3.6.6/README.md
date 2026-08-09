# Radio Myata 3.6.6 — concepts (NOT approved)

**Status: unapproved concepts.** Nothing here is signed off, and nothing is implemented
in Android. No Android code changes with it.

## What this contains

Screens that **do not exist yet** in any approved design, plus a proposed light token set:

- **Auth (light + dark):** sign in, create account, error state, loading state
- **Profile (light + dark):** authenticated, guest
- **Settings (light + dark):** settings, appearance (System / Light / Dark)
- **Splash:** two directions, A (keep the ten random artworks) and B (single branded splash)
- **UI kit** per theme, and the proposed light/dark token pair in `design.json`

## What this deliberately does NOT contain

**No light versions of the existing screens.** Home, player, collection, collection-empty,
about, both menus, the three sheets and full-history are not here.

An earlier attempt produced them by reconstructing the layout from `preview.html` and the
rendered PNGs. That was the wrong method and it drifted from the approved design — on Home
alone: the wrong header text, section titles at y=96 instead of y=80, three stream cards
instead of two, the mini-player at the wrong offset and in the wrong state. Those files
were deleted rather than patched, because a "mostly right" reconstruction of a canonical
screen is worse than none.

**The canonical source for existing screens is the real Figma project.** Light duplicates
of them are blocked until that is available. The required order is:

1. Get the Figma frames.
2. Compare them against `../dark-theme/code.js`, `../dark-theme/preview.html` and
   `../dark-theme/dark-screens.json`.
3. Record any drift. **Figma wins on every disagreement.**
4. Only then produce light duplicates: identical layout, content, elements and states —
   changing nothing but semantic tokens and the contrast-driven details listed below.

## The proposed light tokens

This part stands on its own and is worth reviewing regardless, because it is the direct
input to the Android theme resources.

The dark accents are chosen against `#0B1D31`. On white they fail: myata cyan `#00E5FF`
is 1.4:1, gold `#FFFF00` is 1.07:1, xtra pink `#FFCCFF` is 1.3:1 — none legible as text or
icon. So each brand colour gets two roles in light:

| Stream | Container (vivid, dark text on top) | Content (text/icon/stroke) | Contrast |
|---|---|---|---|
| MYATA | `#00E5FF` | `#0090A3` | 4.7:1 |
| GOLD | `#FFFF00` | `#7A6A00` | 5.2:1 |
| XTRA | `#FFCCFF` | `#A03D8F` | 4.6:1 |

Both content variants already exist in the app's palette. Full token pair, measured
contrast and the light-specific rules (outline-plus-shadow instead of raised fills, since
elevation cannot be read as a lighter surface on white) are in `design.json`.

These are exactly the "necessary contrast adjustments" that a 1:1 light duplicate is
allowed to make. Nothing else.

## Auth concept, in one paragraph

Guest is a first-class way to use the app, not a degraded mode: playback, all three
streams, history and on-device favourites need no account, and an account buys only cloud
favourites. Three steps — sign in, create account, continue as guest. No onboarding
carousel, no sign-up wall, no social login (undecided, so not drawn). The error state is
inline and keeps what the user typed; loading disables the inputs and turns the primary
button into a progress state.

The shared components these concepts borrow — bottom navigation, mini-player — are
**provisional** and must be reconciled against Figma along with everything else.

## Regenerating

```powershell
$env:PLAYWRIGHT_REQUIRE_FROM='<path to a package.json whose node_modules has playwright-core>'
node tools/figma-export/concepts-3.6.6/render-previews.mjs
```

`CHROME_PATH` overrides Chrome detection.

No third-party album artwork: every cover is the app's own `zaglushka_*` placeholder
drawable, which is tracked, so previews regenerate on a clean clone.
