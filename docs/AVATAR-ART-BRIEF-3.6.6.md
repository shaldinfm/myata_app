# Radio Myata listener avatars - creative brief (draft for owner approval)

> **SUPERSEDED (2026-09-14).** The owner stopped regeneration and approved a final 24-avatar set
> from the existing candidate pool; the slot concepts below no longer apply. What ships is in
> [PROFILE-AVATAR-3.6.6.md](PROFILE-AVATAR-3.6.6.md#assets) and `tools/avatars/`. This brief is
> kept as a record.

Sixteen original avatars for `Выбор аватара`, replacing the 15 uncleared Material 3 Design Kit
renders. **Status: DRAFT - nothing is generated or swapped until the owner approves.**

Slots map 1:1 to the stored keys `myata-01` … `myata-16`, in grid order (left to right, top to
bottom). `myata-06` already exists (owner-created, cleared) and is the current style reference.
It is **not guaranteed to stay final**: after the style test the owner may regenerate all 16 for
consistency.

**Next step - external 3-avatar style test (owner, ChatGPT Image / Gemini):** `myata-01` vinyl
collector, `myata-08` night-city / club listener, `myata-13` late-night listener 60+. The rest of
this brief is unchanged until the test has been reviewed.

## Set-wide art direction

- **World.** Listeners of an indie / alternative / electronic radio station: gig regulars, crate
  diggers, night people, makers. Contemporary, a little offbeat, warm rather than corporate.
- **Rendering.** Polished soft 3D: rounded forms, matte "soft-touch" materials, gentle key light
  from upper left plus a subtle cool rim light, soft contact shadow. One consistent camera across
  the set: a slight three-quarter or front view at eye level.
- **Radio Myata signature** (what makes the set ours, not generic):
  - A small mint accent (#5FD9B4) somewhere on every character: a lace, a pin, a lens tint, a
    hair clip.
  - Backgrounds are a flat colour disc with one soft graphic shape behind the head: a sound-wave
    arc, an equaliser bar, a vinyl groove ring or a mint-leaf silhouette. Never text.
- **Framing.** Head-and-shoulders or chest-up. **No hand-to-face poses** (`myata-06` already has
  one). Hands appear in at most 4 slots, and only when the prop needs them. The face takes
  roughly 35-45% of the circle's diameter, with the eyes at about 40% from the top.
- **Readability.** It must read at 64dp (about 170-256px on phones): clear silhouette, strong
  value contrast between face, hair and background, and no detail finer than about 8px at 1080px.
  Test the thumbnail at 64px and 170px before approving.
- **Expressions.** Open, friendly, individual: a smirk, a laugh, calm, mid-nod, eyes closed to
  the music. No blank stares.
- **Diversity across the 16.**
  - Roughly 5 feminine-presenting, 5 masculine-presenting, 6 androgynous or ambiguous.
  - A full spread of skin tones and hair textures; ages from about 18 to 60+.
  - Visible individuality: glasses, freckles, vitiligo, piercings, tattoos, head coverings, grey
    hair.
  - Each is a person first, not a single-trait stereotype.

### Must not

- Resemble any real person, musician or public figure.
- Show logos, brand names, band merch, recognisable products or text of any kind.
- Reuse the Material 3 avatar set's poses, props, outfits or compositions. Specifically: no VR
  headset, megaphone, spray can, helmet with skateboard at the side, guide dog, white cane, puppy,
  flowers with a bee, pencil, welding torch, fur-hood parka, beanie with sunglasses and coffee, or
  hand-on-chin pose.
- Imitate a named artist or studio. Prompts must never name artists, studios or the kit.

## Technical spec

| | |
|---|---|
| Master | 1080×1080 px, sRGB PNG, one file per slot |
| Shape | Full-bleed square or a disc with a transparent outside; the export clips to a circle either way |
| Safe area | Keep the face and key props inside the central 88% circle; the 1dp outline / 2dp selected ring overlays the outer edge |
| Background colour | Flat, saturated-but-soft discs, all different. **Avoid** anything close to #1C4771 (navy) or #5FD9B4 (mint): those are the selected ring colours in light and dark, and would hide the selection. Must sit well on both #F8F9FA and #0F253E. |
| Shipped as | 384px WebP (q95) via `tools/figma-export/avatars/export_avatars.py`; about 25 KB each |
| Provenance | Per slot: tool/artist, date, prompt (if generated), rights document reference → manifest |

**Suggested background palette** (one per slot, shown in the table): coral #FF8A6B, butter
#FFD66B, lilac #B9A4F2, sky #7CC4F5, peach #FFB38A, lime #C7E36B, rose #F59AC0, sand #E8C9A0,
cobalt-light #8FA8FF, tangerine #FFA94D, pistachio #A8D89A, blush #F7B6B0, teal-light #7AD1D6,
mustard #E6B84A, orchid #D9A0E6. Teal-light and pistachio stay clearly away from #5FD9B4.

## The 16 slots

| Key | Character | Look | Pose / expression | Background |
|---|---|---|---|---|
| myata-01 | Vinyl collector | Round tortoiseshell glasses, cropped curly hair, oversized cardigan; a record sleeve (blank artwork) held at chest | Slight smile, looking just off-camera | butter + groove ring |
| myata-02 | Indie gig regular | Shag cut with fringe, denim jacket covered in abstract enamel pins (no symbols), wristband | Laughing, head tilted | coral + sound-wave arc |
| myata-03 | Synth / electronic fan | Shaved head with a mint-tinted visor-style clip-on shade pushed up, tech-fabric jacket, a coiled cable over the shoulder | Calm, eyes half closed | lilac + equaliser bars |
| myata-04 | Alternative-fashion listener | Two-tone bob (black / silver), layered chains, septum ring, platform collar | Confident smirk, chin slightly down | rose + leaf silhouette |
| myata-05 | Photographer / creative | Bucket hat, film camera on a strap (no brand), freckles, striped tee | Friendly, mid-glance over shoulder | sky + ring |
| **myata-06** | **Headphones / music obsessive** | **Existing owner-created avatar - keep** | - | yellow (existing) |
| myata-07 | Festival listener | Braids with small beads, sun-faded bandana on the wrist, glitter freckles, mesh top over tee | Eyes closed, big grin, mid-dance | tangerine + sound-wave arc |
| myata-08 | Night-city / club listener | Slick-back hair, iridescent bomber, small hoop earrings; neon-ish rim light in mint | Cool, direct gaze | cobalt-light + equaliser bars |
| myata-09 | Skater | Buzz cut, oversized flannel shirt, beanie tucked in back pocket (not worn); deck visible only as a diagonal edge behind the shoulder | Relaxed half-smile | lime + ring |
| myata-10 | Musician | Long wavy hair, guitar strap across the chest (no instrument brand), rolled sleeves, small tattoo on the forearm | Mid-nod, content | peach + groove ring |
| myata-11 | Designer | Asymmetric haircut, chunky coloured glasses, clean mock-neck top, a fanned colour-swatch card clipped to the collar | Thoughtful, slight smile | pistachio + leaf silhouette |
| myata-12 | Understated minimalist | Grey-streaked short hair, black crewneck, a single small earring, small wireless earbud | Calm, quiet smile | sand (no shape - the one plain disc) |
| myata-13 | Late-night radio listener (60+) | Silver hair in a low bun, oversized knit, retro portable radio strap on the shoulder (no brand) | Warm, amused | blush + sound-wave arc |
| myata-14 | Cassette / mixtape maker | Vitiligo, curly high-top, varsity-style jacket without lettering, a blank cassette tucked behind the ear | Playful wink | teal-light + ring |
| myata-15 | DJ-in-training | Headscarf in a bold pattern, one headphone cup held to the ear by its band at the neck (distinct from 06), bomber | Focused, slight smile | orchid + equaliser bars |
| myata-16 | Bedroom producer | Messy undercut, hoodie with the hood down, small MIDI knob keyring on the zip, nose stud | Excited, looking up | mustard + leaf silhouette |

Check against the Must-not list before approval. For example, 09 must not become the kit's
skater, and 15 must read clearly different from 06.

## Prompt sheet (if the owner chooses an image-generation workflow)

Use one shared prefix and one shared negative block. Change only the slot line. Review each output
against the brief and the kit list above; regenerate rather than hand-copying any reference.

**Prefix**

> Original character avatar for a music radio app. Polished soft 3D illustration, matte
> soft-touch materials, rounded friendly forms, gentle upper-left key light with a subtle cool
> rim light. Head-and-shoulders portrait centred in a circular composition, face large and
> readable, eyes at about 40% from the top. Flat {BACKGROUND HEX} background disc with one soft
> {SHAPE} shape behind the head. A small mint (#5FD9B4) accent detail on the character. Clean,
> contemporary, warm. Square 1:1.

**Slot line** (example, myata-01)

> Character: a vinyl collector with round tortoiseshell glasses, cropped curly hair and an
> oversized cardigan, holding a plain blank record sleeve at chest height, slight smile, looking
> just off-camera.

**Negative / exclusions**

> no text, no letters, no logos, no brand marks, no band merchandise, no real person likeness,
> no celebrity, no hand touching face, no VR headset, no megaphone, no spray can, no guide dog,
> no white cane, no puppy, no welding, no fur hood, no photorealism, no harsh shadows, no busy
> background, no cropping of the face at the circle edge

Record for every accepted image: tool and version, date, full prompt, seed (if available), and
the output file hash, in the manifest.

## Approval checklist for the owner

- [ ] Slot list and characters (swap any in the table).
- [ ] Keep `myata-06` as is, or regenerate all 16 to match.
- [ ] Palette, including the navy/mint avoidance rule.
- [ ] Workflow: commissioned illustrator, in-house, or image generation - and the matching rights record.
- [ ] Style test: `myata-01`, `myata-08`, `myata-13`, generated externally by the owner and reviewed before anything else is produced.
- [ ] After the test: keep `myata-06`, or regenerate all 16.
