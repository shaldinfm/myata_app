# TrackKey v1 — the track identity contract

Status: **frozen**. Implemented in
[`TrackKey.kt`](../app/src/main/java/com/example/musicplayerapp/data/TrackKey.kt),
pinned by [`TrackKeyTest.kt`](../app/src/test/java/com/example/musicplayerapp/data/TrackKeyTest.kt).

This document is the specification. The code follows it, not the other way round.

## Why it exists

The station's metadata API (`api_all_tracks.php`) returns an artist string and a
title string per stream, and **no track id of any kind**. Every listener reaction —
today's Collection, and the LIKED / DISLIKED reaction state and reaction history
that follow — has to be filed under something derived from those two strings.

Room already does this implicitly: `favorites` carries a unique index on
`(artist, track)` over the **raw** strings. That is why a trailing space, a BOM, or
an en dash instead of a hyphen from the playout system silently creates a second,
unrelated row for the same song. TrackKey is the same identity made explicit,
deterministic, and shared, so the app, the local reaction store and any backend
agree on what "the same track" is.

Nothing in the app calls it yet. It lands on its own, ahead of its callers.

## The contract

### Normalisation

Applied identically to artist and to title. The steps are **ordered, numbered and
total**: every character takes exactly one branch, and the first branch that matches
wins. NFKC comes first so compatibility forms are already unpacked when characters
are inspected; casing comes last so it cannot influence any earlier decision.

| # | Step | Applies to | Result |
|---|---|---|---|
| 1 | Unicode NFKC | the whole string | `Normalizer.normalize(s, Form.NFKC)` |
| 2 | Remove format characters | the named invisibles below, and any other `Cf` | **removed** |
| 3 | Fold whitespace | TAB, LF, CR, VT, FF, NEL (U+0085), and Unicode `Zs` / `Zl` / `Zp` | **one space** |
| 4 | Remove remaining controls | any other `Cc` | **removed** |
| 5 | Fold dashes | the dash set below | ASCII `-` (U+002D) |
| 6 | Keep | everything else, surrogate pairs included | unchanged |
| 7 | Collapse, trim, lowercase | the folded string | runs of spaces → one space; ends trimmed; `lowercase(Locale.ROOT)`, never the device locale |

Steps 2–6 are a single pass in exactly that precedence.

**Step 3 before step 4 is the whole point.** TAB, LF, CR, VT, FF and NEL are `Cc`
*and* whitespace. They are whitespace here: `"Artist\nName"` normalises to
`artist name`, two words, not `artistname`. Every `Cc` that is not whitespace —
U+0000, U+0007, U+001F and the rest — is removed at step 4 without leaving a space
behind, which is what keeps U+001F usable as the field separator (below).

**Dash set folded to `-`:** U+002D, U+2010, U+2011, U+2012, U+2013, U+2014, U+2015,
U+2212, U+FE58, U+FE63, U+FF0D.

**Invisibles removed by name:** U+00AD, U+200B, U+200C, U+200D, U+200E, U+200F,
U+FEFF. All of them are `Cf` under current Unicode, so step 2's category check
would catch them anyway. They are named because those categories have changed
between Unicode versions — U+200B was `Zs` before Unicode 4.0.1, which would make
it a *space* under step 3 rather than a deletion — and this app runs on runtimes
from API 24 to API 36. A key whose value depends on the device's Unicode table is
not a stable key. U+FEFF is not hypothetical: `MetadataRepository` already trims it
out of the playlist feed by hand.

### Explicitly preserved

Everything that a listener would read as a difference between tracks:

- `feat.` / `ft.` / `with` credits, and any difference between them;
- parentheticals and version text — `(Radio Edit)`, `(Ewan Pearson Remix)`, `(Live)`;
- diacritics — `Beyoncé` and `Beyonce` are two keys;
- script — Cyrillic and Latin lookalikes are two keys;
- punctuation, `&`, apostrophes, slashes, digits, emoji and other astral characters.

This is **not** `ArtworkRepository`'s normalisation, which strips `feat.`,
punctuation and connectors and then matches fuzzily. That is right for finding a
cover and wrong for identity: it would merge distinct recordings into one reaction.
The two normalisations stay separate, and `ArtworkRepository` is untouched.

### Key derivation

```
SEPARATOR = U+001F                       (Unit Separator)
PREFIX    = "myata:trackkey:v1"

payload   = PREFIX + SEPARATOR + normalize(artist) + SEPARATOR + normalize(title)
track_key = lowercase_hex( SHA-256( UTF-8 bytes of payload ) )     // 64 chars, untruncated
```

The separator cannot occur inside either field — it is a `Cc` character and not
whitespace, so step 4 removes it outright — which is what stops `("a b", "c")` and
`("a", "b c")` producing one key. The version string is inside the hashed payload so a future v2 key space is
disjoint from v1's and the two can never collide.

### No key at all

`TrackKey.of` returns `null`, meaning no reaction can be recorded, when:

- either field is empty after normalisation (the metadata API returns `""` for both
  while a stream is between tracks); or
- the artist is the jingle sentinel `YOUR MUSIC! YOUR STATION!`, compared after
  normalisation. `StreamsViewModel` already refuses this on the PLAYER control;
  putting the guard here means every future entry point inherits it.

Callers treat `null` as an inert control, not as an error.

### Not part of identity

**Stream** (`myata` / `gold` / `myata_hits`). A reaction is to a track, not to a
track-on-a-stream, per the owner decision. Stream stays event and context metadata
for analytics. This also matches today's behaviour: the `favorites` unique index
already ignores `stream`.

## Accepted limitations

Conservative normalisation means the key splits more often than it merges. Both
directions are accepted for v1:

- **False merges** — distinct recordings that share artist and title: studio vs
  live vs re-recording where the title carries no marker, or two different songs of
  the same name by the same artist.
- **False splits** — one track under several keys: apostrophe and quote glyph
  variants (U+2019 vs `'`), diacritic-stripped spellings, Cyrillic/Latin
  homoglyphs, `feat.` vs `ft.`, artist order in `A & B`, `The Beatles` vs
  `Beatles`, and optional descriptors present on some plays only.
- **Upstream re-tagging** changes the key: the old row stays LIKED in the
  Collection while the same song reads NEUTRAL on the PLAYER.

For aggregate analytics, splits under-count and merges over-count, so totals are a
floor rather than an exact count. Originals are always stored next to the key, so
splits can be folded later, server-side, by a `track_key → canonical_track_key`
alias table — without changing the client or the key.

## Changing this contract

Every reaction ever recorded is filed under a v1 key. Changing any step above
re-keys the whole userbase and orphans that data.

So: **the rules are frozen.** A change is a **v2 key plus a migration**, never an
edit to v1. That is what the version string inside the payload is for.

`TrackKeyTest`'s golden vectors are hard-coded digests derived from this document
by an independent implementation — not a snapshot of what the code happens to
return. If one of them fails, the normalisation changed, and the fix is a migration
plan, not a new expected value.

## Golden vectors

| artist | title | key |
|---|---|---|
| `Depeche Mode` | `Enjoy the Silence` | `0e81089c8caec4294651945b2d7253272e4a7009fd6ce66b1a8a92ed24888651` |
| `Земфира` | `Искала` | `5d28c0ac6f793f82d8038406324314ca7a1f1392efccd4c79b0c74051eda0c5b` |
| `Calvin Harris feat. Rihanna` | `This Is What You Came For (Radio Edit)` | `fad7e5957d2e4432e60be40237abfa7f43c68ce37d736c326cc0ac161c06af82` |
| `Beyoncé` | `Halo` | `bee6dc791fa9680cec9aae24df9f1cccba9f8be897e2da1a65d3b792d86bfbea` |
| `Beyonce` | `Halo` | `2fb3e3f65cbbf5141a17d23bd78631799704b6a25fcd5b7725c93f50e77dab2a` |
| `Nick Cave` | `Red Right Hand - Live` | `b419b6ea145f9e3e5a7ac280e027298fc261a888121601afce1326833baa0d01` |
| `AC/DC` | `T.N.T.` | `5ba97feb2d040f45bc5ff161994182d7249cad8e6d091cbb7e7a1cc6e6311539` |
