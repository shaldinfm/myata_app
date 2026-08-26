# auth-sign-in / auth-create-account (G-A4c1)

```
node tools/qa/phone/capture-auth.mjs <api24|api36>
```

`AuthLayoutTest` measures both screens - every box, every gap, every token colour,
both themes, four widths - and measuring is the stronger check. This harness covers
the one thing a measurement cannot: what the screen looks like, for a human to hold
next to the Figma frame.

It pins the density to **443dpi** for the length of the run and resets it afterwards,
including if it throws. That is the one density at which this AVD is 390dp wide -
`1080 / (443/160) = 390.1` - which is the width the frames are drawn at. It is a
deliberate departure from the standing convention that manual visual QA runs at the
panel's default 420dpi, and it exists only so a screenshot is a like-for-like
comparison.

## What it captures, per theme

| shot | frame |
|---|---|
| `auth-sign-in` | 2517:2603 / 2517:3570 |
| `auth-sign-in-loading` | none - transient state on the same screen |
| `auth-sign-in-error` | none - inline error on the same screen |
| `auth-create-account` | 2517:2624 / 2517:3591 |
| `auth-create-account-error` | none - field validation on the same screen |

The three frameless ones are what the brief asked for instead of new frames: the
existing screen with transient state, and inline error copy in the screen's own
style. What is checked about them is that they change **nothing** above the control
that produced them.

## It sends nothing

No account is created and no password is recovered. The sign-in it performs uses a
`zz-ga4c1-probe@example.com` address that does not exist, so the only thing it
reaches is the refusal path. Screenshots are gitignored (`*.png`); `metadata.json`
carries the measured dp for every row and is the artefact worth keeping.
