# Font trial — Muller vs Montserrat vs Onest

A bounded visual comparison in Figma. Montserrat is the primary candidate, Onest
the reference; Manrope is dropped.

**Nothing frozen is modified.** Every frame is cloned onto a brand-new page and
only the clones are restyled. Re-running creates another page rather than
overwriting the last one, so trials never collide and nothing needs undoing
beyond deleting the page.

## Before running

Both families must be enabled in the Figma file — they are on Google Fonts. The
plugin checks first and refuses to build anything if either is missing, naming
the exact weights it could not find.

## Running it

1. Open the 3.6.6 Figma file.
2. Plugins → Development → Import plugin from manifest… → this `manifest.json`.
3. Run **Radio Myata · Font trial**.
4. Leave *Inject the two long Russian titles* checked.
5. Press **Build trial page**.

It creates a page named `FONT TRIAL <date time>` and switches to it. Delete that
page when you are done.

## What it builds

Seven frames × three columns, laid out left to right:

| column | what it is |
|---|---|
| `… · Muller (baseline)` | untouched clone, for reference |
| `… · Montserrat` | primary candidate |
| `… · Onest` | fallback reference |

| frame | why it is in the set |
|---|---|
| `HOME` | BottomNav labels, headings, mini-player |
| `PLAYER` | Black 24 now-playing title, history rows, the one AUTO line height |
| `COLLECTION` | track rows and the widest button |
| `ABOUT US` | long paragraph and the donation CTA |
| `history-content` | Broadcast History — variable height, no ellipsis |
| `sleep-timer-custom` | custom time entry |
| `settings` | Profile/Settings rows, buttons and labels |

## The rule it enforces

The frozen type scale wins; the new font's natural metrics do not get to redefine
the design.

- `fontSize` is never written.
- `letterSpacing` is never written.
- An explicit `lineHeight` is carried over verbatim. 90 of the 91 frozen text
  nodes already pin one in PIXELS, which is why a font with a 22–37% taller line
  box does not move this design in Figma.
- An `AUTO` line height would be redefined by the new font, so it is pinned
  **before** the swap to the value Muller resolves it to. Muller's `hhea` is
  exactly 1000/1000 units, so its AUTO line height is exactly `1.0 × fontSize` —
  measured from the shipped binaries, not guessed.

Exactly one frozen node is AUTO: `PLAYER` → "PAUSE", 26.77 Medium. Left alone it
would grow to 32.6px under Montserrat or 34.1px under Onest.

## Weight mapping

| Muller | → | candidate |
|---|---|---|
| Light 300 | → | Light |
| Regular 400 | → | Regular |
| Medium 500 | → | Medium |
| Bold 700 | → | Bold |
| Black 900 | → | Black |
| Heavy 900 | → | Black |

Muller is inconsistent about where the weight lives: some cuts are family
`Muller` with style `Black`, others are family `Muller Black` with style
`Regular`, because that is how the TTF name tables are built. A weight named in
the *family* therefore wins whenever the style is the generic `Regular` —
otherwise `Muller Black / Regular` would silently map to Regular and lose the
weight. All ten combinations are unit-checked.

## Stress titles

The two longest Russian examples are injected into History rows on **all three**
columns, so the comparison stays like-for-like:

- `КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ`
- `Прогулка по воде под дождём в конце ноября`

The plugin reports which nodes it replaced and what they said before.

## What to look for

The panel prints each frame's height per column with the delta against the
baseline, which catches Auto Layout growth without measuring by hand. By eye,
check:

- **BottomNav** — `Коллекция` is the tightest label in the app. Montserrat is
  predicted +10.6%, leaving ~2.1dp inside the 320dp item; this frame is at 390dp
  so it will look comfortable here. That risk is a 320dp Android question, not
  one this frame can answer.
- **History rows** — Montserrat is predicted to take the upper-case stress title
  from 2 lines to 3.
- **ABOUT US paragraph** — predicted to stay at 3 lines in both candidates.
- **Buttons** — `Экспортировать список` is the widest; Montserrat is predicted
  +10.2%.
- **Visual weight** — both candidates have a larger x-height than Muller, so text
  reads slightly bigger at the same size.
