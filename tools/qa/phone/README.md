# Phone rendering baseline (A3)

Evidence that the A3 theme refactor did not change how the phone UI renders.

```bash
node tools/qa/phone/capture-phone-baseline.mjs before
# ... apply the refactor, rebuild, reinstall ...
node tools/qa/phone/capture-phone-baseline.mjs after
node tools/qa/phone/capture-phone-baseline.mjs after --night
```

Set `PHONE_SERIAL` if the phone emulator is not `emulator-5556`.

## Why screenshots alone cannot be the gate here

`MainActivity` picks one of ten window backgrounds at random per launch, so two
runs legitimately differ. The gate is therefore the uiautomator hierarchy — node
ids, classes and bounds — which is stable across launches.

The randomness itself is checked behaviourally rather than by reading a field the
platform does not expose: launch twelve times, hash each screen, and count the
distinct results. **One distinct result across twelve launches would mean the
feature had been collapsed.** Before the refactor: 10 of 12. After: 11 of 12.

The harness also dismisses the emulator's "System UI isn't responding" dialog,
which appears under software GL and would otherwise land in a screenshot and be
mistaken for a rendering change.
